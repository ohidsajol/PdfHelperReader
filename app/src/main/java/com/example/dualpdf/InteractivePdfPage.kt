package com.example.dualpdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun InteractivePdfPage(
    doc: PdfDoc,
    pageIndex: Int,
    bitmap: ImageBitmap?,
    displayedWidth: Dp,
    displayedHeight: Dp,
    colorFilter: ColorFilter?,
    pane: Pane,
    reader: Reader,
    isZenMode: Boolean = false,
    onToggleZenControls: () -> Unit = {},
    onTripleTap: () -> Unit = {},
    onDoubleTapPage: ((Offset) -> Unit)? = null,
    onRequestScroll: ((Float) -> Unit)? = null
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current

    var words by remember(doc, pageIndex) { mutableStateOf(doc.getWords(pageIndex) ?: emptyList()) }
    val activeSelection = pane.activeSelection

    var isDraggingStart by remember(pageIndex) { mutableStateOf(false) }
    var isDraggingEnd by remember(pageIndex) { mutableStateOf(false) }
    var isLongPressDragging by remember(pageIndex) { mutableStateOf(false) }

    var dragStartCumulative by remember(pageIndex) { mutableStateOf(Offset.Zero) }
    var dragEndCumulative by remember(pageIndex) { mutableStateOf(Offset.Zero) }
    var longPressCumulative by remember(pageIndex) { mutableStateOf(Offset.Zero) }

    val pageHighlights = reader.highlights.filter { it.pageIndex == pageIndex }

    val displayedWPx = with(density) { displayedWidth.toPx() }
    val displayedHPx = with(density) { displayedHeight.toPx() }

    val currentWordsList = if (words.isEmpty()) (doc.getWords(pageIndex) ?: emptyList()) else words
    val currentWPx by rememberUpdatedState(displayedWPx)

    val handleTouchSize = 56.dp
    val halfTouchPx = with(density) { handleTouchSize.toPx() / 2f }

    val curSel = activeSelection
    val isStartPage = curSel != null && curSel.startPageIndex == pageIndex
    val isEndPage = curSel != null && curSel.endPageIndex == pageIndex

    val (calcStartHandleX, calcStartHandleY) = if (isStartPage && currentWordsList.isNotEmpty()) {
        val sWordIdx = curSel!!.startWordIndex.coerceIn(0, currentWordsList.size - 1)
        val sw = currentWordsList[sWordIdx]
        val sLen = maxOf(1, sw.text.length)
        val sOffset = curSel.startCharOffset.coerceIn(0, sLen)
        val sFrac = sOffset.toFloat() / sLen
        val sX = sw.left + (sw.right - sw.left) * sFrac
        Pair(sX * displayedWPx, sw.top * displayedHPx)
    } else {
        Pair(0f, 0f)
    }

    val (calcEndHandleX, calcEndHandleY) = if (isEndPage && currentWordsList.isNotEmpty()) {
        val eWordIdx = curSel!!.endWordIndex.coerceIn(0, currentWordsList.size - 1)
        val ew = currentWordsList[eWordIdx]
        val eLen = maxOf(1, ew.text.length)
        val eOffset = (if (curSel.endCharOffset in 0..eLen) curSel.endCharOffset else eLen).coerceIn(0, eLen)
        val eFrac = eOffset.toFloat() / eLen
        val eX = ew.left + (ew.right - ew.left) * eFrac
        Pair(eX * displayedWPx, ew.bottom * displayedHPx)
    } else {
        Pair(0f, 0f)
    }

    val currentStartHandleX by rememberUpdatedState(calcStartHandleX)
    val currentStartHandleY by rememberUpdatedState(calcStartHandleY)
    val currentEndHandleX by rememberUpdatedState(calcEndHandleX)
    val currentEndHandleY by rememberUpdatedState(calcEndHandleY)

    LaunchedEffect(doc, pageIndex, bitmap) {
        if (words.isEmpty()) {
            val cached = doc.getWords(pageIndex)
            if (cached != null && cached.isNotEmpty()) {
                words = cached
            } else {
                withContext(Dispatchers.IO) {
                    val extracted = PdfTextHelper.extractWordsFromPage(
                        ctx = ctx,
                        uri = pane.uri ?: reader.getBookUri(),
                        doc = doc,
                        pageIndex = pageIndex,
                        fallbackBitmap = doc.getAnyCachedBitmap(pageIndex)
                    )
                    if (extracted.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            words = extracted
                        }
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .width(displayedWidth)
            .height(displayedHeight)
    ) {
        // 1. Base rendered image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "PDF Page ${pageIndex + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Medium,
                colorFilter = colorFilter
            )
        }

        // 2. Canvas layer: Persistent highlights + Live Selection & Handles
        val primaryHandleColor = MaterialTheme.colorScheme.primary
        val selectionBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentWordsList, pageHighlights, isZenMode) {
                    detectMultiTapGestures(
                        onTap = { tapOffset ->
                            if (!pane.isDraggingSelection) {
                                focusManager.clearFocus()
                                val xNorm = (tapOffset.x / size.width).coerceIn(0f, 1f)
                                val yNorm = (tapOffset.y / size.height).coerceIn(0f, 1f)

                                val hit = PdfTextHelper.findHighlightAt(pageHighlights, xNorm, yNorm)
                                if (hit != null) {
                                    val otherPane = if (pane == reader.book) reader.vocab else reader.book
                                    otherPane.activeHighlightMenu = null
                                    otherPane.activeSelection = null
                                    pane.activeHighlightMenu = hit
                                    reader.book.activeSelection = null
                                    reader.vocab.activeSelection = null
                                    val wordIdx = PdfTextHelper.findWordAt(currentWordsList, xNorm, yNorm)
                                    reader.tappedHighlightWord = if (wordIdx != -1) currentWordsList[wordIdx].text else null
                                } else {
                                    reader.book.activeHighlightMenu = null
                                    reader.vocab.activeHighlightMenu = null
                                    reader.book.activeSelection = null
                                    reader.vocab.activeSelection = null
                                    reader.dictSummary = null
                                    reader.showDictPopup = false
                                    reader.tappedHighlightWord = null
                                }
                            }
                        },
                        onDoubleTap = { offset ->
                            if (isZenMode) {
                                onToggleZenControls()
                            } else if (onDoubleTapPage != null) {
                                onDoubleTapPage(offset)
                            }
                        },
                        onTripleTap = {
                            onTripleTap()
                        },
                        onLongPress = { pressOffset ->
                            val xNorm = (pressOffset.x / size.width).coerceIn(0f, 1f)
                            val yNorm = (pressOffset.y / size.height).coerceIn(0f, 1f)

                            val wordIdx = PdfTextHelper.findWordAt(currentWordsList, xNorm, yNorm)
                            if (wordIdx != -1) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                var startIdx = wordIdx
                                while (startIdx > 0) {
                                    val prevW = currentWordsList[startIdx - 1]
                                    if (prevW.lineIndex == currentWordsList[wordIdx].lineIndex &&
                                        (currentWordsList[startIdx].left - prevW.right) < 0.003f &&
                                        prevW.text.any { it.isLetterOrDigit() } && currentWordsList[startIdx].text.any { it.isLetterOrDigit() }) {
                                        startIdx--
                                    } else break
                                }
                                var endIdx = wordIdx
                                while (endIdx + 1 < currentWordsList.size) {
                                    val nextW = currentWordsList[endIdx + 1]
                                    if (nextW.lineIndex == currentWordsList[wordIdx].lineIndex) {
                                        val gap = nextW.left - currentWordsList[endIdx].right
                                        if (gap < 0.003f && nextW.text.any { it.isLetterOrDigit() } && currentWordsList[endIdx].text.any { it.isLetterOrDigit() }) {
                                            endIdx++
                                        } else if (gap < 0.035f && nextW.text.all { !it.isLetterOrDigit() }) {
                                            endIdx++
                                        } else break
                                    } else break
                                }
                                val otherPane = if (pane == reader.book) reader.vocab else reader.book
                                otherPane.activeSelection = null
                                otherPane.activeHighlightMenu = null
                                pane.activeSelection = TextSelection(
                                    startPageIndex = pageIndex,
                                    startWordIndex = startIdx,
                                    startCharOffset = 0,
                                    endPageIndex = pageIndex,
                                    endWordIndex = endIdx,
                                    endCharOffset = currentWordsList[endIdx].text.length
                                )
                                pane.activeHighlightMenu = null
                                reader.tappedHighlightWord = null

                                isLongPressDragging = true
                                pane.isDraggingSelection = true
                                longPressCumulative = pressOffset
                            }
                        },
                        onLongPressEnd = {
                            isLongPressDragging = false
                            pane.isDraggingSelection = false
                        }
                    )
                }
        ) {
            // Draw Saved Highlights
            pageHighlights.forEach { h ->
                val hlColor = parseHexColor(h.colorHex).copy(alpha = 0.42f)
                h.rects.forEach { r ->
                    drawRoundRect(
                        color = hlColor,
                        topLeft = Offset(r.left * size.width, r.top * size.height),
                        size = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                }
            }

            // Draw Active Selection for this page
            val pageWordRange = activeSelection?.wordRangeForPage(pageIndex, currentWordsList.size)
            if (activeSelection != null && pageWordRange != null && currentWordsList.isNotEmpty()) {
                val s = activeSelection
                val (sChar, eChar) = s.charOffsetsForPage(pageIndex, pageWordRange)
                val rects = PdfTextHelper.getSelectionRects(currentWordsList, pageWordRange.first, pageWordRange.last, sChar, eChar)
                rects.forEach { r ->
                    drawRoundRect(
                        color = selectionBgColor,
                        topLeft = Offset(r.left * size.width, r.top * size.height),
                        size = Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    )
                }

                // Draw Start pin if this is start page
                if (s.startPageIndex == pageIndex) {
                    val sIdx = s.startWordIndex.coerceIn(0, currentWordsList.size - 1)
                    val startWord = currentWordsList[sIdx]
                    val sLen = maxOf(1, startWord.text.length)
                    val sOffset = s.startCharOffset.coerceIn(0, sLen)
                    val sFrac = sOffset.toFloat() / sLen
                    val startX = (startWord.left + (startWord.right - startWord.left) * sFrac) * size.width
                    val startY = startWord.top * size.height
                    val startBottom = startWord.bottom * size.height

                    drawLine(
                        color = primaryHandleColor,
                        start = Offset(startX, startY),
                        end = Offset(startX, startBottom),
                        strokeWidth = 2.5.dp.toPx()
                    )
                    drawCircle(
                        color = primaryHandleColor,
                        radius = 6.dp.toPx(),
                        center = Offset(startX, startY - 2.dp.toPx())
                    )
                }

                // Draw End pin if this is end page
                if (s.endPageIndex == pageIndex) {
                    val eIdx = s.endWordIndex.coerceIn(0, currentWordsList.size - 1)
                    val endWord = currentWordsList[eIdx]
                    val eLen = maxOf(1, endWord.text.length)
                    val eOffset = (if (s.endCharOffset in 0..eLen) s.endCharOffset else eLen).coerceIn(0, eLen)
                    val eFrac = eOffset.toFloat() / eLen
                    val endX = (endWord.left + (endWord.right - endWord.left) * eFrac) * size.width
                    val endY = endWord.top * size.height
                    val endBottom = endWord.bottom * size.height

                    drawLine(
                        color = primaryHandleColor,
                        start = Offset(endX, endY),
                        end = Offset(endX, endBottom),
                        strokeWidth = 2.5.dp.toPx()
                    )
                    drawCircle(
                        color = primaryHandleColor,
                        radius = 6.dp.toPx(),
                        center = Offset(endX, endBottom + 2.dp.toPx())
                    )
                }
            }
        }

        // 3. Draggable touch targets for Start and End Handles
        val isDraggingActive = pane.isDraggingSelection
        if ((isStartPage || isDraggingStart) && (!isDraggingActive || isDraggingStart) && currentWordsList.isNotEmpty()) {
            var dragBoxOffset by remember { mutableStateOf<IntOffset?>(null) }
            val currentStartBoxOffset = dragBoxOffset ?: IntOffset(
                (calcStartHandleX - halfTouchPx).roundToInt(),
                (calcStartHandleY - halfTouchPx).roundToInt()
            )

            // Start Handle Drag Box
            Box(
                modifier = Modifier
                    .offset { currentStartBoxOffset }
                    .size(handleTouchSize)
                    .pointerInput(pageIndex) {
                        detectDragGestures(
                            onDragStart = {
                                isDraggingStart = true
                                pane.isDraggingSelection = true
                                dragBoxOffset = currentStartBoxOffset
                                dragStartCumulative = Offset(currentStartHandleX, currentStartHandleY)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragStartCumulative += dragAmount
                                dragBoxOffset = IntOffset(
                                    (dragStartCumulative.x - halfTouchPx).roundToInt(),
                                    (dragStartCumulative.y - halfTouchPx).roundToInt()
                                )

                                val (targetPage, targetYNorm) = PdfTextHelper.resolveContinuousPage(
                                    fromPage = pageIndex,
                                    yOffset = dragStartCumulative.y,
                                    displayedWPx = currentWPx,
                                    docPageCount = doc.pageCount,
                                    spacerPx = with(density) { 16.dp.toPx() },
                                    getPageRatio = { p -> doc.getPageRatio(p) ?: 0.707f }
                                )

                                val curXNorm = (dragStartCumulative.x / currentWPx).coerceIn(0f, 1f)
                                val targetWords = doc.getWords(targetPage) ?: doc.getOrExtractWords(ctx, pane.uri ?: (if (pane == reader.book) reader.getBookUri() else null), targetPage)

                                if (targetWords.isNotEmpty()) {
                                    val selPoint = PdfTextHelper.findSelectionPointAt(targetWords, curXNorm, targetYNorm, isEndHandle = false)
                                    val sel = pane.activeSelection ?: return@detectDragGestures
                                    val endWords = if (sel.endPageIndex == targetPage) targetWords else (doc.getWords(sel.endPageIndex) ?: emptyList())
                                    val endWordLen = if (endWords.isNotEmpty() && sel.endWordIndex in endWords.indices) endWords[sel.endWordIndex].text.length else 0
                                    val endChar = if (sel.endCharOffset in 0..endWordLen) sel.endCharOffset else endWordLen

                                    val cmp = PdfTextHelper.compareSelectionPos(
                                        targetPage, selPoint.wordIndex, selPoint.charOffset,
                                        sel.endPageIndex, sel.endWordIndex, endChar
                                    )

                                    if (cmp > 0) {
                                        if (sel.startPageIndex != sel.endPageIndex || sel.startWordIndex != sel.endWordIndex || sel.startCharOffset != endChar) {
                                            pane.activeSelection = sel.copy(
                                                startPageIndex = sel.endPageIndex,
                                                startWordIndex = sel.endWordIndex,
                                                startCharOffset = endChar
                                            )
                                        }
                                    } else {
                                        if (sel.startPageIndex != targetPage || sel.startWordIndex != selPoint.wordIndex || sel.startCharOffset != selPoint.charOffset) {
                                            pane.activeSelection = sel.copy(
                                                startPageIndex = targetPage,
                                                startWordIndex = selPoint.wordIndex,
                                                startCharOffset = selPoint.charOffset
                                            )
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                isDraggingStart = false
                                pane.isDraggingSelection = false
                                dragBoxOffset = null
                            },
                            onDragCancel = {
                                isDraggingStart = false
                                pane.isDraggingSelection = false
                                dragBoxOffset = null
                            }
                        )
                    }
            )
        }

        if ((isEndPage || isDraggingEnd) && (!isDraggingActive || isDraggingEnd) && currentWordsList.isNotEmpty()) {
            var dragBoxOffset by remember { mutableStateOf<IntOffset?>(null) }
            val currentEndBoxOffset = dragBoxOffset ?: IntOffset(
                (calcEndHandleX - halfTouchPx).roundToInt(),
                (calcEndHandleY - halfTouchPx).roundToInt()
            )

            // End Handle Drag Box
            Box(
                modifier = Modifier
                    .offset { currentEndBoxOffset }
                    .size(handleTouchSize)
                    .pointerInput(pageIndex) {
                        detectDragGestures(
                            onDragStart = {
                                isDraggingEnd = true
                                pane.isDraggingSelection = true
                                dragBoxOffset = currentEndBoxOffset
                                dragEndCumulative = Offset(currentEndHandleX, currentEndHandleY)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragEndCumulative += dragAmount
                                dragBoxOffset = IntOffset(
                                    (dragEndCumulative.x - halfTouchPx).roundToInt(),
                                    (dragEndCumulative.y - halfTouchPx).roundToInt()
                                )

                                val (targetPage, targetYNorm) = PdfTextHelper.resolveContinuousPage(
                                    fromPage = pageIndex,
                                    yOffset = dragEndCumulative.y,
                                    displayedWPx = currentWPx,
                                    docPageCount = doc.pageCount,
                                    spacerPx = with(density) { 16.dp.toPx() },
                                    getPageRatio = { p -> doc.getPageRatio(p) ?: 0.707f }
                                )

                                val curXNorm = (dragEndCumulative.x / currentWPx).coerceIn(0f, 1f)
                                val targetWords = doc.getWords(targetPage) ?: doc.getOrExtractWords(ctx, pane.uri ?: (if (pane == reader.book) reader.getBookUri() else null), targetPage)

                                if (targetWords.isNotEmpty()) {
                                    val selPoint = PdfTextHelper.findSelectionPointAt(targetWords, curXNorm, targetYNorm, isEndHandle = true)
                                    val sel = pane.activeSelection ?: return@detectDragGestures
                                    val startChar = sel.startCharOffset

                                    val cmp = PdfTextHelper.compareSelectionPos(
                                        targetPage, selPoint.wordIndex, selPoint.charOffset,
                                        sel.startPageIndex, sel.startWordIndex, startChar
                                    )

                                    if (cmp < 0) {
                                        if (sel.endPageIndex != sel.startPageIndex || sel.endWordIndex != sel.startWordIndex || sel.endCharOffset != startChar) {
                                            pane.activeSelection = sel.copy(
                                                endPageIndex = sel.startPageIndex,
                                                endWordIndex = sel.startWordIndex,
                                                endCharOffset = startChar
                                            )
                                        }
                                    } else {
                                        if (sel.endPageIndex != targetPage || sel.endWordIndex != selPoint.wordIndex || sel.endCharOffset != selPoint.charOffset) {
                                            pane.activeSelection = sel.copy(
                                                endPageIndex = targetPage,
                                                endWordIndex = selPoint.wordIndex,
                                                endCharOffset = selPoint.charOffset
                                            )
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                isDraggingEnd = false
                                pane.isDraggingSelection = false
                                dragBoxOffset = null
                            },
                            onDragCancel = {
                                isDraggingEnd = false
                                pane.isDraggingSelection = false
                                dragBoxOffset = null
                            }
                        )
                    }
            )
        }

        // 4. Floating Selection Magnifier Loupe
        val activeMagnifierInfo = remember(
            isDraggingStart, isDraggingEnd, isLongPressDragging,
            dragStartCumulative, dragEndCumulative, longPressCumulative,
            activeSelection, pageIndex
        ) {
            if (activeSelection == null) return@remember null
            if (isDraggingStart) {
                val (targetPage, targetYNorm) = PdfTextHelper.resolveContinuousPage(
                    fromPage = pageIndex,
                    yOffset = dragStartCumulative.y,
                    displayedWPx = currentWPx,
                    docPageCount = doc.pageCount,
                    spacerPx = with(density) { 16.dp.toPx() },
                    getPageRatio = { p -> doc.getPageRatio(p) ?: 0.707f }
                )
                MagnifierInfo(
                    touchOffset = dragStartCumulative,
                    targetPage = targetPage,
                    targetYNorm = targetYNorm,
                    isEndHandle = false
                )
            } else if (isDraggingEnd) {
                val (targetPage, targetYNorm) = PdfTextHelper.resolveContinuousPage(
                    fromPage = pageIndex,
                    yOffset = dragEndCumulative.y,
                    displayedWPx = currentWPx,
                    docPageCount = doc.pageCount,
                    spacerPx = with(density) { 16.dp.toPx() },
                    getPageRatio = { p -> doc.getPageRatio(p) ?: 0.707f }
                )
                MagnifierInfo(
                    touchOffset = dragEndCumulative,
                    targetPage = targetPage,
                    targetYNorm = targetYNorm,
                    isEndHandle = true
                )
            } else if (isLongPressDragging) {
                val (targetPage, targetYNorm) = PdfTextHelper.resolveContinuousPage(
                    fromPage = pageIndex,
                    yOffset = longPressCumulative.y,
                    displayedWPx = currentWPx,
                    docPageCount = doc.pageCount,
                    spacerPx = with(density) { 16.dp.toPx() },
                    getPageRatio = { p -> doc.getPageRatio(p) ?: 0.707f }
                )
                MagnifierInfo(
                    touchOffset = longPressCumulative,
                    targetPage = targetPage,
                    targetYNorm = targetYNorm,
                    isEndHandle = true
                )
            } else {
                null
            }
        }

        if (activeMagnifierInfo != null) {
            val targetPage = activeMagnifierInfo.targetPage
            val targetBitmap = if (targetPage == pageIndex) bitmap else doc.getAnyCachedBitmap(targetPage)?.asImageBitmap()
            val targetWords = if (targetPage == pageIndex) currentWordsList else (doc.getWords(targetPage) ?: emptyList())
            val targetHighlights = reader.highlights.filter { it.pageIndex == targetPage }
            val targetRatio = doc.getPageRatio(targetPage) ?: 0.707f
            val targetHPx = currentWPx / targetRatio

            SelectionMagnifierLoupe(
                info = activeMagnifierInfo,
                targetBitmap = targetBitmap,
                targetWords = targetWords,
                targetHighlights = targetHighlights,
                activeSelection = activeSelection,
                displayedWPx = currentWPx,
                targetHPx = targetHPx,
                colorFilter = colorFilter,
                isDarkMode = reader.isDarkMode
            )
        }
    }
}

data class MagnifierInfo(
    val touchOffset: Offset,
    val targetPage: Int,
    val targetYNorm: Float,
    val isEndHandle: Boolean
)

object SelectionMagnifierHelper {
    fun calculateLoupePosition(
        touchX: Float,
        touchY: Float,
        loupeWPx: Float,
        loupeHPx: Float,
        displayedWPx: Float,
        padPx: Float = 16f,
        gapPx: Float = 48f,
        bottomFlipPx: Float = 72f
    ): Offset {
        val minX = padPx
        val maxX = maxOf(padPx, displayedWPx - loupeWPx - padPx)
        val loupeLeft = (touchX - loupeWPx / 2f).coerceIn(minX, maxX)

        var loupeTop = touchY - loupeHPx - gapPx
        if (loupeTop < padPx) {
            loupeTop = touchY + bottomFlipPx
        }
        return Offset(loupeLeft, loupeTop)
    }

    fun calculateSourcePosition(
        touchX: Float,
        targetYNorm: Float,
        displayedWPx: Float,
        targetHPx: Float,
        targetWords: List<PdfWord>,
        selectedWordIndex: Int?
    ): Offset {
        val sourceX = touchX.coerceIn(0f, displayedWPx)
        val wordIdx = selectedWordIndex?.coerceIn(0, maxOf(0, targetWords.size - 1))
        val sourceY = if (wordIdx != null && wordIdx in targetWords.indices) {
            val w = targetWords[wordIdx]
            (w.top + w.bottom) / 2f * targetHPx
        } else {
            (targetYNorm * targetHPx).coerceIn(0f, targetHPx)
        }
        return Offset(sourceX, sourceY)
    }
}

@Composable
fun SelectionMagnifierLoupe(
    info: MagnifierInfo,
    targetBitmap: ImageBitmap?,
    targetWords: List<PdfWord>,
    targetHighlights: List<Highlight>,
    activeSelection: TextSelection?,
    displayedWPx: Float,
    targetHPx: Float,
    colorFilter: ColorFilter?,
    isDarkMode: Boolean
) {
    val density = LocalDensity.current
    val loupeW = 120.dp
    val loupeH = 56.dp
    val loupeWPx = with(density) { loupeW.toPx() }
    val loupeHPx = with(density) { loupeH.toPx() }
    val cornerRadius = 28.dp
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }

    val primaryHandleColor = MaterialTheme.colorScheme.primary
    val selectionBgColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)

    val padPx = with(density) { 8.dp.toPx() }
    val gapPx = with(density) { 24.dp.toPx() }
    val bottomFlipPx = with(density) { 36.dp.toPx() }

    val loupePos = remember(info.touchOffset, displayedWPx, loupeWPx, loupeHPx, padPx, gapPx, bottomFlipPx) {
        SelectionMagnifierHelper.calculateLoupePosition(
            touchX = info.touchOffset.x,
            touchY = info.touchOffset.y,
            loupeWPx = loupeWPx,
            loupeHPx = loupeHPx,
            displayedWPx = displayedWPx,
            padPx = padPx,
            gapPx = gapPx,
            bottomFlipPx = bottomFlipPx
        )
    }

    val selectedWordIndex = if (info.isEndHandle) {
        activeSelection?.endWordIndex
    } else {
        activeSelection?.startWordIndex
    }
    val sourcePos = remember(info, selectedWordIndex, targetWords, displayedWPx, targetHPx) {
        SelectionMagnifierHelper.calculateSourcePosition(
            touchX = info.touchOffset.x,
            targetYNorm = info.targetYNorm,
            displayedWPx = displayedWPx,
            targetHPx = targetHPx,
            targetWords = targetWords,
            selectedWordIndex = selectedWordIndex
        )
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(loupePos.x.roundToInt(), loupePos.y.roundToInt()) }
            .size(width = loupeW, height = loupeH)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(cornerRadius), clip = false)
            .clip(RoundedCornerShape(cornerRadius))
            .background(if (isDarkMode) Color(0xFF1E1E1E) else Color.White)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val zoom = 1.45f
            val loupeCenter = Offset(size.width / 2f, size.height / 2f)

            clipPath(
                Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(Offset.Zero, size),
                            cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                        )
                    )
                }
            ) {
                // Background
                drawRect(if (isDarkMode) Color(0xFF1E1E1E) else Color.White)

                // Magnified content
                translate(
                    left = loupeCenter.x - sourcePos.x * zoom,
                    top = loupeCenter.y - sourcePos.y * zoom
                ) {
                    scale(zoom, zoom, pivot = Offset.Zero) {
                        // 1. Target page bitmap
                        if (targetBitmap != null) {
                            drawImage(
                                image = targetBitmap,
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(displayedWPx.roundToInt(), targetHPx.roundToInt()),
                                colorFilter = colorFilter,
                                filterQuality = FilterQuality.High
                            )
                        }

                        // 2. Highlights
                        targetHighlights.forEach { h ->
                            val hlColor = parseHexColor(h.colorHex).copy(alpha = 0.42f)
                            h.rects.forEach { r ->
                                drawRoundRect(
                                    color = hlColor,
                                    topLeft = Offset(r.left * displayedWPx, r.top * targetHPx),
                                    size = Size((r.right - r.left) * displayedWPx, (r.bottom - r.top) * targetHPx),
                                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                )
                            }
                        }

                        // 3. Active selection highlight
                        val pageWordRange = activeSelection?.wordRangeForPage(info.targetPage, targetWords.size)
                        if (activeSelection != null && pageWordRange != null && targetWords.isNotEmpty()) {
                            val (sChar, eChar) = activeSelection.charOffsetsForPage(info.targetPage, pageWordRange)
                            val rects = PdfTextHelper.getSelectionRects(
                                targetWords,
                                pageWordRange.first,
                                pageWordRange.last,
                                sChar,
                                eChar
                            )
                            rects.forEach { r ->
                                drawRoundRect(
                                    color = selectionBgColor,
                                    topLeft = Offset(r.left * displayedWPx, r.top * targetHPx),
                                    size = Size((r.right - r.left) * displayedWPx, (r.bottom - r.top) * targetHPx),
                                    cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                                )
                            }
                        }
                    }
                }

                // 4. Center vertical handle pin guide line
                val pinTop = 8.dp.toPx()
                val pinBottom = size.height - 8.dp.toPx()
                drawLine(
                    color = primaryHandleColor,
                    start = Offset(loupeCenter.x, pinTop),
                    end = Offset(loupeCenter.x, pinBottom),
                    strokeWidth = 2.dp.toPx()
                )

                if (info.isEndHandle) {
                    drawCircle(
                        color = primaryHandleColor,
                        radius = 4.5.dp.toPx(),
                        center = Offset(loupeCenter.x, pinBottom + 1.dp.toPx())
                    )
                } else {
                    drawCircle(
                        color = primaryHandleColor,
                        radius = 4.5.dp.toPx(),
                        center = Offset(loupeCenter.x, pinTop - 1.dp.toPx())
                    )
                }

                // 5. Border outline
                val borderColor = if (isDarkMode) Color(0x66FFFFFF) else Color(0x33000000)
                drawRoundRect(
                    color = borderColor,
                    topLeft = Offset.Zero,
                    size = size,
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun SelectionFloatingToolbar(
    onHighlight: (String) -> Unit,
    onCopy: () -> Unit,
    onSearch: () -> Unit,
    onShare: () -> Unit,
    onSelectAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 410.dp)
            .padding(1.dp)
            .pointerInput(Unit) {
                detectTapGestures { /* consume tap inside toolbar */ }
            }
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color Swatches (Left section)
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HighlightColors.forEach { (hex, _) ->
                    val c = parseHexColor(hex)
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(1.2.dp, Color.Black.copy(alpha = 0.25f), CircleShape)
                            .clickable { onHighlight(hex) }
                    )
                }
            }

            Spacer(Modifier.width(6.dp))
            VerticalDivider(modifier = Modifier.height(18.dp), thickness = 1.dp)
            Spacer(Modifier.width(4.dp))

            // Action buttons taking more horizontal space
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarPillBtn(label = "Copy", onClick = onCopy)
                ToolbarPillBtn(label = "Google", onClick = onSearch)
                ToolbarPillBtn(label = "Share", onClick = onShare)
                ToolbarPillBtn(label = "All", onClick = onSelectAll)
                ToolbarPillBtn(label = "✕", onClick = onDismiss)
            }
        }
    }
}

