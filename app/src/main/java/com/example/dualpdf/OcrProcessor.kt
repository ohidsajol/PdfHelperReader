package com.example.dualpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.itextpdf.text.pdf.BaseFont
import com.itextpdf.text.pdf.PdfContentByte
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.PdfStamper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object OcrProcessor {
    suspend fun processPdf(
        ctx: Context,
        inputPdf: File,
        outputPdf: File,
        forceOcr: Boolean = false,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val reader = PdfReader(inputPdf.absolutePath)
        val totalPages = reader.numberOfPages
        val stamper = PdfStamper(reader, FileOutputStream(outputPdf))

        val pfd = ParcelFileDescriptor.open(inputPdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdfRenderer = PdfRenderer(pfd)

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val baseFont = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED)

        try {
            for (i in 1..totalPages) {
                ensureActive()
                onProgress(i, totalPages)
                val pageText = com.itextpdf.text.pdf.parser.PdfTextExtractor.getTextFromPage(reader, i)

                // Skip if page seems to already have text (unless forced)
                if (!forceOcr && pageText.trim().length > 50) {
                    continue
                }

                val androidPage = pdfRenderer.openPage(i - 1)
                val scale = 2.0f
                val bmpWidth = (androidPage.width * scale).toInt().coerceAtLeast(1)
                val bmpHeight = (androidPage.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                androidPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                androidPage.close()

                val image = InputImage.fromBitmap(bmp, 0)
                val result = recognizer.process(image).await()

                val overContent = stamper.getOverContent(i)
                overContent.beginText()
                overContent.setTextRenderingMode(PdfContentByte.TEXT_RENDER_MODE_INVISIBLE)

                val pdfWidth = reader.getPageSize(i).width
                val pdfHeight = reader.getPageSize(i).height

                val scaleX = pdfWidth / bmpWidth.toFloat()
                val scaleY = pdfHeight / bmpHeight.toFloat()

                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox
                        if (box != null) {
                            val pdfLeft = box.left * scaleX
                            val pdfBottom = pdfHeight - (box.bottom * scaleY)
                            val pdfBoxHeight = (box.bottom - box.top) * scaleY

                            val fontSize = pdfBoxHeight.coerceAtLeast(1f)
                            overContent.setFontAndSize(baseFont, fontSize)
                            overContent.setTextMatrix(pdfLeft, pdfBottom)
                            overContent.showText(line.text)
                        }
                    }
                }
                overContent.endText()
                bmp.recycle()
            }
        } finally {
            try { recognizer.close() } catch (_: Exception) {}
            stamper.close()
            reader.close()
            pdfRenderer.close()
            pfd.close()
        }
    }
}
