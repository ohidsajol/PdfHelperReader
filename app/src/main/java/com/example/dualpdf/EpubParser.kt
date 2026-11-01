package com.example.dualpdf

import android.content.Context
import android.net.Uri
import com.itextpdf.text.BaseColor
import com.itextpdf.text.Document
import com.itextpdf.text.Element
import com.itextpdf.text.FontFactory
import com.itextpdf.text.PageSize
import com.itextpdf.text.Paragraph
import com.itextpdf.text.pdf.PdfWriter
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class EpubBook(
    val title: String,
    val author: String,
    val chapterTexts: List<String>
)

object EpubParser {

    fun isEpub(ctx: Context, uri: Uri): Boolean {
        val name = Sidecar.displayName(ctx, uri) ?: uri.lastPathSegment ?: ""
        if (name.lowercase().endsWith(".epub")) return true
        val mime = ctx.contentResolver.getType(uri)
        return mime?.lowercase()?.contains("epub") == true
    }

    fun parseEpub(inputStream: InputStream): EpubBook {
        val tempDir = File.createTempFile("epub_extract_", "").apply {
            delete()
            mkdirs()
        }

        try {
            // 1. Unzip all files into tempDir
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val file = File(tempDir, entry.name)
                        file.parentFile?.mkdirs()
                        file.outputStream().use { os ->
                            zis.copyTo(os)
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            // 2. Parse META-INF/container.xml
            val containerFile = File(tempDir, "META-INF/container.xml")
            var opfRelPath = "content.opf"
            if (containerFile.exists()) {
                val factory = XmlPullParserFactory.newInstance()
                factory.isNamespaceAware = true
                val parser = factory.newPullParser()
                parser.setInput(containerFile.inputStream(), "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                        val fullPath = parser.getAttributeValue(null, "full-path")
                        if (!fullPath.isNullOrBlank()) {
                            opfRelPath = fullPath
                            break
                        }
                    }
                    event = parser.next()
                }
            }

            val opfFile = File(tempDir, opfRelPath)
            val opfDir = opfFile.parentFile ?: tempDir

            // 3. Parse OPF File
            var title = "EPUB Document"
            var author = "Unknown"
            val manifest = mutableMapOf<String, String>() // id -> relative href
            val spine = mutableListOf<String>() // idrefs

            if (opfFile.exists()) {
                val factory = XmlPullParserFactory.newInstance()
                factory.isNamespaceAware = true
                val parser = factory.newPullParser()
                parser.setInput(opfFile.inputStream(), "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "title" -> title = parser.nextText()
                            "creator" -> author = parser.nextText()
                            "item" -> {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                                    manifest[id] = href
                                }
                            }
                            "itemref" -> {
                                val idref = parser.getAttributeValue(null, "idref")
                                if (!idref.isNullOrBlank()) {
                                    spine.add(idref)
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }

            // 4. Extract text from XHTML chapters in spine order
            val chapterTexts = mutableListOf<String>()
            for (idref in spine) {
                val href = manifest[idref] ?: continue
                val chapterFile = File(opfDir, href)
                if (chapterFile.exists()) {
                    val htmlContent = chapterFile.readText(Charsets.UTF_8)
                    val cleanText = htmlToPlainText(htmlContent)
                    if (cleanText.isNotBlank()) {
                        chapterTexts.add(cleanText)
                    }
                }
            }

            return EpubBook(title, author, chapterTexts)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun htmlToPlainText(html: String): String {
        var text = html.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        text = text.replace(Regex("</h[1-6]>", RegexOption.IGNORE_CASE), "\n\n")
        text = text.replace(Regex("<[^>]+>"), "")
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&rsquo;", "'")
            .replace("&lsquo;", "'")
            .replace("&rdquo;", "\"")
            .replace("&ldquo;", "\"")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
        return text.trim()
    }

    fun convertEpubToPdf(epub: EpubBook, outputFile: File) {
        val document = Document(PageSize.A4, 36f, 36f, 36f, 36f)
        PdfWriter.getInstance(document, outputFile.outputStream())
        document.open()

        val titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22f, BaseColor.BLACK)
        val authorFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 14f, BaseColor.DARK_GRAY)
        val bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 10.5f, BaseColor.BLACK)

        val pTitle = Paragraph(epub.title, titleFont)
        pTitle.alignment = Element.ALIGN_CENTER
        pTitle.spacingAfter = 10f
        document.add(pTitle)

        val pAuthor = Paragraph("By ${epub.author}", authorFont)
        pAuthor.alignment = Element.ALIGN_CENTER
        pAuthor.spacingAfter = 20f
        document.add(pAuthor)

        for (chapterText in epub.chapterTexts) {
            val lines = chapterText.split("\n")
            var paragraphText = StringBuilder()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    if (paragraphText.isNotEmpty()) {
                        val p = Paragraph(paragraphText.toString(), bodyFont)
                        p.spacingAfter = 8f
                        document.add(p)
                        paragraphText = StringBuilder()
                    }
                } else {
                    if (paragraphText.isNotEmpty()) paragraphText.append(" ")
                    paragraphText.append(trimmed)
                }
            }
            if (paragraphText.isNotEmpty()) {
                val p = Paragraph(paragraphText.toString(), bodyFont)
                p.spacingAfter = 8f
                document.add(p)
            }
        }

        document.close()
    }
}
