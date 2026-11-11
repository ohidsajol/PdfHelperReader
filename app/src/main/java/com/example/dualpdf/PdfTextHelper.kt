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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

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

data class SelectionPoint(
    val wordIndex: Int,
    val charOffset: Int
)

data class TextSelection(
    val startPageIndex: Int,
    val startWordIndex: Int,
    val startCharOffset: Int = 0,
    val endPageIndex: Int,
    val endWordIndex: Int,
    val endCharOffset: Int = -1
) {
    constructor(
        startPageIndex: Int,
        startWordIndex: Int,
        endPageIndex: Int,
        endWordIndex: Int
    ) : this(
        startPageIndex = startPageIndex,
        startWordIndex = startWordIndex,
        startCharOffset = 0,
        endPageIndex = endPageIndex,
        endWordIndex = endWordIndex,
        endCharOffset = -1
    )

    constructor(pageIndex: Int, startWordIndex: Int, endWordIndex: Int) : this(
        startPageIndex = pageIndex,
        startWordIndex = startWordIndex,
        startCharOffset = 0,
        endPageIndex = pageIndex,
        endWordIndex = endWordIndex,
        endCharOffset = -1
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

    fun charOffsetsForPage(page: Int, pRange: IntRange): Pair<Int, Int> {
        val sChar = if (page == startPageIndex) startCharOffset else 0
        val eChar = if (page == endPageIndex) endCharOffset else -1
        return Pair(sChar, eChar)
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

/**
 * Rethrows coroutine cancellation and non-linkage JVM errors so recoverable exceptions
 * and missing-class linkage errors can be handled without terminating the app.
 */
internal fun Throwable.rethrowIfFatal() {
    if (this is CancellationException || (this is Error && this !is LinkageError)) {
        throw this
    }
}

/**
 * Adapts any caught [Throwable] into an [Exception] for callback signatures that accept [Exception].
 */
internal fun Throwable.toRecoverableException(): Exception =
    (this as? Exception) ?: RuntimeException(message ?: javaClass.simpleName, this)

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
        } catch (t: Throwable) {
            t.rethrowIfFatal()
            t.printStackTrace()
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
        var lastCharEndPoint = Float.NaN

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

        parser.processContent(pageIndex1Based, object : RenderListener {
            override fun beginTextBlock() {}
            override fun endTextBlock() {
                commitWord()
                lastCharEndPoint = Float.NaN
            }
            override fun renderImage(renderInfo: ImageRenderInfo?) {}

            override fun renderText(renderInfo: TextRenderInfo?) {
                if (renderInfo == null) return

                val baselineStart = renderInfo.baseline.startPoint
                val baselineY = baselineStart[1]
                if (!lastBaselineY.isNaN() && abs(baselineY - lastBaselineY) > 4.0f) {
                    commitWord()
                    currentLineIndex++
                    lastCharEndPoint = Float.NaN
                }
                lastBaselineY = baselineY

                val chars = renderInfo.characterRenderInfos
                if (chars.isEmpty()) {
                    val text = renderInfo.text.filter { it >= ' ' || it == '\n' || it == '\t' }.trim()
                    if (text.isNotBlank()) {
                        commitWord()
                        val descent = renderInfo.descentLine.startPoint
                        val ascent = renderInfo.ascentLine.endPoint
                        val renderBaseline = renderInfo.baseline
                        val bStart = renderBaseline.startPoint[0]
                        val bEnd = renderBaseline.endPoint[0]
                        val cLeft = minOf(bStart, bEnd)
                        val cRight = maxOf(bStart, bEnd)
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
                        lastCharEndPoint = cRight
                    }
                    return
                }

                for (charInfo in chars) {
                    val rawCh = charInfo.text
                    val cleanCh = rawCh.filter { it >= ' ' || it == '\n' || it == '\t' }
                    if (cleanCh.isBlank()) {
                        commitWord()
                        lastCharEndPoint = Float.NaN
                        continue
                    }
                    val descent = charInfo.descentLine.startPoint
                    val ascent = charInfo.ascentLine.endPoint
                    val charBaseline = charInfo.baseline

                    // Use baseline for horizontal coordinates to prevent text-skew / fake-italic distortion
                    val bStart = charBaseline.startPoint[0]
                    val bEnd = charBaseline.endPoint[0]
                    val cLeft = minOf(bStart, bEnd)
                    val cRight = maxOf(bStart, bEnd)

                    val cBottom = minOf(descent[1], ascent[1])
                    val cTop = maxOf(descent[1], ascent[1])

                    // If gap from previous character on the same line exceeds space threshold (> 2.0 points),
                    // treat it as a word boundary
                    if (!lastCharEndPoint.isNaN() && (cLeft - lastCharEndPoint) > 2.0f) {
                        commitWord()
                    }

                    val screenTop = pageH - cTop
                    val screenBottom = pageH - cBottom

                    wLeft = minOf(wLeft, cLeft)
                    wRight = maxOf(wRight, cRight)
                    wTop = minOf(wTop, screenTop)
                    wBottom = maxOf(wBottom, screenBottom)
                    currentWord.append(cleanCh)
                    lastCharEndPoint = cRight
                }
            }
        })
        commitWord()

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
            val mergedLineWords = mutableListOf<PdfWord>()
            for (w in lineWords) {
                if (mergedLineWords.isEmpty()) {
                    mergedLineWords.add(w)
                } else {
                    val prev = mergedLineWords.last()
                    val gap = w.left - prev.right
                    val shouldMerge = gap <= 0.0025f && (
                        (prev.text.any { it.isLetterOrDigit() } && w.text.any { it.isLetterOrDigit() }) ||
                        (prev.text.all { !it.isLetterOrDigit() } && w.text.all { !it.isLetterOrDigit() })
                    )
                    if (shouldMerge) {
                        mergedLineWords[mergedLineWords.size - 1] = prev.copy(
                            text = prev.text + w.text,
                            left = minOf(prev.left, w.left),
                            top = minOf(prev.top, w.top),
                            right = maxOf(prev.right, w.right),
                            bottom = maxOf(prev.bottom, w.bottom)
                        )
                    } else {
                        mergedLineWords.add(w)
                    }
                }
            }
            mergedLineWords.forEach { w ->
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

    fun compareSelectionPos(
        page1: Int, word1: Int, char1: Int,
        page2: Int, word2: Int, char2: Int
    ): Int {
        if (page1 != page2) return page1.compareTo(page2)
        if (word1 != word2) return word1.compareTo(word2)
        return char1.compareTo(char2)
    }

    fun findSelectionPointAt(
        words: List<PdfWord>,
        xNorm: Float,
        yNorm: Float,
        isEndHandle: Boolean = false
    ): SelectionPoint {
        if (words.isEmpty()) return SelectionPoint(0, 0)

        val hasLineIndices = words.any { it.lineIndex != 0 }
        val lines: List<List<PdfWord>> = if (hasLineIndices) {
            words.groupBy { it.lineIndex }.values.toList()
        } else {
            clusterAndOrderWords(words).groupBy { it.lineIndex }.values.toList()
        }

        if (lines.isEmpty()) return SelectionPoint(0, 0)

        fun wordIndexInWords(target: PdfWord): Int {
            if (target.wordIndex in words.indices && words[target.wordIndex] == target) return target.wordIndex
            val idx = words.indexOf(target)
            if (idx != -1) return idx
            val match = words.indexOfFirst { it.left == target.left && it.top == target.top && it.text == target.text }
            return if (match != -1) match else target.wordIndex.coerceIn(0, words.size - 1)
        }

        val lastLine = lines.last()
        val lastLineBottom = lastLine.maxOf { it.bottom }
        if (isEndHandle && yNorm >= lastLineBottom) {
            val lastWord = lastLine.sortedBy { it.left }.last()
            return SelectionPoint(wordIndexInWords(lastWord), lastWord.text.length)
        }

        val firstLine = lines.first()
        val firstLineTop = firstLine.minOf { it.top }
        if (!isEndHandle && yNorm <= firstLineTop) {
            val firstWord = firstLine.sortedBy { it.left }.first()
            return SelectionPoint(wordIndexInWords(firstWord), 0)
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

        val targetLine = bestLine ?: return SelectionPoint(0, 0)
        val sortedLine = targetLine.sortedBy { it.left }
        val firstWord = sortedLine.first()
        val lastWord = sortedLine.last()

        if (xNorm <= firstWord.left) {
            return SelectionPoint(wordIndexInWords(firstWord), 0)
        }
        if (xNorm >= lastWord.right) {
            return SelectionPoint(wordIndexInWords(lastWord), lastWord.text.length)
        }

        for (i in sortedLine.indices) {
            val w = sortedLine[i]
            if (xNorm >= w.left && xNorm <= w.right) {
                val len = w.text.length
                val offset = if (len <= 1) {
                    if (isEndHandle) (if (xNorm >= (w.left + w.right) / 2f) len else 0)
                    else (if (xNorm <= (w.left + w.right) / 2f) 0 else len)
                } else {
                    val frac = ((xNorm - w.left) / (w.right - w.left)).coerceIn(0f, 1f)
                    (frac * len).roundToInt().coerceIn(0, len)
                }
                return SelectionPoint(wordIndexInWords(w), offset)
            }

            if (i + 1 < sortedLine.size) {
                val nextW = sortedLine[i + 1]
                if (xNorm > w.right && xNorm < nextW.left) {
                    val distW = xNorm - w.right
                    val distNext = nextW.left - xNorm
                    return if (distW <= distNext) {
                        SelectionPoint(wordIndexInWords(w), w.text.length)
                    } else {
                        SelectionPoint(wordIndexInWords(nextW), 0)
                    }
                }
            }
        }

        return SelectionPoint(wordIndexInWords(lastWord), lastWord.text.length)
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

    fun getSelectionRects(
        words: List<PdfWord>,
        startIndex: Int,
        endIndex: Int,
        startCharOffset: Int = 0,
        endCharOffset: Int = -1
    ): List<HighlightRect> {
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
            val sortedLine = lineWords.sortedBy { it.left }
            var l = sortedLine.minOf { it.left }
            val t = sortedLine.minOf { it.top }
            var r = sortedLine.maxOf { it.right }
            val b = sortedLine.maxOf { it.bottom }

            val firstWordInLine = sortedLine.first()
            val lastWordInLine = sortedLine.last()

            if (firstWordInLine.wordIndex == minIdx && startCharOffset > 0) {
                val sLen = maxOf(1, firstWordInLine.text.length)
                val sFrac = startCharOffset.coerceIn(0, sLen).toFloat() / sLen
                l = firstWordInLine.left + (firstWordInLine.right - firstWordInLine.left) * sFrac
            }

            if (lastWordInLine.wordIndex == maxIdx && endCharOffset >= 0) {
                val eLen = maxOf(1, lastWordInLine.text.length)
                val eFrac = endCharOffset.coerceIn(0, eLen).toFloat() / eLen
                r = lastWordInLine.left + (lastWordInLine.right - lastWordInLine.left) * eFrac
            }

            HighlightRect(minOf(l, r), t, maxOf(l, r), b)
        }
    }

    fun getSelectedText(
        words: List<PdfWord>,
        startIndex: Int,
        endIndex: Int,
        startCharOffset: Int = 0,
        endCharOffset: Int = -1
    ): String {
        if (words.isEmpty() || startIndex < 0 || endIndex < 0) return ""
        val minIdx = minOf(startIndex, endIndex).coerceIn(0, words.size - 1)
        val maxIdx = maxOf(startIndex, endIndex).coerceIn(0, words.size - 1)

        if (minIdx == maxIdx) {
            val w = words[minIdx]
            val clean = w.text.filter { it >= ' ' || it == '\n' || it == '\t' }
            val s = startCharOffset.coerceIn(0, clean.length)
            val e = (if (endCharOffset in 0..clean.length) endCharOffset else clean.length).coerceIn(s, clean.length)
            return clean.substring(s, e)
        }

        val firstWord = words[minIdx]
        val firstClean = firstWord.text.filter { it >= ' ' || it == '\n' || it == '\t' }
        val s = startCharOffset.coerceIn(0, firstClean.length)
        val firstPart = firstClean.substring(s)

        val lastWord = words[maxIdx]
        val lastClean = lastWord.text.filter { it >= ' ' || it == '\n' || it == '\t' }
        val e = (if (endCharOffset in 0..lastClean.length) endCharOffset else lastClean.length).coerceIn(0, lastClean.length)
        val lastPart = lastClean.substring(0, e)

        val middleWords = if (maxIdx > minIdx + 1) {
            words.subList(minIdx + 1, maxIdx)
                .map { it.text.filter { c -> c >= ' ' || c == '\n' || c == '\t' } }
                .filter { it.isNotBlank() }
        } else {
            emptyList()
        }

        return (listOf(firstPart) + middleWords + listOf(lastPart))
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
