package com.example.dualpdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.ImageRenderInfo
import com.itextpdf.text.pdf.parser.PdfReaderContentParser
import com.itextpdf.text.pdf.parser.RenderListener
import com.itextpdf.text.pdf.parser.TextRenderInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs

data class PdfWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val lineIndex: Int = 0,
    val wordIndex: Int = 0
)

data class HighlightRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class Highlight(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int,
    val text: String,
    val colorHex: String = "#FFF176", // Default Soft Yellow
    val rects: List<HighlightRect>,
    val createdAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val groupId: String? = null
)

data class HighlightGroup(
    val segments: List<Highlight>
) {
    val id: String get() = segments.firstOrNull()?.id ?: ""
    val groupId: String? get() = segments.firstOrNull()?.groupId
    val minPage: Int get() = segments.minOfOrNull { it.pageIndex } ?: 0
    val maxPage: Int get() = segments.maxOfOrNull { it.pageIndex } ?: 0
    val pageDisplay: String
        get() = if (minPage == maxPage) "Page ${minPage + 1}" else "Pages ${minPage + 1}–${maxPage + 1}"
    val createdAt: Long get() = segments.firstOrNull()?.createdAt ?: 0L
    val colorHex: String get() = segments.firstOrNull()?.colorHex ?: "#FFF176"
    val text: String get() = segments.sortedBy { it.pageIndex }.joinToString(" ") { it.text.trim() }
    val primaryHighlight: Highlight get() = segments.first()
}

data class TextSelection(
    val startPageIndex: Int,
    val startWordIndex: Int,
    val endPageIndex: Int,
    val endWordIndex: Int
) {
    constructor(pageIndex: Int, startWordIndex: Int, endWordIndex: Int) : this(
        startPageIndex = pageIndex,
        startWordIndex = startWordIndex,
        endPageIndex = pageIndex,
        endWordIndex = endWordIndex
    )

    val pageIndex: Int get() = startPageIndex
    val isSinglePage: Boolean get() = startPageIndex == endPageIndex
    val minPage: Int get() = minOf(startPageIndex, endPageIndex)
    val maxPage: Int get() = maxOf(startPageIndex, endPageIndex)

    fun wordRangeForPage(page: Int, pageWordCount: Int): IntRange? {
        if (pageWordCount <= 0) return null
        if (page < minPage || page > maxPage) return null

        return if (startPageIndex == endPageIndex) {
            val minW = minOf(startWordIndex, endWordIndex).coerceIn(0, pageWordCount - 1)
            val maxW = maxOf(startWordIndex, endWordIndex).coerceIn(0, pageWordCount - 1)
            minW..maxW
        } else if (startPageIndex < endPageIndex) {
            when (page) {
                startPageIndex -> startWordIndex.coerceIn(0, pageWordCount - 1)..(pageWordCount - 1)
                endPageIndex -> 0..endWordIndex.coerceIn(0, pageWordCount - 1)
                else -> 0..(pageWordCount - 1)
            }
        } else {
            when (page) {
                endPageIndex -> endWordIndex.coerceIn(0, pageWordCount - 1)..(pageWordCount - 1)
                startPageIndex -> 0..startWordIndex.coerceIn(0, pageWordCount - 1)
                else -> 0..(pageWordCount - 1)
            }
        }
    }
}

val HighlightColors = listOf(
    "#FFF176" to "Yellow",
    "#A5D6A7" to "Green",
    "#81D4FA" to "Blue",
    "#FFB74D" to "Orange"
)

fun parseHexColor(hex: String, defaultColor: Color = Color(0xFFFFF176)): Color {
    return try {
        val clean = hex.removePrefix("#")
        val colorInt = when (clean.length) {
            6 -> (0xFF000000 or clean.toLong(16)).toInt()
            8 -> clean.toLong(16).toInt()
            else -> return defaultColor
        }
        Color(colorInt)
    } catch (_: Exception) {
        defaultColor
    }
}

object PdfTextHelper {

