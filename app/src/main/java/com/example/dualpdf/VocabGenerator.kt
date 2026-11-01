package com.example.dualpdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.io.InputStreamReader

data class VocabResult(
    val outputPdf: File,
    val pageMap: Map<Int, Int> // bookPage -> vocabPage
)

object VocabGenerator {

    private var commonWords = setOf<String>()
    private var dictionary = mapOf<String, String>()
    private var initialized = false

    suspend fun init(ctx: Context) = withContext(Dispatchers.IO) {
        if (initialized) return@withContext

        // Load common words (acting as Zipf approximation)
        ctx.assets.open("google-10000-english-no-swears.txt").bufferedReader().use { reader ->
            val set = mutableSetOf<String>()
            reader.forEachLine {
                val w = it.trim().lowercase()
                if (w.isNotEmpty()) set.add(w)
            }
            commonWords = set
        }

        // Load JSON dictionary
        ctx.assets.open("dictionary.json").use { input ->
            InputStreamReader(input, "UTF-8").use { reader ->
                val type = object : TypeToken<Map<String, String>>() {}.type
                dictionary = Gson().fromJson(reader, type)
            }
        }
        initialized = true
    }

    private fun lookupDefinition(word: String): String {
        val wLower = word.lowercase()

        // 1. Direct lowercase lookup
        var defn = dictionary[wLower] ?: return ""
        if (defn.isBlank()) return ""

        // 2. Reference resolution ($#$root_key)
        if (defn.startsWith("\$#\$")) {
            val rootKey = defn.removePrefix("\$#\$").trim().lowercase()
            val rootDef = dictionary[rootKey]
            if (!rootDef.isNullOrBlank()) {
                defn = rootDef
            }
        }

        return defn
    }

    suspend fun generate(
        ctx: Context,
        inputPdf: File,
        outputPdf: File,
        pageRange: IntRange? = null,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): VocabResult = withContext(Dispatchers.IO) {
        init(ctx)

        val wordsRegex = Regex("\\b[a-zA-Z]{4,}\\b")

        var reader: PdfReader? = null
        val androidDoc = PdfDocument()
        val pageMap = mutableMapOf<Int, Int>()

        val textPaint = TextPaint().apply {
            color = Color.DKGRAY
            textSize = 12f
            isAntiAlias = true
        }
        val boldPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 12f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val headerPaint = TextPaint().apply {
            color = Color.rgb(43, 108, 176)
            textSize = 16f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val noWordsPaint = TextPaint().apply {
            color = Color.GRAY
            textSize = 12f
            textSkewX = -0.25f // fake italic
            isAntiAlias = true
        }

        try {
            reader = PdfReader(inputPdf.absolutePath)
            val totalPages = reader.numberOfPages

            val range = if (pageRange != null) {
                (pageRange.first.coerceIn(1, totalPages))..(pageRange.last.coerceIn(1, totalPages))
            } else {
                1..totalPages
            }

            var vocabPageNum = 1

            for (i in range) {
                coroutineContext.ensureActive()
                onProgress(i - range.first + 1, range.count())
                pageMap[i] = vocabPageNum

                val pageText = PdfTextExtractor.getTextFromPage(reader, i)

                val tokens = wordsRegex.findAll(pageText).map { it.value.lowercase() }
                val validWords = mutableListOf<Pair<String, String>>()
                val seen = mutableSetOf<String>()

                for (word in tokens) {
                    if (seen.contains(word)) continue
                    if (commonWords.contains(word)) continue

                    // Look up definition with full variant and lemmatization support
                    val defn = lookupDefinition(word)
                    if (!defn.isNullOrBlank()) {
                        seen.add(word)
                        validWords.add(word to defn)
                    }
                }

                // Draw Page
                var pageInfo = PdfDocument.PageInfo.Builder(612, 792, vocabPageNum).create()
                var page = androidDoc.startPage(pageInfo)
                var canvas = page.canvas

                var yOffset = 50f
                val margin = 50f
                val textWidth = 612f - (margin * 2)

                // Draw header
                canvas.drawText("Page $i", margin, yOffset, headerPaint)
                yOffset += 30f

                if (validWords.isEmpty()) {
                    canvas.drawText("No uncommon words on this page.", margin, yOffset, noWordsPaint)
                    androidDoc.finishPage(page)
                    vocabPageNum++
                } else {
                    for ((word, defn) in validWords) {
                        // Definition (Wrapped)
                        val sl = StaticLayout.Builder.obtain(defn, 0, defn.length, textPaint, textWidth.toInt())
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setLineSpacing(0f, 1f)
                            .setIncludePad(false)
                            .build()

                        val entryHeight = 16f + sl.height + 15f

                        if (yOffset + entryHeight > 750f) {
                            // Finish current page
                            androidDoc.finishPage(page)
                            vocabPageNum++

                            // Start new page
                            pageInfo = PdfDocument.PageInfo.Builder(612, 792, vocabPageNum).create()
                            page = androidDoc.startPage(pageInfo)
                            canvas = page.canvas
                            yOffset = 50f

                            // Draw header for continued page
                            canvas.drawText("Page $i (continued)", margin, yOffset, headerPaint)
                            yOffset += 30f
                        }

                        // Word (Bold)
                        canvas.drawText(word, margin, yOffset, boldPaint)
                        yOffset += 16f

                        canvas.save()
                        canvas.translate(margin, yOffset)
                        sl.draw(canvas)
                        canvas.restore()

                        yOffset += sl.height + 15f
                    }
                    androidDoc.finishPage(page)
                    vocabPageNum++
                }
            }

            outputPdf.outputStream().use { os ->
                androidDoc.writeTo(os)
            }

            VocabResult(outputPdf, pageMap)
        } finally {
            androidDoc.close()
            reader?.close()
        }
    }
}
