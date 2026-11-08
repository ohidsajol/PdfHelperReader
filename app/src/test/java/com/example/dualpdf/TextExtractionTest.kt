package com.example.dualpdf

import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.ImageRenderInfo
import com.itextpdf.text.pdf.parser.PdfReaderContentParser
import com.itextpdf.text.pdf.parser.RenderListener
import com.itextpdf.text.pdf.parser.TextRenderInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID
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
    val colorHex: String,
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

object PdfTextHelper {

    fun extractWordsFromReader(reader: PdfReader, pageIndex1Based: Int): List<PdfWord> {
        val parser = PdfReaderContentParser(reader)
        val pageSize = reader.getPageSizeWithRotation(pageIndex1Based)
        val pageW = pageSize.width
        val pageH = pageSize.height
        val rotation = pageSize.rotation

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

                    val bStart = charBaseline.startPoint[0]
                    val bEnd = charBaseline.endPoint[0]
                    val cLeft = minOf(bStart, bEnd)
                    val cRight = maxOf(bStart, bEnd)

                    val cBottom = minOf(descent[1], ascent[1])
                    val cTop = maxOf(descent[1], ascent[1])

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

    fun findWordAt(words: List<PdfWord>, xNorm: Float, yNorm: Float): Int {
        if (words.isEmpty()) return -1
        // First check exact bounding boxes with a small touch slop
        val slopX = 0.015f
        val slopY = 0.012f
        for (i in words.indices) {
            val w = words[i]
            if (xNorm >= (w.left - slopX) && xNorm <= (w.right + slopX) &&
                yNorm >= (w.top - slopY) && yNorm <= (w.bottom + slopY)) {
                return i
            }
        }

        // If not directly inside, find the closest word on the closest line
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in words.indices) {
            val w = words[i]
            val centerX = (w.left + w.right) / 2f
            val centerY = (w.top + w.bottom) / 2f
            val dx = xNorm - centerX
            val dy = (yNorm - centerY) * 2.0f // Weight vertical distance more
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return if (bestDist < 0.05f) bestIdx else -1
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
}

class TextExtractionTest {

    @Test
    fun testMultiPageTextSelection() {
        // Selection spanning from page 1 (word 5) to page 3 (word 10)
        val sel = TextSelection(startPageIndex = 1, startWordIndex = 5, endPageIndex = 3, endWordIndex = 10)

        // Page 0 (outside selection)
        assertEquals(null, sel.wordRangeForPage(0, 20))

        // Page 1 (start page: from 5 to end of page)
        assertEquals(5..19, sel.wordRangeForPage(1, 20))

        // Page 2 (intermediate full page: 0 to end of page)
        assertEquals(0..29, sel.wordRangeForPage(2, 30))

        // Page 3 (end page: from 0 to 10)
        assertEquals(0..10, sel.wordRangeForPage(3, 25))

        // Page 4 (outside selection)
        assertEquals(null, sel.wordRangeForPage(4, 20))

        // Single page selection
        val singleSel = TextSelection(startPageIndex = 2, startWordIndex = 3, endPageIndex = 2, endWordIndex = 8)
        assertTrue(!sel.isSinglePage)
        assertTrue(singleSel.isSinglePage)
        assertEquals(null, singleSel.wordRangeForPage(1, 20))
        assertEquals(3..8, singleSel.wordRangeForPage(2, 20))
        assertEquals(null, singleSel.wordRangeForPage(3, 20))
    }

    @Test
    fun testBookPage70Words() {
        val f = File("/tmp/book.pdf")
        if (!f.exists()) return
        val reader = PdfReader(f.absolutePath)
        val words = PdfTextHelper.extractWordsFromReader(reader, 70)
        
        // Find "Supreme" and get selected text all the way to "star."
        val startIdx = words.indexOfFirst { it.text == "Supreme" }
        val endIdx = words.indexOfFirst { it.text == "star." }
        assertTrue("startIdx should be found", startIdx != -1)
        assertTrue("endIdx should be found", endIdx != -1)
        
        val selected = PdfTextHelper.getSelectedText(words, startIdx, endIdx)
        println("SELECTED TEXT: '$selected'")
        
        org.junit.Assert.assertFalse("Selected text should NOT contain null bytes", selected.contains('\u0000'))
        assertTrue("Selected text should contain 'sa'", selected.contains("sa"))
        assertTrue("Selected text should contain 'ron'", selected.contains("ron"))
        assertTrue("Selected text should contain 'robe,'", selected.contains("robe,"))
        assertTrue("Selected text should contain 'silver globe,'", selected.contains("silver globe,"))
        assertTrue("Selected text should contain 'star.'", selected.contains("star."))
        
        reader.close()
    }

    @Test
    fun testMultiPageHighlightGrouping() {
        val gId = UUID.randomUUID().toString()
        val h1 = Highlight(pageIndex = 2, text = "part 1 on page 2", colorHex = "#FFF176", rects = emptyList(), groupId = gId)
        val h2 = Highlight(pageIndex = 3, text = "part 2 on page 3", colorHex = "#FFF176", rects = emptyList(), groupId = gId)
        val h3 = Highlight(pageIndex = 4, text = "part 3 on page 4", colorHex = "#FFF176", rects = emptyList(), groupId = gId)
        val other = Highlight(pageIndex = 2, text = "unrelated", colorHex = "#FFF176", rects = emptyList(), groupId = UUID.randomUUID().toString())

        val list = listOf(h2, other, h1, h3)

        fun getRelated(h: Highlight): List<Highlight> {
            if (h.groupId != null) {
                val group = list.filter { it.groupId == h.groupId }.sortedBy { it.pageIndex }
                if (group.isNotEmpty()) return group
            }
            return listOf(h)
        }

        val relatedFromH2 = getRelated(h2)
        assertEquals(3, relatedFromH2.size)
        assertEquals(listOf(2, 3, 4), relatedFromH2.map { it.pageIndex })

        val fullText = relatedFromH2.joinToString("\n") { it.text }
        assertEquals("part 1 on page 2\npart 2 on page 3\npart 3 on page 4", fullText)

        val fullQuery = relatedFromH2.joinToString(" ") { it.text }
        assertEquals("part 1 on page 2 part 2 on page 3 part 3 on page 4", fullQuery)
    }

    @Test
    fun testHighlightsDialogGroupingAcrossPages() {
        // Multi-page highlight spanning pages 31 to 34 (indices 30 to 33)
        val multiPageGroupId = UUID.randomUUID().toString()
        val h31 = Highlight(pageIndex = 30, text = "First part on page 31", colorHex = "#FFF176", rects = emptyList(), groupId = multiPageGroupId)
        val h32 = Highlight(pageIndex = 31, text = "second part on page 32", colorHex = "#FFF176", rects = emptyList(), groupId = multiPageGroupId)
        val h33 = Highlight(pageIndex = 32, text = "third part on page 33", colorHex = "#FFF176", rects = emptyList(), groupId = multiPageGroupId)
        val h34 = Highlight(pageIndex = 33, text = "fourth part on page 34", colorHex = "#FFF176", rects = emptyList(), groupId = multiPageGroupId)

        // Single-page highlight on page 10 (index 9)
        val h10 = Highlight(pageIndex = 9, text = "Solo highlight on page 10", colorHex = "#A5D6A7", rects = emptyList(), groupId = UUID.randomUUID().toString())

        val highlightsList = listOf(h32, h10, h34, h31, h33)

        fun getRelated(h: Highlight): List<Highlight> {
            if (h.groupId != null) {
                val group = highlightsList.filter { it.groupId == h.groupId }.sortedBy { it.pageIndex }
                if (group.isNotEmpty()) return group
            }
            return listOf(h)
        }

        fun getGroupedHighlights(): List<HighlightGroup> {
            val processedIds = mutableSetOf<String>()
            val groups = mutableListOf<HighlightGroup>()
            val sorted = highlightsList.sortedWith(compareBy({ it.pageIndex }, { it.createdAt }))
            for (h in sorted) {
                if (h.id in processedIds) continue
                val related = getRelated(h)
                for (r in related) {
                    processedIds.add(r.id)
                }
                if (related.isNotEmpty()) {
                    groups.add(HighlightGroup(related.sortedBy { it.pageIndex }))
                }
            }
            return groups
        }

        val groups = getGroupedHighlights()
        // Must produce exactly 2 entries (one for page 10, one for pages 31-34) instead of 5 separate entries
        assertEquals(2, groups.size)

        val group1 = groups[0]
        assertEquals(9, group1.minPage)
        assertEquals(9, group1.maxPage)
        assertEquals("Page 10", group1.pageDisplay)
        assertEquals("Solo highlight on page 10", group1.text)

        val group2 = groups[1]
        assertEquals(30, group2.minPage)
        assertEquals(33, group2.maxPage)
        assertEquals("Pages 31–34", group2.pageDisplay)
        assertEquals("First part on page 31 second part on page 32 third part on page 33 fourth part on page 34", group2.text)
    }

    @Test
    fun testResolveContinuousPage() {
        val pageW = 1000f
        val pageRatio = 0.5f // Height = 1000 / 0.5 = 2000px
        val spacerPx = 50f
        val docCount = 5

        // Within start page (page 1)
        val r1 = PdfTextHelper.resolveContinuousPage(1, 1000f, pageW, docCount, spacerPx) { pageRatio }
        assertEquals(1, r1.first)
        assertEquals(0.5f, r1.second, 0.001f)

        // Drag down into next page (page 2)
        // 2000 (page 1) + 50 (spacer) + 500 (into page 2) = 2550
        val r2 = PdfTextHelper.resolveContinuousPage(1, 2550f, pageW, docCount, spacerPx) { pageRatio }
        assertEquals(2, r2.first)
        assertEquals(0.25f, r2.second, 0.001f)

        // Drag down across two pages into page 3
        // 2000 + 50 + 2000 + 50 + 1000 = 5100
        val r3 = PdfTextHelper.resolveContinuousPage(1, 5100f, pageW, docCount, spacerPx) { pageRatio }
        assertEquals(3, r3.first)
        assertEquals(0.5f, r3.second, 0.001f)

        // Drag up into previous page (page 0)
        // -50 (spacer) - 500 (from bottom of page 0) = -550 -> 1500px from top of page 0 (ratio 1500/2000 = 0.75f)
        val r4 = PdfTextHelper.resolveContinuousPage(1, -550f, pageW, docCount, spacerPx) { pageRatio }
        assertEquals(0, r4.first)
        assertEquals(0.75f, r4.second, 0.001f)
    }

    @Test
    fun testPinchToZoomMath() {
        val initialDistance = 200f
        var currentZoom = 1.0f
        val speedFactor = 0.085f

        // Damped Pinch out (spread fingers: 200 -> 300, raw factor 1.5)
        val spreadDistance = 300f
        val rawZoomFactor = spreadDistance / initialDistance // 1.5
        val dampedFactor = 1f + (rawZoomFactor - 1f) * speedFactor // 1 + 0.5 * 0.085 = 1.0425
        currentZoom = (currentZoom * dampedFactor).coerceIn(1.0f, 5.0f)
        assertEquals(1.0425f, currentZoom, 0.001f)

        // Damped Pinch in (fingers closer: 300 -> 240, raw factor 0.8)
        val pinchDistance = 240f
        val rawPinchFactor = pinchDistance / spreadDistance // 0.8
        val dampedPinchFactor = 1f + (rawPinchFactor - 1f) * speedFactor // 1 - 0.2 * 0.085 = 0.983
        currentZoom = (currentZoom * dampedPinchFactor).coerceIn(1.0f, 5.0f)
        assertEquals(1.02477f, currentZoom, 0.001f)

        // Pane center zooming math:
        // Screen width = 1000, height = 2000 -> Pane Center is at (500, 1000)
        val paneCenterX = 500f
        val paneCenterY = 1000f
        val scrollX = 0f
        val scrollY = 300f
        val zoomRatio = 1.2f

        val deltaX = -((scrollX + paneCenterX) * (zoomRatio - 1f))
        val deltaY = -((scrollY + paneCenterY) * (zoomRatio - 1f))
        assertEquals(-100f, deltaX, 0.001f)
        assertEquals(-260f, deltaY, 0.001f)

        // Verifying that after dispatchRawDelta(deltaX) the center of the pane remains exactly at paneCenterX:
        val newScrollX = scrollX - deltaX // 0 - (-100) = 100
        val originalContentX = scrollX + paneCenterX // 500
        val newContentX = originalContentX * zoomRatio // 500 * 1.2 = 600
        val newScreenX = newContentX - newScrollX // 600 - 100 = 500
        assertEquals(paneCenterX, newScreenX, 0.001f)
    }

    @Test
    fun testPageWordsAndSelection() {
        var bookFile = File("docs/online_books/Crime_and_Punishment.pdf")
        if (!bookFile.exists()) {
            bookFile = File("../docs/online_books/Crime_and_Punishment.pdf")
        }
        val reader = PdfReader(bookFile.absolutePath)
        val p11Words = PdfTextHelper.extractWordsFromReader(reader, 11)
        assertTrue(p11Words.isNotEmpty())
        println("Page 11 word count: ${p11Words.size}")
        
        // Print lines and words on Page 11
        val lines = p11Words.groupBy { it.lineIndex }
        for ((lIdx, lWords) in lines.entries.take(10)) {
            println("Line $lIdx (${lWords.first().top}..${lWords.first().bottom}): ${lWords.joinToString(" ") { it.text }}")
            println("   Last word: '${lWords.last().text}', right=${lWords.last().right}, index=${lWords.last().wordIndex}")
        }

        // Test findSelectionWordAt at the right edge of each line
        for ((lIdx, lWords) in lines.entries.take(10)) {
            val lastWord = lWords.last()
            val midY = (lastWord.top + lastWord.bottom) / 2f
            // Touch right at lastWord.right
            val foundIdx1 = PdfTextHelper.findSelectionWordAt(p11Words, lastWord.right, midY, isEndHandle = true)
            // Touch past lastWord.right (e.g. In margin)
            val foundIdx2 = PdfTextHelper.findSelectionWordAt(p11Words, 0.99f, midY, isEndHandle = true)
            // Touch below lastWord.bottom
            val foundIdx3 = PdfTextHelper.findSelectionWordAt(p11Words, lastWord.right, lastWord.bottom + 0.005f, isEndHandle = true)
            
            println("Line $lIdx '${lastWord.text}': expected=${lastWord.wordIndex}, atRightEdge=$foundIdx1, pastRightEdge=$foundIdx2, belowBottom=$foundIdx3")
        }
        reader.close()
    }

    @Test
    fun testClusterLineOrdering() {
        var bookFile = File("docs/online_books/Crime_and_Punishment.pdf")
        if (!bookFile.exists()) {
            bookFile = File("../docs/online_books/Crime_and_Punishment.pdf")
        }
        val reader = PdfReader(bookFile.absolutePath)
        for (p in 1..15) {
            val words = PdfTextHelper.extractWordsFromReader(reader, p)
            if (words.isEmpty()) continue
            val lines = words.groupBy { it.lineIndex }
            for ((lIdx, lWords) in lines) {
                // Check if any word on this line has overlapping or inverted X order
                for (i in 0 until lWords.size - 1) {
                    if (lWords[i].left > lWords[i + 1].right) {
                        println("Page $p Line $lIdx OUT OF ORDER: '${lWords[i].text}' (${lWords[i].left}) > '${lWords[i + 1].text}' (${lWords[i + 1].right})")
                    }
                }
            }
        }
        println("Line ordering check completed!")
        reader.close()
    }

    private fun Float.format(digits: Int): String = String.format("%.${digits}f", this)
}