    private val ocrClient by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun extractWordsFromPage(
        ctx: Context,
        uri: Uri?,
        doc: PdfDoc,
        pageIndex: Int,
        fallbackBitmap: Bitmap? = null
    ): List<PdfWord> = withContext(Dispatchers.IO) {
        // 1. Check cache in doc
        val cached = doc.getWords(pageIndex)
        if (cached != null) return@withContext cached

        // 2. Try fast native extraction via iText
        try {
            val itext = doc.getOrInitTextReader(ctx, uri)
            if (itext != null && pageIndex + 1 <= itext.numberOfPages) {
                val words = doc.synchronizedExtractWords(pageIndex + 1)
                if (words.isNotEmpty()) {
                    doc.putWords(pageIndex, words)
                    return@withContext words
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Fallback to ML Kit OCR for scanned pages
        if (fallbackBitmap != null) {
            val ocrWords = extractWordsFromBitmapOcr(fallbackBitmap)
            if (ocrWords.isNotEmpty()) {
                doc.putWords(pageIndex, ocrWords)
                return@withContext ocrWords
            }
        }

        emptyList()
    }

    fun extractWordsFromReader(reader: PdfReader, pageIndex1Based: Int): List<PdfWord> = synchronized(reader) {
        val parser = PdfReaderContentParser(reader)
        val pageSize = reader.getPageSizeWithRotation(pageIndex1Based)
        val pageW = pageSize.width
        val pageH = pageSize.height

        val words = mutableListOf<PdfWord>()
        var currentLineIndex = 0
        var lastBaselineY = Float.NaN

        parser.processContent(pageIndex1Based, object : RenderListener {
            override fun beginTextBlock() {}
            override fun endTextBlock() {}
            override fun renderImage(renderInfo: ImageRenderInfo?) {}

            override fun renderText(renderInfo: TextRenderInfo?) {
                if (renderInfo == null) return

                val baseline = renderInfo.baseline.startPoint
                val baselineY = baseline[1]
                if (!lastBaselineY.isNaN() && abs(baselineY - lastBaselineY) > 4.0f) {
                    currentLineIndex++
                }
                lastBaselineY = baselineY

                val chars = renderInfo.characterRenderInfos
                if (chars.isEmpty()) {
                    val text = renderInfo.text.filter { it >= ' ' || it == '\n' || it == '\t' }.trim()
                    if (text.isNotBlank()) {
                        val descent = renderInfo.descentLine.startPoint
                        val ascent = renderInfo.ascentLine.endPoint
                        val cLeft = minOf(descent[0], ascent[0])
                        val cRight = maxOf(descent[0], ascent[0])
                        val cBottom = minOf(descent[1], ascent[1])
                        val cTop = maxOf(descent[1], ascent[1])

                        words.add(
                            PdfWord(
                                text = text,
                                left = (cLeft / pageW).coerceIn(0f, 1f),
                                top = ((pageH - cTop) / pageH).coerceIn(0f, 1f),
                                right = (cRight / pageW).coerceIn(0f, 1f),
                                bottom = ((pageH - cBottom) / pageH).coerceIn(0f, 1f),
                                lineIndex = currentLineIndex,
                                wordIndex = words.size
                            )
                        )
                    }
                    return
                }

                var currentWord = StringBuilder()
                var wLeft = Float.MAX_VALUE
                var wRight = Float.MIN_VALUE
                var wTop = Float.MAX_VALUE
                var wBottom = Float.MIN_VALUE

                fun commitWord() {
                    val cleanText = currentWord.filter { it >= ' ' || it == '\n' || it == '\t' }.trim()
                    if (cleanText.isNotBlank() && wLeft < wRight && wTop < wBottom) {
                        words.add(
                            PdfWord(
                                text = cleanText.toString(),
                                left = (wLeft / pageW).coerceIn(0f, 1f),
                                top = (wTop / pageH).coerceIn(0f, 1f),
                                right = (wRight / pageW).coerceIn(0f, 1f),
                                bottom = (wBottom / pageH).coerceIn(0f, 1f),
                                lineIndex = currentLineIndex,
                                wordIndex = words.size
                            )
                        )
                    }
                    currentWord.clear()
                    wLeft = Float.MAX_VALUE
                    wRight = Float.MIN_VALUE
                    wTop = Float.MAX_VALUE
                    wBottom = Float.MIN_VALUE
                }

                for (charInfo in chars) {
                    val rawCh = charInfo.text
                    val cleanCh = rawCh.filter { it >= ' ' || it == '\n' || it == '\t' }
                    if (cleanCh.isBlank()) {
                        commitWord()
                        continue
                    }
                    val descent = charInfo.descentLine.startPoint
                    val ascent = charInfo.ascentLine.endPoint

                    val cLeft = minOf(descent[0], ascent[0])
                    val cRight = maxOf(descent[0], ascent[0])
                    val cBottom = minOf(descent[1], ascent[1])
                    val cTop = maxOf(descent[1], ascent[1])

                    val screenTop = pageH - cTop
                    val screenBottom = pageH - cBottom

                    wLeft = minOf(wLeft, cLeft)
                    wRight = maxOf(wRight, cRight)
                    wTop = minOf(wTop, screenTop)
                    wBottom = maxOf(wBottom, screenBottom)
                    currentWord.append(cleanCh)
                }
                commitWord()
            }
        })

        return clusterAndOrderWords(words)
    }

    fun clusterAndOrderWords(rawWords: List<PdfWord>): List<PdfWord> {
        if (rawWords.isEmpty()) return emptyList()

        val sortedByTop = rawWords.sortedWith(compareBy({ (it.top + it.bottom) / 2f }, { it.left }))
        val lineGroups = mutableListOf<MutableList<PdfWord>>()

        for (w in sortedByTop) {
            val wHeight = (w.bottom - w.top).coerceAtLeast(0.005f)
            val wMidY = (w.top + w.bottom) / 2f

            var bestLine: MutableList<PdfWord>? = null
            var bestDist = Float.MAX_VALUE

            for (line in lineGroups) {
                val lineMidY = line.map { (it.top + it.bottom) / 2f }.average().toFloat()
                val lineHeight = line.map { it.bottom - it.top }.average().toFloat().coerceAtLeast(0.005f)
                val dist = abs(wMidY - lineMidY)

                if (dist <= maxOf(wHeight, lineHeight) * 0.45f && dist < bestDist) {
                    bestDist = dist
                    bestLine = line
                }
            }

            if (bestLine != null) {
                bestLine.add(w)
            } else {
                lineGroups.add(mutableListOf(w))
            }
        }

        val sortedLines = lineGroups.map { line -> line.sortedBy { it.left } }
        val orderedLines = sortedLines.sortedBy { line -> line.map { (it.top + it.bottom) / 2f }.average() }

        val orderedWords = mutableListOf<PdfWord>()
        orderedLines.forEachIndexed { lineIndex, lineWords ->
            lineWords.forEach { w ->
                orderedWords.add(
                    w.copy(
                        lineIndex = lineIndex,
                        wordIndex = orderedWords.size
                    )
                )
            }
        }
        return orderedWords
    }

    private suspend fun extractWordsFromBitmapOcr(bitmap: Bitmap): List<PdfWord> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)
            val bmpW = bitmap.width.toFloat()
            val bmpH = bitmap.height.toFloat()

            ocrClient.process(image)
                .addOnSuccessListener { visionText ->
                    val list = mutableListOf<PdfWord>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            for (element in line.elements) {
                                element.boundingBox?.let { rect ->
                                    val text = element.text.filter { it >= ' ' || it == '\n' || it == '\t' }.trim()
                                    if (text.isNotBlank()) {
                                        list.add(
                                            PdfWord(
                                                text = text,
                                                left = (rect.left.toFloat() / bmpW).coerceIn(0f, 1f),
                                                top = (rect.top.toFloat() / bmpH).coerceIn(0f, 1f),
                                                right = (rect.right.toFloat() / bmpW).coerceIn(0f, 1f),
                                                bottom = (rect.bottom.toFloat() / bmpH).coerceIn(0f, 1f)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    continuation.resume(clusterAndOrderWords(list))
                }
                .addOnFailureListener {
                    continuation.resume(emptyList())
                }
        }

    fun findWordAt(words: List<PdfWord>, xNorm: Float, yNorm: Float): Int {
        if (words.isEmpty()) return -1
        val slopX = 0.02f
        val slopY = 0.015f
        for (i in words.indices) {
            val w = words[i]
            if (xNorm >= (w.left - slopX) && xNorm <= (w.right + slopX) &&
                yNorm >= (w.top - slopY) && yNorm <= (w.bottom + slopY)) {
                return i
            }
        }

        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in words.indices) {
            val w = words[i]
            val centerX = (w.left + w.right) / 2f
            val centerY = (w.top + w.bottom) / 2f
            val dx = xNorm - centerX
            val dy = (yNorm - centerY) * 2.5f
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return if (bestDist < 0.06f) bestIdx else -1
    }

    fun findSelectionWordAt(words: List<PdfWord>, xNorm: Float, yNorm: Float, isEndHandle: Boolean = false): Int {
        if (words.isEmpty()) return -1

        val hasLineIndices = words.any { it.lineIndex != 0 }
        val lines: List<List<PdfWord>> = if (hasLineIndices) {
            words.groupBy { it.lineIndex }.values.toList()
        } else {
            clusterAndOrderWords(words).groupBy { it.lineIndex }.values.toList()
        }

        if (lines.isEmpty()) return -1

        // If dragging end handle and finger is below all lines on this page, select the last word of the page!
        val lastLine = lines.last()
        val lastLineBottom = lastLine.maxOf { it.bottom }
        if (isEndHandle && yNorm >= lastLineBottom) {
            return words.size - 1
        }

        // If dragging start handle and finger is above all lines on this page, select the first word of the page!
        val firstLine = lines.first()
        val firstLineTop = firstLine.minOf { it.top }
        if (!isEndHandle && yNorm <= firstLineTop) {
            return 0
        }

        var bestLine: List<PdfWord>? = null
        var minVerticalDist = Float.MAX_VALUE

        for (line in lines) {
            val lineTop = line.minOf { it.top }
            val lineBottom = line.maxOf { it.bottom }

            val vDist = when {
                yNorm < lineTop -> (lineTop - yNorm) * 2.0f
                yNorm > lineBottom -> (yNorm - lineBottom) * 2.0f
                else -> abs(yNorm - (lineTop + lineBottom) / 2f) * 0.1f
            }

            if (vDist < minVerticalDist) {
                minVerticalDist = vDist
                bestLine = line
            }
        }

        val targetLine = bestLine ?: return -1
        val sortedLine = targetLine.sortedBy { it.left }
        val firstWord = sortedLine.first()
        val lastWord = sortedLine.last()

        fun wordIndexInWords(target: PdfWord): Int {
            if (target.wordIndex in words.indices && words[target.wordIndex] == target) return target.wordIndex
            val idx = words.indexOf(target)
            if (idx != -1) return idx
            val match = words.indexOfFirst { it.left == target.left && it.top == target.top && it.text == target.text }
            return if (match != -1) match else target.wordIndex.coerceIn(0, words.size - 1)
        }

        if (xNorm <= firstWord.left) {
            return wordIndexInWords(firstWord)
        }
        if (xNorm >= lastWord.right) {
            var lastIdx = wordIndexInWords(lastWord)
            while (lastIdx + 1 < words.size) {
                val nextW = words[lastIdx + 1]
                if (nextW.lineIndex == lastWord.lineIndex && nextW.text.all { !it.isLetterOrDigit() }) {
                    if (abs(nextW.left - words[lastIdx].right) < 0.05f) {
                        lastIdx++
                    } else break
                } else break
            }
            return lastIdx
        }

        if (isEndHandle) {
            // For END handle: if xNorm reaches into word (it.left - slop), include that word!
            val slop = 0.015f
            val hitWord = sortedLine.lastOrNull { xNorm >= (it.left - slop) } ?: firstWord
            val chosenIdx = wordIndexInWords(hitWord)
            var lastIdx = chosenIdx
            while (lastIdx + 1 < words.size) {
                val nextW = words[lastIdx + 1]
                if (nextW.lineIndex == hitWord.lineIndex && nextW.text.all { !it.isLetterOrDigit() }) {
                    if (abs(nextW.left - words[lastIdx].right) < 0.05f) {
                        lastIdx++
                    } else break
                } else break
            }
            return lastIdx
        } else {
            // For START handle: if xNorm is to the left of it.right + slop, include that word!
            val slop = 0.015f
            val hitWord = sortedLine.firstOrNull { xNorm <= (it.right + slop) } ?: lastWord
            return wordIndexInWords(hitWord)
        }
    }

    fun findHighlightAt(highlights: List<Highlight>, xNorm: Float, yNorm: Float): Highlight? {
        val slop = 0.01f
        return highlights.firstOrNull { h ->
            h.rects.any { r ->
                xNorm >= (r.left - slop) && xNorm <= (r.right + slop) &&
                yNorm >= (r.top - slop) && yNorm <= (r.bottom + slop)
            }
        }
    }

    fun getSelectionRects(words: List<PdfWord>, startIndex: Int, endIndex: Int): List<HighlightRect> {
        if (words.isEmpty() || startIndex < 0 || endIndex < 0) return emptyList()
        val minIdx = minOf(startIndex, endIndex).coerceIn(0, words.size - 1)
        val maxIdx = maxOf(startIndex, endIndex).coerceIn(0, words.size - 1)

        val selectedWords = words.subList(minIdx, maxIdx + 1)
        if (selectedWords.isEmpty()) return emptyList()

        val lineGroups = if (selectedWords.any { it.lineIndex != 0 }) {
            selectedWords.groupBy { it.lineIndex }.values
        } else {
            clusterAndOrderWords(selectedWords).groupBy { it.lineIndex }.values
        }

        return lineGroups.map { lineWords ->
            val l = lineWords.minOf { it.left }
            val t = lineWords.minOf { it.top }
            val r = lineWords.maxOf { it.right }
            val b = lineWords.maxOf { it.bottom }
            HighlightRect(l, t, r, b)
        }
    }

    fun getSelectedText(words: List<PdfWord>, startIndex: Int, endIndex: Int): String {
        if (words.isEmpty() || startIndex < 0 || endIndex < 0) return ""
        val minIdx = minOf(startIndex, endIndex).coerceIn(0, words.size - 1)
        val maxIdx = maxOf(startIndex, endIndex).coerceIn(0, words.size - 1)
        return words.subList(minIdx, maxIdx + 1)
            .map { it.text.filter { c -> c >= ' ' || c == '\n' || c == '\t' } }
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    fun resolveContinuousPage(
        fromPage: Int,
        yOffset: Float,
        displayedWPx: Float,
        docPageCount: Int,
        spacerPx: Float,
        getPageRatio: (Int) -> Float
    ): Pair<Int, Float> {
        val fromRatio = getPageRatio(fromPage)
        val fromHPx = displayedWPx / fromRatio

        if (yOffset in 0f..fromHPx) {
            return Pair(fromPage, (yOffset / fromHPx).coerceIn(0f, 1f))
        }

        if (yOffset > fromHPx) {
            var remaining = yOffset - fromHPx
            var curr = fromPage
            while (curr < docPageCount - 1) {
                if (remaining <= spacerPx) {
                    return Pair(curr, 1f)
                }
                remaining -= spacerPx
                curr++
                val nextRatio = getPageRatio(curr)
                val nextHPx = displayedWPx / nextRatio
                if (remaining <= nextHPx || curr == docPageCount - 1) {
                    val yNorm = (remaining / nextHPx).coerceIn(0f, 1f)
                    return Pair(curr, yNorm)
                }
                remaining -= nextHPx
            }
            return Pair(curr, 1f)
        } else {
            var remaining = -yOffset
            var curr = fromPage
            while (curr > 0) {
                if (remaining <= spacerPx) {
                    return Pair(curr, 0f)
                }
                remaining -= spacerPx
                curr--
                val prevRatio = getPageRatio(curr)
                val prevHPx = displayedWPx / prevRatio
                if (remaining <= prevHPx || curr == 0) {
                    val yNorm = ((prevHPx - remaining) / prevHPx).coerceIn(0f, 1f)
                    return Pair(curr, yNorm)
                }
                remaining -= prevHPx
            }
            return Pair(curr, 0f)
        }
    }
}