@Composable
fun ExistingHighlightToolbar(
    highlight: Highlight,
    onColorChange: (String) -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 410.dp)
            .padding(1.dp)
            .pointerInput(Unit) {
                detectTapGestures { /* consume tap inside toolbar */ }
            }
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color swatches (Left section)
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HighlightColors.forEach { (hex, _) ->
                    val c = parseHexColor(hex)
                    val isSelected = hEquals(highlight.colorHex, hex)
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (isSelected) 2.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.25f),
                                shape = CircleShape
                            )
                            .clickable { onColorChange(hex) }
                    )
                }
            }

            Spacer(Modifier.width(6.dp))
            VerticalDivider(modifier = Modifier.height(18.dp), thickness = 1.dp)
            Spacer(Modifier.width(4.dp))

            // Action buttons taking more horizontal space
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolbarPillBtn(label = "Del", textColor = MaterialTheme.colorScheme.error, onClick = onDelete)
                ToolbarPillBtn(label = "Copy", onClick = onCopy)
                ToolbarPillBtn(label = "Google", onClick = onSearch)
                ToolbarPillBtn(label = "✕", onClick = onDismiss)
            }
        }
    }
}

private fun hEquals(c1: String, c2: String): Boolean {
    return c1.removePrefix("#").equals(c2.removePrefix("#"), ignoreCase = true)
}

@Composable
private fun ToolbarPillBtn(
    label: String,
    textColor: Color? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
            fontWeight = FontWeight.Bold,
            color = textColor ?: MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun HighlightsDialog(
    reader: Reader,
    onDismiss: () -> Unit,
    onJumpToPage: (Int) -> Unit
) {
    val groups = remember(reader.highlights.toList()) {
        reader.getGroupedHighlights()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Highlights (${groups.size})")
        },
        text = {
            if (groups.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No highlights yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Long-press any word on a page to select and highlight text with custom colors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(groups, key = { it.id }) { group ->
                        val dateStr = remember(group.createdAt) {
                            java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(group.createdAt))
                        }
                        val color = parseHexColor(group.colorHex)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onJumpToPage(group.minPage)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(1.dp, Color.Black.copy(alpha = 0.2f), CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = group.pageDisplay,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = dateStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "\"${group.text}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { reader.removeHighlight(group.primaryHighlight) }) {
                                Text("Del", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

