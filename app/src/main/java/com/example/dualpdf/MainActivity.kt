package com.example.dualpdf

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.content.ContentUris
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.res.Configuration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.util.UUID
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

private val DOCUMENT_MIMES = arrayOf("application/pdf", "application/epub+zip")

fun renderWidth(widthPx: Int, zoom: Float) =
    (((widthPx * zoom).toInt() + 63) / 64 * 64).coerceIn(256, 3072)

/* =========================================================================
 *  PdfDoc — thin, thread-safe wrapper over PdfRenderer
 *  PdfRenderer allows only ONE open page at a time, so every render is
 *  serialised by a mutex. Pages are always closed in `finally`.
 * ========================================================================= */
class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val tempFile: File?,          // non-null when we had to copy a non-seekable stream
    val convertedFile: File? = null
) {
    val pageCount: Int = renderer.pageCount
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + renderDispatcher)
    private val textDispatcher = Dispatchers.IO.limitedParallelism(1)
    
    @Volatile private var closed = false
    private val bitmapCache = object : android.util.LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 3).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        // Removed entryRemoved and recycle() to prevent "Canvas: trying to use a recycled bitmap" crash in Compose
    }

    private val ocrCache = android.util.LruCache<String, List<OcrBox>>(50)
    private val wordsCache = android.util.LruCache<Int, List<PdfWord>>(120)
    private val latestBitmaps = java.util.concurrent.ConcurrentHashMap<Int, Bitmap>()
    private val preloadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    
    private val pageRatios = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val textCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    val itextLock = Any()
    private var pdfBytes: ByteArray? = null

    var itextReader: com.itextpdf.text.pdf.PdfReader? = null
        private set

    private fun initItextReaderLocked(ctx: Context, uri: Uri?): com.itextpdf.text.pdf.PdfReader? {
        if (itextReader != null) return itextReader
        try {
            if (convertedFile != null && convertedFile.exists()) {
                itextReader = com.itextpdf.text.pdf.PdfReader(convertedFile.absolutePath)
                return itextReader
            }
            if (tempFile != null && tempFile.exists()) {
                itextReader = com.itextpdf.text.pdf.PdfReader(tempFile.absolutePath)
                return itextReader
            }
            if (uri != null) {
                if (uri.scheme == "file" && uri.path != null && File(uri.path!!).exists()) {
                    itextReader = com.itextpdf.text.pdf.PdfReader(uri.path)
                    return itextReader
                }
                if (pdfBytes == null) {
                    pdfBytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                pdfBytes?.let { bytes ->
                    itextReader = com.itextpdf.text.pdf.PdfReader(bytes)
                }
            }
        } catch (t: Throwable) {
            t.rethrowIfFatal()
        }
        return itextReader
    }

    fun synchronizedExtractWords(pageIndex1Based: Int): List<PdfWord> = synchronized(itextLock) {
        val reader = itextReader ?: return emptyList()
        if (pageIndex1Based > reader.numberOfPages) return emptyList()
        return PdfTextHelper.extractWordsFromReader(reader, pageIndex1Based)
    }

    suspend fun getOrInitTextReader(ctx: Context, uri: Uri?): com.itextpdf.text.pdf.PdfReader? = withContext(textDispatcher) {
        synchronized(itextLock) {
            initItextReaderLocked(ctx, uri)
        }
    }

    @Volatile var focusIndex: Int = 0

    fun getPageText(index: Int): String? = textCache[index]
    fun putPageText(index: Int, text: String) { textCache[index] = text }

    fun getWords(pageIndex: Int): List<PdfWord>? = wordsCache.get(pageIndex)
    fun putWords(pageIndex: Int, words: List<PdfWord>) { wordsCache.put(pageIndex, words) }

    fun getOrExtractWords(ctx: Context, uri: Uri?, pageIndex: Int): List<PdfWord> {
        if (pageIndex < 0 || pageIndex >= pageCount) return emptyList()
        wordsCache.get(pageIndex)?.let { return it }
        synchronized(itextLock) {
            wordsCache.get(pageIndex)?.let { return it }
            try {
                initItextReaderLocked(ctx, uri)
                val itext = itextReader
                if (itext != null && pageIndex + 1 <= itext.numberOfPages) {
                    val words = PdfTextHelper.extractWordsFromReader(itext, pageIndex + 1)
                    if (words.isNotEmpty()) {
                        wordsCache.put(pageIndex, words)
                        return words
                    }
                }
            } catch (t: Throwable) {
                t.rethrowIfFatal()
            }
        }
        return emptyList()
    }

    fun getCachedOcrBoxes(key: String): List<OcrBox>? = ocrCache.get(key)
    fun putCachedOcrBoxes(key: String, boxes: List<OcrBox>) { ocrCache.put(key, boxes) }

    fun getAnyCachedBitmap(pageIndex: Int): Bitmap? = latestBitmaps[pageIndex]

    fun getPageRatio(index: Int): Float? = pageRatios[index] ?: pageRatios.values.firstOrNull()

    /** Renders [index] as a white-backed ARGB bitmap, [targetWidth] px wide (clamped by MAX_DIM). */
    suspend fun render(index: Int, targetWidth: Int): Bitmap = withContext(renderDispatcher) {
        val cacheKey = "${index}_${targetWidth}"
        bitmapCache.get(cacheKey)?.let { 
            latestBitmaps[index] = it
            return@withContext it 
        }

        check(!closed) { "Document is closed" }
        bitmapCache.get(cacheKey)?.let { 
            latestBitmaps[index] = it
            return@withContext it 
        }

        val page = renderer.openPage(index)
        try {
            val ratio = page.width.toFloat() / page.height.toFloat()
            pageRatios[index] = ratio
            
            val scale = min(targetWidth.toFloat() / page.width, MAX_DIM.toFloat() / page.height)
            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AColor.WHITE)           // PdfRenderer needs an opaque background
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmapCache.put(cacheKey, bmp)
            latestBitmaps[index] = bmp
            bmp
        } finally {
            page.close()
        }
    }

    fun preload(index: Int, targetWidth: Int) {
        if (index < 0 || index >= pageCount || closed) return
        val cacheKey = "${index}_${targetWidth}"
        if (bitmapCache.get(cacheKey) != null) return
        
        val jobKey = cacheKey
        if (preloadJobs[jobKey]?.isActive == true) return
        
        preloadJobs[jobKey] = scope.launch {
            if (kotlin.math.abs(index - focusIndex) > 3) return@launch
            runCatching { render(index, targetWidth) }
            preloadJobs.remove(jobKey)
        }
    }

    fun preloadRange(centerIndex: Int, targetWidth: Int, radius: Int = 3) {
        for (i in (centerIndex - radius).coerceAtLeast(0).. (centerIndex + radius).coerceAtMost(pageCount - 1)) {
            if (i != centerIndex) {
                preload(i, targetWidth)
            }
        }
        
        // Cancel stale preloads outside the radius
        val activeKeys = preloadJobs.keys().toList()
        for (key in activeKeys) {
            val parts = key.split("_")
            if (parts.size >= 2) {
                val idx = parts[0].toIntOrNull()
                if (idx != null && kotlin.math.abs(idx - centerIndex) > radius) {
                    preloadJobs[key]?.cancel()
                    preloadJobs.remove(key)
                }
            }
        }
    }

    fun startRatioCompute() {
        scope.launch(renderDispatcher) {
            for (i in 0 until pageCount) {
                if (closed) break
                if (pageRatios[i] == null) {
                    runCatching {
                        val page = renderer.openPage(i)
                        pageRatios[i] = page.width.toFloat() / page.height.toFloat()
                        page.close()
                    }
                    kotlinx.coroutines.yield()
                }
            }
        }
    }

    /** Waits for any in-flight render, then releases renderer, descriptor and temp copy. */
    suspend fun close() = withContext(NonCancellable + renderDispatcher) {
        if (!closed) {
            closed = true
            scope.cancel()
            preloadJobs.values.forEach { it.cancel() }
            preloadJobs.clear()
            runCatching { renderer.close() }
            runCatching { pfd.close() }
            synchronized(itextLock) {
                runCatching { itextReader?.close() }
                itextReader = null
                pdfBytes = null
            }
            tempFile?.delete()
            bitmapCache.evictAll()
            ocrCache.evictAll()
            wordsCache.evictAll()
            latestBitmaps.clear()
            textCache.clear()
        }
    }

    companion object {
        private const val MAX_DIM = 6000

        /** Opens [uri]; falls back to a cache copy if the provider's stream isn't seekable. */
        fun open(ctx: Context, uri: Uri): PdfDoc {
            if (EpubParser.isEpub(ctx, uri)) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "PdfHelperReader_files")
                if (!targetDir.exists()) targetDir.mkdirs()

                val rawName = Sidecar.displayName(ctx, uri) ?: uri.lastPathSegment ?: "epub_book.epub"
                val cleanBase = rawName.removePrefix(".").replace(Regex("\\.epub$", RegexOption.IGNORE_CASE), "")
                val epubConvertedFile = File(targetDir, "epub_$cleanBase.pdf")

                if (!epubConvertedFile.exists() || epubConvertedFile.length() < 1000) {
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw FileNotFoundException("Cannot open EPUB stream for $uri")
                    val epubBook = input.use { EpubParser.parseEpub(it) }
                    EpubParser.convertEpubToPdf(epubBook, epubConvertedFile)
                    android.media.MediaScannerConnection.scanFile(
                        ctx, arrayOf(epubConvertedFile.absolutePath), arrayOf("application/pdf"), null
                    )
                }
                val pfd = ParcelFileDescriptor.open(epubConvertedFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val doc = PdfDoc(pfd, PdfRenderer(pfd), null, epubConvertedFile)
                CoroutineScope(Dispatchers.IO).launch {
                    doc.startRatioCompute()
                }
                return doc
            }

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val doc = if (uri.scheme == "file") {
                val file = File(uri.path!!)
                val targetFile = if (file.exists()) file else {
                    val alt1 = File(downloadsDir, "PdfHelperReader_files/${file.name}")
                    val alt2 = File(downloadsDir, file.name)
                    if (alt1.exists()) alt1 else if (alt2.exists()) alt2 else file
                }
                val pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
                PdfDoc(pfd, PdfRenderer(pfd), null)
            } else {
                val resolver = ctx.contentResolver
                try {
                    val pfd = resolver.openFileDescriptor(uri, "r")
                        ?: throw IOException("The provider returned no file descriptor")
                    PdfDoc(pfd, PdfRenderer(pfd), null)
                } catch (e: Exception) {
                    val name = Sidecar.displayName(ctx, uri) ?: uri.lastPathSegment ?: ""
                    val alt1 = File(downloadsDir, "PdfHelperReader_files/$name")
                    val alt2 = File(downloadsDir, name)
                    val targetFile = if (alt1.exists()) alt1 else if (alt2.exists()) alt2 else null
                    if (targetFile != null && targetFile.exists()) {
                        val pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        PdfDoc(pfd, PdfRenderer(pfd), null)
                    } else {
                        val cache = File.createTempFile("pdf_", ".pdf", ctx.cacheDir)
                        try {
                            val input = resolver.openInputStream(uri)
                                ?: throw FileNotFoundException("Cannot open $uri")
                            input.use { i -> cache.outputStream().use { o -> i.copyTo(o) } }
                            val copyPfd = ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY)
                            try {
                                PdfDoc(copyPfd, PdfRenderer(copyPfd), cache)
                            } catch (e2: Exception) {
                                runCatching { copyPfd.close() }
                                throw e2
                            }
                        } catch (e3: Exception) {
                            cache.delete()
                            throw e
                        }
                    }
                }
            }
            CoroutineScope(Dispatchers.IO).launch {
                doc.startRatioCompute()
            }
            return doc
        }
    }
}

/* =========================================================================
 *  Sidecar discovery helpers (Uri parsing + file matching)
 * ========================================================================= */
object Sidecar {
    private const val EXT_AUTH = "com.android.externalstorage.documents"

    fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) { null }

    /** "The_Brothers_Karamazov.pdf" -> ".The_Brothers_Karamazov_vocab.pdf" */
    fun expectedName(bookName: String): String {
        val clean = bookName.removePrefix(".")
        val base = clean.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
        return ".$base" + "_vocab.pdf"
    }

    fun canOpen(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) { false }

    /** "primary:Books/Dir/a.pdf" + "b.pdf" -> "primary:Books/Dir/b.pdf" */
    private fun siblingDocId(bookDocId: String, name: String): String {
        val slash = bookDocId.lastIndexOf('/')
        return if (slash >= 0) bookDocId.substring(0, slash + 1) + name
        else bookDocId.substringBefore(':') + ":" + name
    }

    /**
     * Tries, in order:
     *  1. the sibling document URI on the same provider (works if the OS lets us read it),
     *  2. the same sibling through a persisted folder grant ([treeUri]),
     *  3. a by-name scan of the granted folder's root.
     */
    fun find(ctx: Context, bookUri: Uri, expected: String, treeUri: Uri?): Uri? {
        val auth = bookUri.authority
        if (auth == EXT_AUTH && DocumentsContract.isDocumentUri(ctx, bookUri)) {
            val sibId = siblingDocId(DocumentsContract.getDocumentId(bookUri), expected)
            val direct = DocumentsContract.buildDocumentUri(auth, sibId)
            if (canOpen(ctx, direct)) return direct
            if (treeUri != null && treeUri.authority == auth) {
                val treeId = DocumentsContract.getTreeDocumentId(treeUri)
                if (sibId.startsWith(treeId)) {
                    val viaTree = DocumentsContract.buildDocumentUriUsingTree(treeUri, sibId)
                    if (canOpen(ctx, viaTree)) return viaTree
                }
            }
        }
        return treeUri?.let { scanTreeRoot(ctx, it, expected) }
    }

    private fun scanTreeRoot(ctx: Context, tree: Uri, expected: String): Uri? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        ctx.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            var hit: Uri? = null
            while (hit == null && c.moveToNext()) {
                if (c.getString(1).equals(expected, ignoreCase = true)) {
                    hit = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
            hit
        }
    } catch (e: Exception) { null }
}

/* =========================================================================
 *  Reader — owns both panes + persistence (SharedPreferences)
 *  Keys:  last_book_uri, tree_uri, book_page_<key>, vocab_page_<key>, vocab_uri_<key>
 *  <key> is derived from the book URI so every book keeps its own positions.
 * ========================================================================= */
enum class SplitMode {
    SPLIT_BOTH,
    ONLY_BOOK,
    ONLY_VOCAB
}

enum class ReadingMode {
    PAGE_BY_PAGE,
    CONTINUOUS
}

enum class ViewMode {
    PDF_VIEW,
    TEXT_VIEW
}

enum class VocabMode {
    VOCAB_PDF,
    GOOGLE_WEB,
    BROWSER
}

class Pane {
    var doc by mutableStateOf<PdfDoc?>(null)
    var page by mutableIntStateOf(0)
    var name by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)   // error text shown in a card
    var zoom by mutableFloatStateOf(1.0f)
    var readingMode by mutableStateOf(ReadingMode.CONTINUOUS)
    var viewMode by mutableStateOf(ViewMode.PDF_VIEW)
    var uri by mutableStateOf<Uri?>(null)

    var activeSelection by mutableStateOf<TextSelection?>(null)
    var isDraggingSelection by mutableStateOf(false)
    var activeHighlightMenu by mutableStateOf<Highlight?>(null)
}

data class Bookmark(
    val id: Long,
    val bookPage: Int,
    val vocabPage: Int,
    val name: String,
    val createdTimeMs: Long = System.currentTimeMillis(),
    val snippet: String = ""
)
data class HistoryEntry(
    val uri: String,
    val name: String,
    val timeMs: Long,
    val firstOpenedMs: Long = timeMs,
    val lastClosedMs: Long = timeMs
)

data class SearchHistoryEntry(
    val query: String,
    val formattedQuery: String = query,
    val timeMs: Long = System.currentTimeMillis()
)

data class OcrBox(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

val SharedTextRecognizer by lazy {
    com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
}

suspend fun extractOcrBoxes(doc: PdfDoc, index: Int, bitmap: Bitmap): List<OcrBox> {
    val cacheKey = "ocr_${index}"
    doc.getCachedOcrBoxes(cacheKey)?.let { return it }

    val boxes = suspendCancellableCoroutine { continuation ->
        val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
        val bmpW = bitmap.width.toFloat()
        val bmpH = bitmap.height.toFloat()
        SharedTextRecognizer.process(image)
            .addOnSuccessListener { visionText ->
                val list = mutableListOf<OcrBox>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            element.boundingBox?.let { rect ->
                                list.add(OcrBox(
                                    text = element.text + " ",
                                    left = rect.left.toFloat() / bmpW,
                                    top = rect.top.toFloat() / bmpH,
                                    right = rect.right.toFloat() / bmpW,
                                    bottom = rect.bottom.toFloat() / bmpH
                                ))
                            }
                        }
                    }
                }
                continuation.resumeWith(Result.success<List<OcrBox>>(list))
            }
            .addOnFailureListener {
                continuation.resumeWith(Result.success<List<OcrBox>>(emptyList()))
            }
    }
    doc.putCachedOcrBoxes(cacheKey, boxes)
    return boxes
}

class Reader(private val ctx: Context, private val scope: CoroutineScope) {
    private val prefs = ctx.getSharedPreferences("dual_pdf_reader", Context.MODE_PRIVATE)
    private val lock = Mutex()                      // serialises open/reload operations

    val book = Pane()
    val vocab = Pane()
    var vocabMode by mutableStateOf(VocabMode.VOCAB_PDF)
    var previousVocabMode by mutableStateOf<VocabMode?>(null)
    var googleSearchQuery by mutableStateOf("https://www.google.com")
        private set
    var googleRawQuery by mutableStateOf("")
        private set
    var googleSearchToken by mutableLongStateOf(0L)
        private set
    var googleLastLoadedToken by mutableLongStateOf(0L)

    var googleWebView: android.webkit.WebView? = null
        private set

    fun getOrCreateGoogleWebView(context: Context): android.webkit.WebView {
        val existing = googleWebView
        if (existing != null) {
            (existing.parent as? android.view.ViewGroup)?.removeView(existing)
            return existing
        }
        val wv = android.webkit.WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = 110
            setInitialScale(110)
        }
        googleWebView = wv
        return wv
    }

    fun destroyGoogleWebView() {
        googleWebView?.let { wv ->
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        googleWebView = null
        googleLastLoadedToken = 0L
    }

    fun formatGoogleSearchQuery(text: String): String {
        val clean = text.filter { it >= ' ' || it == '\n' || it == '\t' }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isBlank()) return ""
        if (clean.startsWith("meaning of this:", ignoreCase = true) ||
            clean.startsWith("meaning of this part by part:", ignoreCase = true)) {
            return clean
        }
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        val stripped = clean.trim('"', '“', '”', '\'', '‘', '’')
        return if (words.size > 1) {
            "meaning of this part by part: \"$stripped\""
        } else {
            "meaning of this: \"$stripped\""
        }
    }

    fun searchGoogle(query: String) {
        vocabMode = VocabMode.GOOGLE_WEB
        showDictPopup = false
        dictSummary = null
        val clean = query.filter { it >= ' ' || it == '\n' || it == '\t' }.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return
        val formatted = formatGoogleSearchQuery(clean)
        googleRawQuery = formatted
        googleSearchToken++
        val encoded = Uri.encode(formatted)
        googleSearchQuery = "https://www.google.com/search?q=$encoded"
        addToSearchHistory(clean, formatted)
    }

    var expectedVocab by mutableStateOf<String?>(null)
        private set

    var isDarkMode by mutableStateOf(false)
        private set

    val bookmarks = mutableStateListOf<Bookmark>()
    val highlights = mutableStateListOf<Highlight>()
    val history = mutableStateListOf<HistoryEntry>()
    val searchHistory = mutableStateListOf<SearchHistoryEntry>()
    var historyTab by mutableStateOf(0)
    var ribbonScrollOffset by mutableStateOf(0)
    var browserSortMode by mutableStateOf(BrowserSortMode.TIME)
    var browserScrollIndex by mutableIntStateOf(0)
    var browserScrollOffset by mutableIntStateOf(0)
    var browserSearchQuery by mutableStateOf("")
    var browserFiles by mutableStateOf<List<BookFileInfo>>(emptyList())
    var isScanningBrowserFiles by mutableStateOf(false)
    var browserHasScanned by mutableStateOf(false)
    var closeDialogsRequest by mutableIntStateOf(0)

    var activeSelection: TextSelection?
        get() = book.activeSelection ?: vocab.activeSelection
        set(value) {
            book.activeSelection = value
            vocab.activeSelection = value
        }

    var activeHighlightMenu: Highlight?
        get() = book.activeHighlightMenu ?: vocab.activeHighlightMenu
        set(value) {
            book.activeHighlightMenu = value
            vocab.activeHighlightMenu = value
        }

    var isDraggingSelection: Boolean
        get() = book.isDraggingSelection || vocab.isDraggingSelection
        set(value) {
            book.isDraggingSelection = value
            vocab.isDraggingSelection = value
        }

    var dictSummary by mutableStateOf<VocabGenerator.WordSummary?>(null)
    var showDictPopup by mutableStateOf(true)
    var tappedHighlightWord by mutableStateOf<String?>(null)

    var isBookEpub by mutableStateOf(false)
        private set

    fun isCurrentBookEpub(): Boolean {
        val uri = bookUri
        if (uri != null && EpubParser.isEpub(ctx, uri)) return true
        if (book.name?.lowercase()?.endsWith(".epub") == true) return true
        if (book.doc?.convertedFile?.name?.lowercase()?.startsWith("epub_") == true) return true
        if (book.doc?.convertedFile?.name?.lowercase()?.contains(".epub") == true) return true
        return false
    }

    private var bookUri: Uri? = null
    private var bookKey = ""

    init {
        // Remove stale temp copies from earlier runs.
        ctx.cacheDir.listFiles { f -> f.name.startsWith("pdf_") }?.forEach { it.delete() }
        isDarkMode = getBoolPref("is_dark_mode", false)
        cleanStorageDirectoryIfExceedsLimit()
    }

    fun cleanStorageDirectoryIfExceedsLimit(maxSizeBytes: Long = 500L * 1024L * 1024L) {
        scope.launch(Dispatchers.IO) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "PdfHelperReader_files")
                if (!targetDir.exists() || !targetDir.isDirectory) return@launch

                val allFiles = targetDir.listFiles()?.toList() ?: return@launch
                var totalSize = allFiles.sumOf { it.length() }

                if (totalSize <= maxSizeBytes) return@launch

                // Priority 1: Vocabulary files (*_vocab.pdf or .*_vocab.pdf), oldest first
                val vocabFiles = allFiles.filter { f ->
                    val name = f.name.lowercase()
                    name.contains("_vocab.pdf")
                }.sortedBy { it.lastModified() }

                // Priority 2: Converted files (any converted format: epub_*, mobi_*, fb2_*, converted_*, *_converted.*), oldest first
                val convertedFiles = allFiles.filter { f ->
                    val name = f.name.lowercase()
                    !vocabFiles.contains(f) && (
                        name.startsWith("epub_") || name.startsWith(".epub_") ||
                        name.startsWith("mobi_") || name.startsWith(".mobi_") ||
                        name.startsWith("converted_") || name.startsWith(".converted_") ||
                        name.contains("_converted.") || name.contains(".converted.")
                    )
                }.sortedBy { it.lastModified() }

                // Priority 3: Downloaded books / other files (excluding .PdfHelperReader_state.json), oldest first
                val downloadedFiles = allFiles.filter { f ->
                    !vocabFiles.contains(f) && !convertedFiles.contains(f) && f.name != ".PdfHelperReader_state.json"
                }.sortedBy { it.lastModified() }

                // Keep the latest 1 file in each group protected from deletion
                val deletableVocab = if (vocabFiles.size > 1) vocabFiles.dropLast(1) else emptyList()
                val deletableConverted = if (convertedFiles.size > 1) convertedFiles.dropLast(1) else emptyList()
                val deletableDownloaded = if (downloadedFiles.size > 1) downloadedFiles.dropLast(1) else emptyList()

                // Delete step-by-step: oldest file first, checking 500MB constraint after each deletion
                for (fileGroup in listOf(deletableVocab, deletableConverted, deletableDownloaded)) {
                    for (file in fileGroup) {
                        if (totalSize <= maxSizeBytes) break
                        val fSize = file.length()
                        if (file.exists() && file.delete()) {
                            totalSize -= fSize
                            android.media.MediaScannerConnection.scanFile(
                                ctx, arrayOf(file.absolutePath), null, null
                            )
                        }
                    }
                    if (totalSize <= maxSizeBytes) break
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun computeHash(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = if (uri.scheme == "file") {
            File(uri.path!!).name
        } else {
            Sidecar.displayName(ctx, uri) ?: uri.lastPathSegment ?: "book.pdf"
        }
        val cleanName = name.removePrefix(".").trim()
        cleanName.ifBlank { "book.pdf" }
    }

    fun toggleDarkMode() {
        isDarkMode = !isDarkMode
        editPrefs { putBoolean("is_dark_mode", isDarkMode) }
    }

    private fun persist(uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun describe(e: Exception) = when (e) {
        is SecurityException -> "Permission denied, or the PDF is password-protected."
        is FileNotFoundException -> "File not found (it may have been moved or deleted)."
        else -> e.message ?: e.javaClass.simpleName
    }

    private val stateFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PdfHelperReader_files/.PdfHelperReader_state.json")
    @Volatile private var hasSyncedFromDisk = false

    private fun syncToDisk() {
        if (!hasSyncedFromDisk && stateFile.exists()) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (prefs.all.isNotEmpty()) {
                    stateFile.parentFile?.mkdirs()
                    val map = prefs.all
                    val json = Gson().toJson(map)
                    stateFile.writeText(json)
                }
            } catch (e: Exception) {}
        }
    }

    private fun getBoolPref(key: String, default: Boolean): Boolean {
        return runCatching { prefs.getBoolean(key, default) }.getOrElse {
            runCatching { prefs.getString(key, null)?.toBoolean() }.getOrNull() ?: default
        }
    }

    private fun getIntPref(key: String, default: Int): Int {
        return runCatching { prefs.getInt(key, default) }.getOrElse {
            runCatching {
                val raw = prefs.all[key]
                when (raw) {
                    is Number -> raw.toInt()
                    is String -> raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt() ?: default
                    else -> default
                }
            }.getOrNull() ?: default
        }
    }

    private fun syncFromDisk() {
        try {
            if (stateFile.exists()) {
                val json = stateFile.readText()
                if (json.isNotBlank()) {
                    hasSyncedFromDisk = true
                    val type = object : TypeToken<Map<String, Any>>() {}.type
                    val map: Map<String, Any> = Gson().fromJson(json, type)
                    val editor = prefs.edit()
                    map.forEach { (k, v) ->
                        if (k.startsWith("book_page_") || k.startsWith("vocab_page_") || k == "history_tab" || k == "ribbon_scroll_offset") {
                            if (v is Number) editor.putInt(k, v.toInt())
                            else if (v is String) editor.putInt(k, v.toIntOrNull() ?: v.toDoubleOrNull()?.toInt() ?: 0)
                        } else if (k == "is_dark_mode" || k.startsWith("default_book_extracted")) {
                            if (v is Boolean) editor.putBoolean(k, v)
                            else if (v is String) editor.putBoolean(k, v.toBoolean())
                            else if (v is Number) editor.putBoolean(k, v.toInt() != 0)
                        } else if (v is String) {
                            editor.putString(k, v)
                        } else {
                            editor.putString(k, Gson().toJson(v))
                        }
                    }
                    editor.apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun editPrefs(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        syncToDisk()
    }

    // ---- public entry points -------------------------------------------------

    private val tutorialUriStr by lazy {
        Uri.fromFile(File(ctx.getExternalFilesDir(null), "PdfHelperReader_Tutorial.pdf")).toString()
    }
    private val tutorialEntry by lazy {
        HistoryEntry(tutorialUriStr, "Tutorial", 0L)
    }

    private fun ensureTutorialInHistory() {
        history.removeAll { it.uri == tutorialUriStr || it.name == "Tutorial" }
        history.add(tutorialEntry)
    }

    private fun loadHistory() {
        history.clear()
        val json = prefs.getString("history", "[]")
        try {
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            val list: List<HistoryEntry> = Gson().fromJson(json, type)
            val filtered = list.filter { it.uri != tutorialUriStr && it.name != "Tutorial" }
            history.addAll(filtered.sortedByDescending { it.timeMs })
        } catch (e: Exception) {}
        ensureTutorialInHistory()
    }

    private fun saveHistory() {
        val json = Gson().toJson(history)
        editPrefs { putString("history", json) }
    }

    private fun addToHistory(uri: Uri, name: String) {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val alt1 = File(downloadsDir, "PdfHelperReader_files/$name")
        val alt2 = File(downloadsDir, name)

        val finalUri = if (alt1.exists()) Uri.fromFile(alt1)
                       else if (alt2.exists()) Uri.fromFile(alt2)
                       else uri

        val uriStr = finalUri.toString()
        if (uriStr == tutorialUriStr || name == "Tutorial") {
            ensureTutorialInHistory()
            saveHistory()
            return
        }
        val existing = history.find { it.uri == uriStr || it.name == name }
        val now = System.currentTimeMillis()
        val firstOpened = existing?.let { if (it.firstOpenedMs > 0) it.firstOpenedMs else it.timeMs } ?: now

        history.removeAll { it.uri == uriStr || it.name == name }
        history.add(0, HistoryEntry(
            uri = uriStr,
            name = name,
            timeMs = now,
            firstOpenedMs = firstOpened,
            lastClosedMs = now
        ))
        ensureTutorialInHistory()
        if (history.size > 1001) history.removeAt(history.lastIndex - 1)
        saveHistory()
    }

    fun updateLastClosed(uri: Uri? = bookUri) {
        if (uri == null) return
        val uriStr = uri.toString()
        val index = history.indexOfFirst { it.uri == uriStr }
        if (index != -1) {
            val old = history[index]
            val now = System.currentTimeMillis()
            history[index] = old.copy(lastClosedMs = now, timeMs = now)
            saveHistory()
        }
    }
    
    fun removeFromHistory(entry: HistoryEntry) {
        if (entry.uri == tutorialUriStr || entry.name == "Tutorial") return

        val isCurrentlyOpen = (bookUri != null && (bookUri.toString() == entry.uri || book.name == entry.name))
        if (isCurrentlyOpen) {
            scope.launch {
                lock.withLock {
                    closePane(book)
                    closePane(vocab)
                    bookUri = null
                    bookKey = ""
                    editPrefs { remove("last_book_uri") }
                }
            }
        }

        val cleanBase = entry.name.removePrefix(".").replace(Regex("\\.(epub|pdf)$", RegexOption.IGNORE_CASE), "")
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val epubConvertedFile = File(downloadsDir, "PdfHelperReader_files/epub_$cleanBase.pdf")
        if (epubConvertedFile.exists()) {
            epubConvertedFile.delete()
            android.media.MediaScannerConnection.scanFile(
                ctx, arrayOf(epubConvertedFile.absolutePath), null, null
            )
        }

        history.remove(entry)
        saveHistory()
    }

    fun addToSearchHistory(query: String, formatted: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        searchHistory.removeAll { it.query.equals(clean, ignoreCase = true) || it.formattedQuery.equals(formatted, ignoreCase = true) }
        searchHistory.add(0, SearchHistoryEntry(clean, formatted, System.currentTimeMillis()))
        if (searchHistory.size > 1000) {
            searchHistory.removeAt(searchHistory.lastIndex)
        }
        saveSearchHistory()
    }

    fun removeFromSearchHistory(entry: SearchHistoryEntry) {
        searchHistory.remove(entry)
        saveSearchHistory()
    }

    fun clearSearchHistory() {
        searchHistory.clear()
        saveSearchHistory()
    }

    fun setAndSaveHistoryTab(tab: Int) {
        historyTab = tab
        editPrefs { putInt("history_tab", tab) }
    }

    fun saveRibbonScrollOffset(offset: Int) {
        ribbonScrollOffset = offset
        editPrefs { putInt("ribbon_scroll_offset", offset) }
    }

    private fun loadSearchHistory() {
        searchHistory.clear()
        val json = prefs.getString("search_history", "[]")
        try {
            val type = object : TypeToken<List<SearchHistoryEntry>>() {}.type
            val list: List<SearchHistoryEntry> = Gson().fromJson(json, type) ?: emptyList()
            searchHistory.addAll(list.sortedByDescending { it.timeMs })
        } catch (_: Exception) {}
    }

    private fun saveSearchHistory() {
        val json = Gson().toJson(searchHistory)
        editPrefs { putString("search_history", json) }
    }

    private fun loadRibbonAndHistoryTabState() {
        historyTab = getIntPref("history_tab", 0)
        ribbonScrollOffset = getIntPref("ribbon_scroll_offset", 0)
    }

    fun loadBrowserState() {
        val sortStr = prefs.getString("browser_sort_mode", BrowserSortMode.TIME.name)
        browserSortMode = try {
            BrowserSortMode.valueOf(sortStr ?: BrowserSortMode.TIME.name)
        } catch (_: Exception) {
            BrowserSortMode.TIME
        }
        browserScrollIndex = getIntPref("browser_scroll_index", 0)
        browserScrollOffset = getIntPref("browser_scroll_offset", 0)
    }

    fun saveBrowserScrollState(index: Int, offset: Int) {
        browserScrollIndex = index
        browserScrollOffset = offset
        editPrefs {
            putInt("browser_scroll_index", index)
            putInt("browser_scroll_offset", offset)
        }
    }

    fun updateBrowserSortMode(mode: BrowserSortMode) {
        if (browserSortMode == mode) return
        browserSortMode = mode
        editPrefs { putString("browser_sort_mode", mode.name) }
        browserFiles = sortFileList(browserFiles, mode)
        saveBrowserScrollState(0, 0)
    }

    fun scanBrowserFiles(force: Boolean = false) {
        if (isScanningBrowserFiles) return
        if (browserHasScanned && !force) return
        isScanningBrowserFiles = true
        scope.launch(Dispatchers.IO) {
            try {
                val rawList = scanDeviceBookFiles(ctx)
                val sorted = sortFileList(rawList, browserSortMode)
                withContext(Dispatchers.Main) {
                    browserFiles = sorted
                    browserHasScanned = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    isScanningBrowserFiles = false
                }
            }
        }
    }

    fun sortFileList(list: List<BookFileInfo>, mode: BrowserSortMode): List<BookFileInfo> {
        return when (mode) {
            BrowserSortMode.TIME -> list.sortedByDescending { it.dateModifiedMs }
            BrowserSortMode.NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
    }

    fun syncFromDiskAndRestore() {
        cleanStorageDirectoryIfExceedsLimit()
        syncFromDisk()
        isDarkMode = getBoolPref("is_dark_mode", false)
        loadBookmarks()
        loadHighlights()
        loadHistory()
        loadSearchHistory()
        loadRibbonAndHistoryTabState()
        loadBrowserState()

        val lastBook = prefs.getString("last_book_uri", null)
        if (!lastBook.isNullOrBlank() && book.doc == null) {
            openBook(Uri.parse(lastBook))
        }
    }

    /** Reopen whatever was open last time. */
    fun restore(initialBookUri: Uri? = null) {
        syncFromDisk()
        isDarkMode = getBoolPref("is_dark_mode", false)
        
        loadBookmarks()
        loadHighlights()
        loadHistory()
        loadSearchHistory()
        loadRibbonAndHistoryTabState()
        loadBrowserState()
        
        if (initialBookUri != null) {
            onBookPicked(initialBookUri)
            return
        }
        
        val defaultExtracted = getBoolPref("default_book_extracted_v19", false)
        if (!defaultExtracted) {
            try {
                val destFile = File(ctx.getExternalFilesDir(null), "PdfHelperReader_Tutorial.pdf")
                if (destFile.exists()) destFile.delete()
                ctx.assets.open("PdfHelperReader_Tutorial.pdf").use { i ->
                    destFile.outputStream().use { o -> i.copyTo(o) }
                }
                editPrefs { putBoolean("default_book_extracted_v19", true) }
                val uri = Uri.fromFile(destFile)
                addToHistory(uri, "Tutorial")
                
                if (!prefs.contains("last_book_uri")) {
                    openBook(uri)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        prefs.getString("last_book_uri", null)?.let { openBook(Uri.parse(it)) }
    }

    fun resolveFileUri(uri: Uri, name: String? = null): Uri {
        if (uri.scheme == "file") {
            val file = File(uri.path!!)
            if (file.exists()) return uri
            val fileName = file.name
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f1 = File(downloadsDir, "PdfHelperReader_files/$fileName")
            val f2 = File(downloadsDir, fileName)
            if (f1.exists()) return Uri.fromFile(f1)
            if (f2.exists()) return Uri.fromFile(f2)
            return uri
        }

        val displayName = name ?: Sidecar.displayName(ctx, uri) ?: uri.lastPathSegment ?: ""
        val cleanName = displayName.substringAfterLast('/')
        if (cleanName.isNotBlank()) {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val f1 = File(downloadsDir, "PdfHelperReader_files/$cleanName")
            val f2 = File(downloadsDir, cleanName)
            if (f1.exists()) return Uri.fromFile(f1)
            if (f2.exists()) return Uri.fromFile(f2)
        }
        return uri
    }

    fun onBookPicked(uri: Uri, name: String? = null) {
        val resolved = resolveFileUri(uri, name)
        persist(resolved)
        closeDialogsRequest++
        openBook(resolved)
    }

    fun onVocabPicked(uri: Uri, targetPage: Int? = null) {
        if (bookUri == null) return
        persist(uri)
        editPrefs { putString("vocab_uri_$bookKey", uri.toString()) }
        scope.launch {
            lock.withLock {
                loadVocabCustom(uri, targetPage)
            }
        }
    }

    private suspend fun loadVocabCustom(uri: Uri, targetPage: Int? = null) {
        closePane(vocab)
        val expected = expectedVocab ?: Sidecar.expectedName(book.name ?: "book.pdf")
        vocab.name = expected
        vocab.message = null

        try {
            val doc = withContext(Dispatchers.IO) { PdfDoc.open(ctx, uri) }
            if (doc.pageCount < 1) { doc.close(); throw IOException("The vocabulary PDF has no pages") }
            vocab.name = withContext(Dispatchers.IO) { Sidecar.displayName(ctx, uri) } ?: expected
            
            val mapped = getMappedVocabPage(book.page + 1)
            val pageToSet = targetPage ?: if (mapped != null) (mapped - 1) else getIntPref("vocab_page_$bookKey", 0)
            vocab.page = pageToSet.coerceIn(0, doc.pageCount - 1)
            vocab.doc = doc
            vocab.uri = uri
        } catch (e: Exception) {
            if (e !is CancellationException) vocab.message = describe(e)
        }
    }

    fun deleteAssociatedVocab(entry: HistoryEntry): Boolean {
        val expectedName = Sidecar.expectedName(entry.name)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val vocabFile = File(downloadsDir, "PdfHelperReader_files/$expectedName")

        val vocabUriStr = vocab.uri?.toString() ?: ""
        val isCurrentlyLoaded = vocabUriStr.contains(expectedName) || vocab.name == expectedName
        if (isCurrentlyLoaded) {
            scope.launch {
                lock.withLock {
                    closePane(vocab)
                    vocab.message = "Vocab file deleted."
                    vocab.name = expectedName
                }
            }
        }

        if (bookKey.isNotBlank()) {
            vocabPageMaps.remove(bookKey)
            editPrefs { remove("vocab_pagemap_$bookKey") }
        }

        if (vocabFile.exists()) {
            val deleted = vocabFile.delete()
            if (deleted) {
                android.media.MediaScannerConnection.scanFile(
                    ctx, arrayOf(vocabFile.absolutePath), arrayOf("application/pdf"), null
                )
            }
            return deleted
        }
        return false
    }
    
    fun getBookUri(): Uri? = bookUri

    private val vocabPageMaps = java.util.concurrent.ConcurrentHashMap<String, Map<Int, Int>>()

    fun saveVocabPageMap(map: Map<Int, Int>) {
        if (bookKey.isNotBlank()) {
            vocabPageMaps[bookKey] = map
            val json = Gson().toJson(map)
            editPrefs { putString("vocab_pagemap_$bookKey", json) }
        }
    }

    fun getMappedVocabPage(bookPage: Int): Int? {
        if (bookKey.isBlank()) return null
        var map = vocabPageMaps[bookKey]
        if (map == null) {
            val json = prefs.getString("vocab_pagemap_$bookKey", null)
            if (json != null) {
                try {
                    val type = object : TypeToken<Map<String, Int>>() {}.type
                    val rawMap: Map<String, Int>? = Gson().fromJson(json, type)
                    if (rawMap != null) {
                        val intMap = rawMap.mapKeys { it.key.toIntOrNull() ?: -1 }.filterKeys { it > 0 }
                        vocabPageMaps[bookKey] = intMap
                        map = intMap
                    }
                } catch (e: Exception) {}
            }
        }
        return map?.get(bookPage)
    }

    private var vocabJob: Job? = null
    private var ocrJob: Job? = null

    fun generateVocab(
        pageRange: IntRange? = null,
        maxScoreThreshold: Float = 100f,
        onProgress: (Int, Int) -> Unit,
        onDone: (VocabResult) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val activeDoc = book.doc ?: return
        val oldJob = vocabJob
        vocabJob = scope.launch(Dispatchers.IO) {
            oldJob?.cancel()
            oldJob?.join()
            try {
                val inputPdf = File.createTempFile("book_in", ".pdf", ctx.cacheDir)
                val sourceFile = activeDoc.convertedFile
                if (sourceFile != null && sourceFile.exists()) {
                    sourceFile.inputStream().use { i ->
                        inputPdf.outputStream().use { o -> i.copyTo(o) }
                    }
                } else {
                    val uri = bookUri ?: return@launch
                    ctx.contentResolver.openInputStream(uri)?.use { i ->
                        inputPdf.outputStream().use { o -> i.copyTo(o) }
                    }
                }

                val outputPdf = File(ctx.cacheDir, "vocab_output_${System.currentTimeMillis()}.pdf")

                val result = VocabGenerator.generate(ctx, inputPdf, outputPdf, pageRange, maxScoreThreshold) { current, total ->
                    if (coroutineContext.isActive) {
                        scope.launch(Dispatchers.Main) {
                            onProgress(current, total)
                        }
                    }
                }

                inputPdf.delete()
                if (coroutineContext.isActive) {
                    withContext(Dispatchers.Main) {
                        onDone(result)
                    }
                }
            } catch (t: Throwable) {
                t.rethrowIfFatal()
                if (coroutineContext.isActive) {
                    val err = t.toRecoverableException()
                    withContext(Dispatchers.Main) {
                        onError(err)
                    }
                }
            }
        }
    }

    fun cancelVocab() {
        vocabJob?.cancel()
        vocabJob = null
    }

    suspend fun checkOcrStatus(): String? = withContext(Dispatchers.IO) {
        val activeDoc = book.doc ?: return@withContext "Please open a document first"

        // 1. EPUB check
        if (isBookEpub || isCurrentBookEpub()) {
            return@withContext "This EPUB already has digital text — OCR is not needed"
        }

        // 2. Already OCR'ed file check
        val name = book.name?.lowercase() ?: ""
        val convertedName = activeDoc.convertedFile?.name?.lowercase() ?: ""
        if (name.contains("_ocr.pdf") || convertedName.contains("_ocr.pdf")) {
            return@withContext "Document is already OCR'ed — OCR is not needed"
        }

        // 3. Digital text check on pages
        val totalPages = activeDoc.pageCount
        if (totalPages <= 0) return@withContext null

        val uri = bookUri
        val itext = activeDoc.getOrInitTextReader(ctx, uri)
        if (itext != null) {
            val pagesToSample = listOf(book.page, 0, totalPages / 2, totalPages - 1)
                .distinct()
                .filter { it in 0 until totalPages }

            for (p in pagesToSample) {
                val cached = activeDoc.getWords(p)
                if (cached != null && cached.size >= 5) {
                    return@withContext "Document already contains searchable text — OCR is not needed"
                }

                try {
                    if (p + 1 <= itext.numberOfPages) {
                        val text = com.itextpdf.text.pdf.parser.PdfTextExtractor.getTextFromPage(itext, p + 1)
                        if (text.isNotBlank() && text.trim().length >= 15) {
                            return@withContext "Document already contains searchable text — OCR is not needed"
                        }
                    }
                } catch (t: Throwable) {
                    t.rethrowIfFatal()
                }
            }
        }

        null
    }

    fun generateOcr(forceOcr: Boolean = false, onProgress: (Int, Int) -> Unit, onDone: (File) -> Unit, onError: (Exception) -> Unit) {
        val activeDoc = book.doc ?: return
        if (isBookEpub || isCurrentBookEpub()) {
            onError(IllegalStateException("OCR is disabled for EPUB documents"))
            return
        }
        ocrJob?.cancel()
        ocrJob = scope.launch(Dispatchers.IO) {
            try {
                val inputPdf = File.createTempFile("book_in", ".pdf", ctx.cacheDir)
                val sourceFile = activeDoc.convertedFile
                if (sourceFile != null && sourceFile.exists()) {
                    sourceFile.inputStream().use { i ->
                        inputPdf.outputStream().use { o -> i.copyTo(o) }
                    }
                } else {
                    val uri = bookUri ?: return@launch
                    ctx.contentResolver.openInputStream(uri)?.use { i ->
                        inputPdf.outputStream().use { o -> i.copyTo(o) }
                    }
                }

                val outputPdf = File(ctx.cacheDir, "ocr_output.pdf")
                
                OcrProcessor.processPdf(ctx, inputPdf, outputPdf, forceOcr = forceOcr) { current, total ->
                    scope.launch(Dispatchers.Main) {
                        onProgress(current, total)
                    }
                }
                
                inputPdf.delete()
                withContext(Dispatchers.Main) {
                    onDone(outputPdf)
                }
            } catch (t: Throwable) {
                t.rethrowIfFatal()
                val err = t.toRecoverableException()
                withContext(Dispatchers.Main) {
                    onError(err)
                }
            }
        }
    }

    fun cancelOcr() {
        ocrJob?.cancel()
    }

    fun onTreePicked(uri: Uri) {
        persist(uri)
        prefs.edit().putString("tree_uri", uri.toString()).apply()
        if (bookUri != null) scope.launch { lock.withLock { loadVocab() } }
    }

    private fun loadBookmarks() {
        bookmarks.clear()
        if (bookKey.isNotBlank()) {
            val json = prefs.getString("bookmarks_$bookKey", "[]")
            try {
                val type = object : TypeToken<List<Bookmark>>() {}.type
                val list: List<Bookmark> = Gson().fromJson(json, type) ?: emptyList()
                val clean = list.map {
                    it.copy(
                        name = it.name ?: "Bookmark",
                        snippet = it.snippet ?: ""
                    )
                }
                bookmarks.addAll(clean)
            } catch (e: Exception) {}
        }
    }

    fun loadHighlights() {
        highlights.clear()
        if (bookKey.isNotBlank()) {
            val json = prefs.getString("highlights_$bookKey", "[]")
            try {
                val type = object : TypeToken<List<Highlight>>() {}.type
                val list: List<Highlight> = Gson().fromJson(json, type) ?: emptyList()
                highlights.addAll(list)
            } catch (e: Exception) {}
        }
    }

    fun saveHighlights() {
        if (bookKey.isNotBlank()) {
            val json = Gson().toJson(highlights)
            editPrefs { putString("highlights_$bookKey", json) }
        }
    }

    fun addHighlight(h: Highlight) {
        highlights.add(h)
        saveHighlights()
    }

    fun addHighlights(list: List<Highlight>) {
        if (list.isEmpty()) return
        highlights.addAll(list)
        saveHighlights()
    }

    fun getRelatedHighlights(h: Highlight): List<Highlight> {
        if (h.groupId != null) {
            val group = highlights.filter { it.groupId == h.groupId }.sortedBy { it.pageIndex }
            if (group.isNotEmpty()) return group
        }
        val close = highlights.filter {
            it.colorHex == h.colorHex && kotlin.math.abs(it.createdAt - h.createdAt) < 1000L
        }
        if (close.size > 1) {
            val sorted = close.sortedBy { it.pageIndex }
            var isConsecutive = true
            for (i in 0 until sorted.size - 1) {
                if (sorted[i + 1].pageIndex != sorted[i].pageIndex + 1) {
                    isConsecutive = false
                    break
                }
            }
            if (isConsecutive && sorted.any { it.id == h.id }) {
                return sorted
            }
        }
        return listOf(h)
    }

    fun getGroupedHighlights(): List<HighlightGroup> {
        val processedIds = mutableSetOf<String>()
        val groups = mutableListOf<HighlightGroup>()
        val sorted = highlights.sortedWith(compareBy({ it.pageIndex }, { it.createdAt }))
        for (h in sorted) {
            if (h.id in processedIds) continue
            val related = getRelatedHighlights(h)
            for (r in related) {
                processedIds.add(r.id)
            }
            if (related.isNotEmpty()) {
                groups.add(HighlightGroup(related.sortedBy { it.pageIndex }))
            }
        }
        return groups
    }

    fun removeHighlight(h: Highlight) {
        val related = getRelatedHighlights(h)
        val idsToRemove = related.map { it.id }.toSet()
        highlights.removeAll { it.id in idsToRemove }
        saveHighlights()
    }

    fun updateHighlightColor(h: Highlight, newColorHex: String) {
        val related = getRelatedHighlights(h)
        val idsToUpdate = related.map { it.id }.toSet()
        for (i in highlights.indices) {
            if (highlights[i].id in idsToUpdate) {
                highlights[i] = highlights[i].copy(colorHex = newColorHex)
            }
        }
        saveHighlights()
    }

    fun updateHighlight(h: Highlight) {
        val idx = highlights.indexOfFirst { it.id == h.id }
        if (idx != -1) {
            highlights[idx] = h
            saveHighlights()
        }
    }
    
    fun addBookmark() {
        val doc = book.doc ?: return
        val bookPageIndex = book.page
        val vocabPageIndex = vocab.page
        val now = System.currentTimeMillis()
        val defaultName = "Book: ${bookPageIndex + 1} | Vocab: ${vocabPageIndex + 1}"

        scope.launch {
            var words = emptyList<String>()

            // 1. Try fast native extraction from PdfTextHelper
            val extracted = PdfTextHelper.extractWordsFromPage(ctx, book.uri, doc, bookPageIndex)
            if (extracted.isNotEmpty()) {
                words = extracted.map { it.text.trim() }.filter { it.isNotBlank() }
            }

            // 2. If OCR boxes or words were empty, try iText page text
            if (words.size < 5) {
                val rawText = extractPageText(ctx, book.uri, doc, bookPageIndex)
                if (!rawText.startsWith("No ") && !rawText.startsWith("Could ")) {
                    words = rawText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                }
            }

            // 3. Fallback to cached OCR boxes or render bitmap
            if (words.size < 5) {
                val cachedOcr = doc.getCachedOcrBoxes("ocr_${bookPageIndex}")
                if (!cachedOcr.isNullOrEmpty()) {
                    words = cachedOcr.map { it.text.trim() }.filter { it.isNotBlank() }
                }
            }

            // 3. If still empty, render bitmap and run ML Kit OCR on demand
            if (words.size < 5) {
                try {
                    val bmp = doc.render(bookPageIndex, 1024)
                    val ocr = extractOcrBoxes(doc, bookPageIndex, bmp)
                    if (ocr.isNotEmpty()) {
                        words = ocr.map { it.text.trim() }.filter { it.isNotBlank() }
                    }
                } catch (e: Exception) {}
            }

            val snippetWords = words.take(25).joinToString(" ")

            val b = Bookmark(
                id = now,
                bookPage = bookPageIndex,
                vocabPage = vocabPageIndex,
                name = defaultName,
                createdTimeMs = now,
                snippet = snippetWords
            )
            withContext(Dispatchers.Main) {
                bookmarks.add(b)
                val json = Gson().toJson(bookmarks)
                prefs.edit().putString("bookmarks_$bookKey", json).apply()
            }
        }
    }

    fun removeBookmark(b: Bookmark) {
        bookmarks.remove(b)
        val json = Gson().toJson(bookmarks)
        prefs.edit().putString("bookmarks_$bookKey", json).apply()
    }

    private var pageSaveJob: Job? = null

    fun setBookPage(i: Int) = setPage(book, i)
    fun setVocabPage(i: Int) = setPage(vocab, i)

    private fun setPage(pane: Pane, i: Int) {
        val d = pane.doc ?: return
        val p = i.coerceIn(0, d.pageCount - 1)
        pane.page = p

        if (bookKey.isNotBlank()) {
            prefs.edit().putInt("book_page_$bookKey", book.page)
                 .putInt("vocab_page_$bookKey", vocab.page).apply()
            syncToDisk()
        }
    }

    /** Called from Activity.onDestroy (scope is already cancelled by then). */
    fun closeAll() = runBlocking {
        val b = book.doc; val v = vocab.doc
        book.doc = null; vocab.doc = null
        b?.close(); v?.close()
    }

    // ---- internals -----------------------------------------------------------

    private fun openBook(uri: Uri) {
        scope.launch {
            val hashKey = computeHash(ctx, uri)
            lock.withLock {
                closePane(book); closePane(vocab)
                vocab.message = null; vocab.name = null; expectedVocab = null
                bookUri = uri
                bookKey = hashKey
                book.message = null
                book.name = withContext(Dispatchers.IO) { Sidecar.displayName(ctx, uri) }
                    ?: uri.lastPathSegment ?: "book.pdf"
                    
                addToHistory(uri, book.name ?: "Unknown")
                
                try {
                    val doc = withContext(Dispatchers.IO) { PdfDoc.open(ctx, uri) }
                    if (doc.pageCount < 1) { doc.close(); throw IOException("The PDF has no pages") }
                    book.page = getIntPref("book_page_$bookKey", 0).coerceIn(0, doc.pageCount - 1)
                    book.doc = doc
                    book.uri = uri
                    isBookEpub = isCurrentBookEpub()
                    editPrefs { putString("last_book_uri", uri.toString()) }
                    loadBookmarks()
                    loadHighlights()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    book.message = describe(e)
                }
                if (book.doc != null) loadVocab()
            }
        }
    }

    /** Must be called while holding [lock]. */
    private suspend fun loadVocab() {
        val uri = bookUri ?: return
        closePane(vocab)
        val expected = Sidecar.expectedName(book.name ?: "book.pdf")
        val legacyExpected = (book.name ?: "book.pdf").removePrefix(".").replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "") + "_vocab.pdf"
        expectedVocab = expected
        vocab.name = expected
        vocab.message = null

        val saved = prefs.getString("vocab_uri_$bookKey", null)?.let(Uri::parse)
        val tree = prefs.getString("tree_uri", null)?.let(Uri::parse)
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val hiddenVocabFile = File(downloadsDir, "PdfHelperReader_files/$expected")
        val legacyVocabFile = File(downloadsDir, "PdfHelperReader_files/$legacyExpected")
        val found = withContext(Dispatchers.IO) {
            saved?.takeIf { Sidecar.canOpen(ctx, it) } 
                ?: hiddenVocabFile.takeIf { it.exists() }?.let { Uri.fromFile(it) }
                ?: legacyVocabFile.takeIf { it.exists() }?.let { Uri.fromFile(it) }
                ?: Sidecar.find(ctx, uri, expected, tree)
                ?: Sidecar.find(ctx, uri, legacyExpected, tree)
        }
        if (found == null) {
            vocab.message = "Could not find \"$expected\" next to the book."
            return
        }
        try {
            val doc = withContext(Dispatchers.IO) { PdfDoc.open(ctx, found) }
            if (doc.pageCount < 1) { doc.close(); throw IOException("The vocabulary PDF has no pages") }
            vocab.name = withContext(Dispatchers.IO) { Sidecar.displayName(ctx, found) } ?: expected
            val savedVocabPage = getIntPref("vocab_page_$bookKey", -1)
            val mapped = getMappedVocabPage(book.page + 1)
            val initialPage = if (savedVocabPage >= 0) savedVocabPage else (mapped?.minus(1) ?: 0)
            vocab.page = initialPage.coerceIn(0, doc.pageCount - 1)
            vocab.doc = doc
            vocab.uri = found
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            vocab.message = describe(e)
        }
    }

    private suspend fun closePane(p: Pane) {
        val d = p.doc
        p.doc = null          // UI stops using it first
        p.uri = null
        if (p == book) {
            isBookEpub = false
            activeSelection = null
            isDraggingSelection = false
            dictSummary = null
        }
        d?.close()            // then wait for in-flight render and release
    }
}

/* =========================================================================
 *  Activity
 * ========================================================================= */
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var reader: Reader

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        reader.syncFromDiskAndRestore()
    }

    private fun getIncomingFileUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val action = intent.action
        if (action == Intent.ACTION_VIEW || action == Intent.ACTION_EDIT) {
            if (intent.data != null) return intent.data
            val clipData = intent.clipData
            if (clipData != null && clipData.itemCount > 0) {
                return clipData.getItemAt(0).uri
            }
        }
        return null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        getIncomingFileUri(intent)?.let { uri ->
            reader.onBookPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = Reader(applicationContext, scope)
        val incomingUri = getIncomingFileUri(intent)
        reader.restore(initialBookUri = incomingUri)
        checkPermissions()
        scope.launch(Dispatchers.IO) {
            VocabGenerator.init(applicationContext)
            reader.scanBrowserFiles()
        }
        scope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(1200)
            try {
                android.webkit.WebView(applicationContext).destroy()
            } catch (_: Exception) {}
        }
        setContent { AppTheme { ReaderScreen(reader) } }
    }

    override fun onResume() {
        super.onResume()
        reader.syncFromDiskAndRestore()

        val pending = pendingApkToInstall
        if (pending != null && pending.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                installDownloadedApkUri(this, Uri.fromFile(pending), pending)
            }
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            } else {
                reader.syncFromDiskAndRestore()
            }
        } else {
            val readPerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
            val writePerm = ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (readPerm != PackageManager.PERMISSION_GRANTED || writePerm != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            } else {
                reader.syncFromDiskAndRestore()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()          // cancel first so nothing holds Reader's lock while we close
        reader.destroyGoogleWebView()
        reader.closeAll()
        super.onDestroy()
    }
}

/* =========================================================================
 *  UI
 * ========================================================================= */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

fun getInverseMappedBookPosition(reader: Reader, vocabContinuousPage: Float, bookTotal: Int, vocabTotal: Int): Float {
    val targetVocabPage1Based = vocabContinuousPage + 1.0f

    var bestBookPage = -1
    for (k in 1..bookTotal) {
        val mapped = reader.getMappedVocabPage(k) ?: continue
        if (mapped <= targetVocabPage1Based) {
            bestBookPage = k
        } else {
            break
        }
    }

    if (bestBookPage > 0) {
        val startVocab = reader.getMappedVocabPage(bestBookPage) ?: 1
        val nextVocab = reader.getMappedVocabPage(bestBookPage + 1) ?: (startVocab + 1)
        val span = (nextVocab - startVocab).coerceAtLeast(1).toFloat()
        val fraction = ((targetVocabPage1Based - startVocab) / span).coerceIn(0.0f, 1.0f)
        return (bestBookPage - 1).toFloat() + fraction
    }

    return (vocabContinuousPage * bookTotal.toFloat() / vocabTotal.toFloat())
}

@Composable
fun ReaderScreen(reader: Reader) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val topBarHeight = if (isLandscape) 30.dp else 36.dp

    var isSyncedReading by remember { mutableStateOf(false) }
    var isAutoScroll by remember { mutableStateOf(false) }
    val bookListState = rememberLazyListState(initialFirstVisibleItemIndex = reader.book.page)
    val vocabListState = rememberLazyListState(initialFirstVisibleItemIndex = reader.vocab.page)

    var activeScrollSource by remember { mutableStateOf<String?>("BOOK") }

    LaunchedEffect(bookListState.isScrollInProgress) {
        if (bookListState.isScrollInProgress) {
            activeScrollSource = "BOOK"
        }
    }

    LaunchedEffect(vocabListState.isScrollInProgress) {
        if (vocabListState.isScrollInProgress) {
            activeScrollSource = "VOCAB"
        }
    }

    val isVocabPdfActive = reader.vocab.doc != null &&
        reader.vocabMode == VocabMode.VOCAB_PDF &&
        (reader.vocab.name?.lowercase()?.endsWith("_vocab.pdf") == true ||
         reader.vocab.uri?.toString()?.lowercase()?.endsWith("_vocab.pdf") == true ||
         reader.expectedVocab?.lowercase()?.endsWith("_vocab.pdf") == true)

    val isContinuousMode = reader.book.readingMode == ReadingMode.CONTINUOUS
    val isPdfViewMode = reader.book.viewMode == ViewMode.PDF_VIEW

    LaunchedEffect(isAutoScroll, isContinuousMode, isPdfViewMode, bookListState) {
        if (!isAutoScroll || !isContinuousMode || !isPdfViewMode) return@LaunchedEffect
        var lastTimeNanos = System.nanoTime()
        while (isActive && isAutoScroll && isContinuousMode && isPdfViewMode) {
            withFrameNanos { frameTimeNanos ->
                val dtMs = (frameTimeNanos - lastTimeNanos) / 1_000_000f
                lastTimeNanos = frameTimeNanos
                val viewportHeight = (bookListState.layoutInfo.viewportEndOffset - bookListState.layoutInfo.viewportStartOffset).coerceAtLeast(500)
                val speedPxPerMs = viewportHeight.toFloat() / 300_000f
                val deltaPx = speedPxPerMs * dtMs
                bookListState.dispatchRawDelta(deltaPx)
            }
        }
    }

    LaunchedEffect(isSyncedReading, isVocabPdfActive, isContinuousMode, isPdfViewMode, bookListState, vocabListState) {
        if (!isSyncedReading || !isVocabPdfActive || !isContinuousMode || !isPdfViewMode) return@LaunchedEffect
        snapshotFlow {
            val idx = bookListState.firstVisibleItemIndex
            val off = bookListState.firstVisibleItemScrollOffset
            val layoutInfo = bookListState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                (idx to -off) to 1000
            } else {
                val topItem = visibleItems.first()
                (topItem.index to topItem.offset) to topItem.size
            }
        }
        .collect { (pair, itemSize) ->
            val (bookItemIndex, itemOffset) = pair

            if (activeScrollSource == "VOCAB" && vocabListState.isScrollInProgress) return@collect

            val bookPageIndex = bookItemIndex + 1 // 1-based page
            val size = itemSize.coerceAtLeast(1)
            val offset = -itemOffset.coerceAtMost(0) // crossed top boundary
            val x = (offset.toFloat() / size.toFloat()).coerceIn(0f, 1f)

            val bookTotal = reader.book.doc?.pageCount ?: 100
            val vocabTotal = reader.vocab.doc?.pageCount ?: 100
            if (bookTotal <= 0 || vocabTotal <= 0) return@collect

            val mappedStart = reader.getMappedVocabPage(bookPageIndex)
            val mappedNext = reader.getMappedVocabPage(bookPageIndex + 1)

            val vocabPosition: Float = if (mappedStart != null && mappedNext != null) {
                val mappingLength = (mappedNext - mappedStart).coerceAtLeast(1)
                (mappedStart - 1).toFloat() + (x + 0.01f) * mappingLength.toFloat()
            } else if (mappedStart != null) {
                val ratio = vocabTotal.toFloat() / bookTotal.toFloat()
                (mappedStart - 1).toFloat() + (x + 0.01f) * ratio.coerceAtLeast(1.0f)
            } else {
                val continuousBookPage = (bookPageIndex - 1).toFloat() + x + 0.01f
                continuousBookPage * (vocabTotal.toFloat() / bookTotal.toFloat())
            }

            val targetVocabItemIndex = vocabPosition.toInt().coerceIn(0, vocabTotal - 1)
            val remainderFraction = (vocabPosition - targetVocabItemIndex).coerceIn(0f, 1f)

            val vocabLayoutInfo = vocabListState.layoutInfo
            val vocabItemSize = vocabLayoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 1000
            val targetVocabScrollOffset = (remainderFraction * vocabItemSize.toFloat()).toInt()

            reader.setVocabPage(targetVocabItemIndex)
            vocabListState.scrollToItem(targetVocabItemIndex, targetVocabScrollOffset)
        }
    }

    LaunchedEffect(isSyncedReading, isVocabPdfActive, isContinuousMode, isPdfViewMode, bookListState, vocabListState) {
        if (!isSyncedReading || !isVocabPdfActive || !isContinuousMode || !isPdfViewMode) return@LaunchedEffect
        snapshotFlow {
            val idx = vocabListState.firstVisibleItemIndex
            val off = vocabListState.firstVisibleItemScrollOffset
            val layoutInfo = vocabListState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                (idx to -off) to 1000
            } else {
                val topItem = visibleItems.first()
                (topItem.index to topItem.offset) to topItem.size
            }
        }
        .collect { (pair, itemSize) ->
            val (vocabItemIndex, itemOffset) = pair

            if (activeScrollSource != "VOCAB" || !vocabListState.isScrollInProgress) return@collect

            val vocabPageIndex = vocabItemIndex + 1 // 1-based vocab page
            val size = itemSize.coerceAtLeast(1)
            val offset = -itemOffset.coerceAtMost(0)
            val y = (offset.toFloat() / size.toFloat()).coerceIn(0f, 1f)
            val vocabContinuousPage = (vocabPageIndex - 1).toFloat() + y

            val bookTotal = reader.book.doc?.pageCount ?: 100
            val vocabTotal = reader.vocab.doc?.pageCount ?: 100
            if (bookTotal <= 0 || vocabTotal <= 0) return@collect

            val bookPosition: Float = getInverseMappedBookPosition(reader, vocabContinuousPage, bookTotal, vocabTotal)

            val targetBookItemIndex = bookPosition.toInt().coerceIn(0, bookTotal - 1)
            val remainderFraction = (bookPosition - targetBookItemIndex).coerceIn(0f, 1f)

            val bookLayoutInfo = bookListState.layoutInfo
            val bookItemSize = bookLayoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 1000
            val targetBookScrollOffset = (remainderFraction * bookItemSize.toFloat()).toInt()

            reader.setBookPage(targetBookItemIndex)
            bookListState.scrollToItem(targetBookItemIndex, targetBookScrollOffset)
        }
    }

    var isGenerating by remember { mutableStateOf(false) }
    var generatedFile by remember { mutableStateOf<File?>(null) }
    var genProgressCurrent by remember { mutableIntStateOf(0) }
    var genProgressTotal by remember { mutableIntStateOf(0) }
    var showVocabThresholdDialog by remember { mutableStateOf(false) }
    
    var isOcrGenerating by remember { mutableStateOf(false) }
    var ocrProgressCurrent by remember { mutableIntStateOf(0) }
    var ocrProgressTotal by remember { mutableIntStateOf(0) }

    var isAppUpdating by remember { mutableStateOf(false) }
    var updateProgressCurrent by remember { mutableIntStateOf(0) }
    var updateProgressTotal by remember { mutableIntStateOf(0) }

    var showUpdateInfoDialog by remember { mutableStateOf(false) }
    var appUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var isCheckingUpdateInfo by remember { mutableStateOf(false) }

    var downloadedApkFile by remember { mutableStateOf<File?>(null) }
    var showDownloadedInstructionDialog by remember { mutableStateOf(false) }

    var showBookmarks by remember { mutableStateOf(false) }
    var showHighlights by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val readingHistoryListState = rememberLazyListState()
    val searchHistoryListState = rememberLazyListState()
    val ribbonScrollState = rememberScrollState(initial = reader.ribbonScrollOffset)

    LaunchedEffect(ribbonScrollState.value) {
        if (ribbonScrollState.value != reader.ribbonScrollOffset) {
            reader.saveRibbonScrollOffset(ribbonScrollState.value)
        }
    }
    var showBrowser by remember { mutableStateOf(false) }
    val browserListState = rememberLazyListState(
        initialFirstVisibleItemIndex = reader.browserScrollIndex,
        initialFirstVisibleItemScrollOffset = reader.browserScrollOffset
    )

    LaunchedEffect(browserListState.firstVisibleItemIndex, browserListState.firstVisibleItemScrollOffset) {
        if (showBrowser) {
            reader.saveBrowserScrollState(browserListState.firstVisibleItemIndex, browserListState.firstVisibleItemScrollOffset)
        }
    }

    LaunchedEffect(showBrowser) {
        if (showBrowser && reader.browserScrollIndex > 0) {
            if (browserListState.firstVisibleItemIndex != reader.browserScrollIndex) {
                browserListState.scrollToItem(reader.browserScrollIndex, reader.browserScrollOffset)
            }
        }
    }

    var showLobDialog by remember { mutableStateOf(false) }

    LaunchedEffect(reader.closeDialogsRequest) {
        if (reader.closeDialogsRequest > 0) {
            showBrowser = false
            showHistory = false
            showBookmarks = false
            showHighlights = false
            showLobDialog = false
        }
    }
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var isDownloadingLob by remember { mutableStateOf(false) }
    var downloadingBookTitle by remember { mutableStateOf("") }

    var lobTabSource by remember { mutableIntStateOf(0) }
    var gutenbergSearchQuery by remember { mutableStateOf("") }
    var gutenbergResults by remember { mutableStateOf<List<GutenbergBookItem>>(emptyList()) }
    var isSearchingGutenberg by remember { mutableStateOf(false) }
    var downloadingGutenbergId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(lobTabSource, gutenbergSearchQuery) {
        if (lobTabSource == 1) {
            isSearchingGutenberg = true
            if (gutenbergSearchQuery.isNotBlank()) delay(400)
            gutenbergResults = fetchGutenbergBooks(gutenbergSearchQuery)
            isSearchingGutenberg = false
        }
    }

    var splitMode by remember { mutableStateOf(SplitMode.SPLIT_BOTH) }
    var showOcrConfirmDialog by remember { mutableStateOf(false) }
    var showForceOcrDialog by remember { mutableStateOf(false) }
    var ocrDialogMessage by remember { mutableStateOf("") }
    var isZenMode by remember { mutableStateOf(false) }
    var showZenControls by rememberSaveable { mutableStateOf(true) }

    val activeSel = reader.activeSelection
    val activeHighlight = reader.book.activeHighlightMenu ?: reader.vocab.activeHighlightMenu
    val activePane = if (reader.book.activeSelection != null || reader.book.activeHighlightMenu != null) reader.book else reader.vocab

    LaunchedEffect(activePane, activeSel, activeHighlight, reader.tappedHighlightWord) {
        if (activeSel == null && activeHighlight == null) {
            reader.dictSummary = null
            return@LaunchedEffect
        }
        val activeDoc = activePane.doc
        if (activeDoc == null && activeSel != null) {
            reader.dictSummary = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            VocabGenerator.init(ctx)
            val textToLookup: String? = if (activeSel != null && activeDoc != null) {
                val fullText = (activeSel.minPage..activeSel.maxPage).mapNotNull { p ->
                    val pWords = activeDoc.getWords(p) ?: activeDoc.getOrExtractWords(ctx, activePane.uri ?: (if (activePane == reader.book) reader.getBookUri() else null), p)
                    val pRange = activeSel.wordRangeForPage(p, pWords.size)
                    if (pRange != null && pWords.isNotEmpty()) {
                        val (sChar, eChar) = activeSel.charOffsetsForPage(p, pRange)
                        PdfTextHelper.getSelectedText(pWords, pRange.first, pRange.last, sChar, eChar).takeIf { it.isNotBlank() }
                    } else null
                }.joinToString(" ").filter { it >= ' ' || it == '\n' || it == '\t' }.trim()

                val words = fullText.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.size == 1) words[0] else null
            } else if (activeHighlight != null) {
                val tapped = reader.tappedHighlightWord?.filter { it >= ' ' || it == '\n' || it == '\t' }?.trim()
                if (!tapped.isNullOrBlank()) {
                    tapped
                } else {
                    val hlText = activeHighlight.text.filter { it >= ' ' || it == '\n' || it == '\t' }.trim()
                    val hlWords = hlText.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (hlWords.size == 1) hlWords[0] else null
                }
            } else null

            if (!textToLookup.isNullOrBlank()) {
                var summary = VocabGenerator.lookupSynonymsAndAntonyms(textToLookup)
                if (summary == null) {
                    coroutineContext.ensureActive()
                    summary = VocabGenerator.lookupOnlineDictionary(textToLookup)
                }
                coroutineContext.ensureActive()
                withContext(Dispatchers.Main) {
                    reader.dictSummary = summary
                    reader.showDictPopup = (summary != null)
                }
            } else {
                withContext(Dispatchers.Main) {
                    reader.dictSummary = null
                    reader.showDictPopup = false
                }
            }
        }
    }

    val handleTripleTap = {
        if (isZenMode) {
            isZenMode = false
        } else {
            if (reader.vocabMode == VocabMode.BROWSER) {
                reader.vocabMode = reader.previousVocabMode ?: VocabMode.VOCAB_PDF
            }
            showBrowser = false
            isZenMode = true
            showZenControls = false
        }
    }

    val window = (ctx as? Activity)?.window
    LaunchedEffect(isZenMode) {
        if (isZenMode) {
            if (reader.vocabMode == VocabMode.BROWSER) {
                reader.vocabMode = reader.previousVocabMode ?: VocabMode.VOCAB_PDF
            }
            showBrowser = false
        }
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (isZenMode) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    var lastCopiedText by remember { mutableStateOf("") }
    var showGoogleSearchBanner by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            runCatching {
                val clipData = clipboard?.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotBlank()) {
                        lastCopiedText = text
                        showGoogleSearchBanner = true
                    }
                }
            }
        }
        clipboard?.addPrimaryClipChangedListener(listener)
        onDispose {
            clipboard?.removePrimaryClipChangedListener(listener)
        }
    }
    
    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(reader::onBookPicked)
    }
    val pickVocab = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(reader::onVocabPicked)
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(reader::onTreePicked)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${ctx.packageName}")
                    ctx.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    ctx.startActivity(intent)
                }
            }
        } else {
            val perms = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (perms.isNotEmpty()) {
                permissionLauncher.launch(perms.toTypedArray())
            }
        }
    }

    val executeGenerateVocab: (Float) -> Unit = { threshold ->
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val vocabDir = File(downloadsDir, "PdfHelperReader_files")
        val expectedName = reader.expectedVocab ?: "vocab.pdf"
        val currentBookPage = reader.book.page + 1

        isGenerating = true
        genProgressCurrent = 0
        genProgressTotal = 0

        Toast.makeText(ctx, "Generating Vocabulary PDF (score ≤ ${threshold.toInt()})...", Toast.LENGTH_SHORT).show()

        reader.generateVocab(
            pageRange = null,
            maxScoreThreshold = threshold,
            onProgress = { c, t -> 
                genProgressCurrent = c
                genProgressTotal = t
            },
            onDone = { fullResult ->
                try {
                    if (!vocabDir.exists()) vocabDir.mkdirs()
                    val destFile = File(vocabDir, expectedName)
                    if (destFile.exists()) destFile.delete()
                    fullResult.outputPdf.copyTo(destFile, overwrite = true)
                    fullResult.outputPdf.delete()

                    android.media.MediaScannerConnection.scanFile(
                        ctx, arrayOf(destFile.absolutePath), arrayOf("application/pdf"), null
                    )

                    reader.saveVocabPageMap(fullResult.pageMap)

                    val mappedPage = fullResult.pageMap[currentBookPage]
                    val targetPage = if (mappedPage != null) {
                        (mappedPage - 1).coerceAtLeast(0)
                    } else {
                        0
                    }

                    reader.onVocabPicked(Uri.fromFile(destFile), targetPage = targetPage)
                    Toast.makeText(ctx, "Vocab document loaded!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
                }
                isGenerating = false
            },
            onError = {
                isGenerating = false
                Toast.makeText(ctx, "Generation error: ${it.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    val generateVocabAction: () -> Unit = {
        if (reader.book.doc == null) {
            Toast.makeText(ctx, "Please open a book first", Toast.LENGTH_SHORT).show()
        } else {
            showVocabThresholdDialog = true
        }
    }

    if (isZenMode) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    DocumentPanesLayout(
                        reader = reader,
                        splitMode = splitMode,
                        isLandscape = isLandscape,
                        isZenMode = true,
                        showZenControls = showZenControls,
                        onToggleZenControls = { showZenControls = !showZenControls },
                        onExitZen = { isZenMode = false },
                        onTripleTap = handleTripleTap,
                        onToggleSplitMode = {
                            splitMode = when (splitMode) {
                                SplitMode.SPLIT_BOTH -> SplitMode.ONLY_BOOK
                                SplitMode.ONLY_BOOK -> SplitMode.ONLY_VOCAB
                                SplitMode.ONLY_VOCAB -> SplitMode.SPLIT_BOTH
                            }
                        },
                        onSetSplitMode = {
                            splitMode = it
                            showGoogleSearchBanner = false
                        },
                        pickBook = pickBook,
                        pickVocab = pickVocab,
                        pickTree = pickTree,
                        isGenerating = isGenerating,
                        generatedFile = generatedFile,
                        genProgressCurrent = genProgressCurrent,
                        genProgressTotal = genProgressTotal,
                        onGenerateVocab = generateVocabAction,
                        onCancelVocab = { reader.cancelVocab(); isGenerating = false },
                        bookListState = bookListState,
                        vocabListState = vocabListState,
                        browserListState = browserListState,
                        onOpenFullBrowser = { showBrowser = true }
                    )
                }

                if (showGoogleSearchBanner && lastCopiedText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 32.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .padding(8.dp),
                            color = MaterialTheme.colorScheme.inverseSurface,
                            shape = MaterialTheme.shapes.medium,
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Search \"${lastCopiedText.take(25)}\" in Google?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.inverseOnSurface,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { showGoogleSearchBanner = false }) {
                                        Text("Dismiss", color = MaterialTheme.colorScheme.inverseOnSurface)
                                    }
                                    Button(onClick = {
                                        reader.vocabMode = VocabMode.GOOGLE_WEB
                                        reader.searchGoogle(lastCopiedText)
                                        splitMode = SplitMode.SPLIT_BOTH
                                        showGoogleSearchBanner = false
                                    }) {
                                        Text("Google")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    Surface(Modifier.fillMaxSize()) {
        if (showAppInfoDialog) {
            val currentVer = remember { getCurrentAppVersionName(ctx) }
            val uriHandler = LocalUriHandler.current
            val repoUrl = "https://github.com/ohidsajol/PdfHelperReader"
            AlertDialog(
                onDismissRequest = { showAppInfoDialog = false },
                title = { Text("App & Developer Info", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                val displayVer = if (currentVer.startsWith("v")) currentVer else "v$currentVer"
                                Text("PdfHelperReader ($displayVer)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.height(2.dp))
                                Text("Advanced Dual-Pane Document & Vocabulary Reader", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f))
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Developer:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text("Ohid Sajol", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("GitHub Repository:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        try {
                                            uriHandler.openUri(repoUrl)
                                        } catch (e: Exception) {
                                            try {
                                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repoUrl)).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                })
                                            } catch (_: Exception) {}
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "github.com/ohidsajol/PdfHelperReader",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            textDecoration = TextDecoration.Underline,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "↗",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("About Application:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(
                                text = "PdfHelperReader is an advanced document and language study reader built for deep reading, research, and vocabulary acquisition. Key features include:\n\n" +
                                    "• Synchronized Dual-Pane Reading: Compare translations, study sidecars, or view multiple sections side-by-side with independent or synced scrolling.\n" +
                                    "• Auto-Vocabulary PDF Generator: Instantly generate sidecar vocabulary study PDFs with word frequencies, CEFR levels, and contextual definitions.\n" +
                                    "• 964k-Word Offline Dictionary: Instant lookup for definitions, inflections, pronunciation, and usage without an internet connection.\n" +
                                    "• Smooth Pinch-to-Zoom: Two-finger zoom anchored directly around each pane's center.\n" +
                                    "• Multi-Page Highlights & Annotations: Seamless text selection across page boundaries with unified highlight management.\n" +
                                    "• On-Device ML Kit OCR: Extract text from scanned PDFs and images, with Force OCR override for text-over-image documents.\n" +
                                    "• Project Gutenberg Catalog: Search and download from over 70,000 free classic books directly within the app.\n" +
                                    "• Intelligent Cache & Storage: Auto-manages downloaded books and temporary renders (500 MB quota) to keep storage footprint minimal.\n" +
                                    "• In-App GitHub Updates: Check for and install new releases directly from GitHub.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAppInfoDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }

        if (showBookmarks) {
            AlertDialog(
                onDismissRequest = { showBookmarks = false },
                title = { Text("Bookmarks") },
                text = {
                    if (reader.bookmarks.isEmpty()) {
                        Text("No bookmarks added yet.")
                    } else {
                        LazyColumn {
                            items(reader.bookmarks) { b ->
                                val dateStr = remember(b.createdTimeMs, b.id) {
                                    val time = if (b.createdTimeMs > 0) b.createdTimeMs else b.id
                                    java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            reader.setBookPage(b.bookPage)
                                            reader.setVocabPage(b.vocabPage)
                                            showBookmarks = false
                                        }
                                        .padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(b.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(2.dp))
                                        Text(dateStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        val snippetText = b.snippet ?: ""
                                        if (snippetText.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "\"${snippetText}\"",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 4,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { reader.removeBookmark(b) }) {
                                        Text("Del", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showBookmarks = false }) { Text("Close") } }
            )
        }

        if (showHighlights) {
            HighlightsDialog(
                reader = reader,
                onDismiss = { showHighlights = false },
                onJumpToPage = { targetPage ->
                    reader.setBookPage(targetPage)
                    scope.launch {
                        bookListState.scrollToItem(targetPage, 0)
                    }
                }
            )
        }

        if (showLobDialog) {
            val catalog = remember { loadOnlineBooksCatalog(ctx) }

            AlertDialog(
                onDismissRequest = { if (!isDownloadingLob && downloadingGutenbergId == null) showLobDialog = false },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = lobTabSource == 0,
                            onClick = { lobTabSource = 0 },
                            label = { Text("Online Books") }
                        )
                        FilterChip(
                            selected = lobTabSource == 1,
                            onClick = { lobTabSource = 1 },
                            label = { Text("Gutenberg Books") }
                        )
                    }
                },
                text = {
                    Column {
                        if (lobTabSource == 0) {
                            if (isDownloadingLob) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = downloadingBookTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Downloading to Downloads/PdfHelperReader_files...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    catalog.forEach { group ->
                                        item {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = MaterialTheme.shapes.small,
                                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                                            ) {
                                                Text(
                                                    text = group.author,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                        items(group.books) { book ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        isDownloadingLob = true
                                                        downloadingBookTitle = book.title
                                                        scope.launch {
                                                            downloadOnlineBook(
                                                                ctx = ctx,
                                                                book = book,
                                                                onDone = { file ->
                                                                    isDownloadingLob = false
                                                                    reader.onBookPicked(Uri.fromFile(file))
                                                                    Toast.makeText(ctx, "${book.title} loaded!", Toast.LENGTH_SHORT).show()
                                                                },
                                                                onError = { err ->
                                                                    isDownloadingLob = false
                                                                    Toast.makeText(ctx, "Download error: ${err.message}", Toast.LENGTH_LONG).show()
                                                                }
                                                            )
                                                        }
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(book.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                                    Text(group.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                                }
                                                Text("Download ➔", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                OutlinedTextField(
                                    value = gutenbergSearchQuery,
                                    onValueChange = { gutenbergSearchQuery = it },
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    placeholder = { Text("Search Gutenberg title or author...") },
                                    singleLine = true,
                                    trailingIcon = {
                                        if (gutenbergSearchQuery.isNotBlank()) {
                                            IconButton(onClick = { gutenbergSearchQuery = "" }) {
                                                Text("✕")
                                            }
                                        }
                                    }
                                )

                                if (isSearchingGutenberg) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(200.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                } else if (gutenbergResults.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(200.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("No Gutenberg books found.", color = MaterialTheme.colorScheme.outline)
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(gutenbergResults) { b ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                    Text(b.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    Text(b.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    if (downloadingGutenbergId == b.id) {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(24.dp).padding(2.dp),
                                                            strokeWidth = 2.dp
                                                        )
                                                    } else {
                                                        FilledTonalButton(
                                                            onClick = {
                                                                downloadingGutenbergId = b.id
                                                                scope.launch {
                                                                    downloadGutenbergBook(ctx, b, isEpub = true, onDone = { file ->
                                                                        downloadingGutenbergId = null
                                                                        reader.onBookPicked(Uri.fromFile(file))
                                                                        Toast.makeText(ctx, "${b.title} loaded!", Toast.LENGTH_SHORT).show()
                                                                    }, onError = { err ->
                                                                        downloadingGutenbergId = null
                                                                        Toast.makeText(ctx, "Download error: ${err.message}", Toast.LENGTH_LONG).show()
                                                                    })
                                                                }
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                            modifier = Modifier.height(28.dp)
                                                        ) {
                                                            Text("EPUB", style = MaterialTheme.typography.labelSmall)
                                                        }

                                                        Button(
                                                            onClick = {
                                                                downloadingGutenbergId = b.id
                                                                scope.launch {
                                                                    downloadGutenbergBook(ctx, b, isEpub = false, onDone = { file ->
                                                                        downloadingGutenbergId = null
                                                                        reader.onBookPicked(Uri.fromFile(file))
                                                                        Toast.makeText(ctx, "${b.title} loaded!", Toast.LENGTH_SHORT).show()
                                                                    }, onError = { err ->
                                                                        downloadingGutenbergId = null
                                                                        Toast.makeText(ctx, "Download error: ${err.message}", Toast.LENGTH_LONG).show()
                                                                    })
                                                                }
                                                            },
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                            modifier = Modifier.height(28.dp)
                                                        ) {
                                                            Text("PDF", style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { showLobDialog = false },
                        enabled = !isDownloadingLob && downloadingGutenbergId == null
                    ) { Text("Close") }
                }
            )
        }

        val currentVer = remember { getCurrentAppVersionName(ctx) }
        if (showUpdateInfoDialog && appUpdateInfo != null) {
            val info = appUpdateInfo!!
            AlertDialog(
                onDismissRequest = { showUpdateInfoDialog = false },
                title = { Text("App Update") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            color = if (info.isNewerAvailable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (info.isNewerAvailable) "New Update Available! (${info.latestTag})" else "Your App is Up to Date (${info.latestTag})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (info.isNewerAvailable) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Text("Installed Version: $currentVer", style = MaterialTheme.typography.bodyMedium)
                        Text("GitHub Latest Tag: ${info.latestTag}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        if (info.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Release Notes:\n${info.releaseNotes.take(300)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showUpdateInfoDialog = false
                            if (info.apkDownloadUrl.isNotBlank()) {
                                isAppUpdating = true
                                updateProgressCurrent = 0
                                updateProgressTotal = 0
                                scope.launch {
                                    downloadAppUpdateApk(
                                        ctx = ctx,
                                        downloadUrl = info.apkDownloadUrl,
                                        onProgress = { current, total ->
                                            updateProgressCurrent = current
                                            updateProgressTotal = total
                                        },
                                        onDone = { apkFile ->
                                            isAppUpdating = false
                                            downloadedApkFile = apkFile
                                            showDownloadedInstructionDialog = true
                                        },
                                        onError = { err ->
                                            isAppUpdating = false
                                            Toast.makeText(ctx, "Download error: ${err.message}", Toast.LENGTH_LONG).show()
                                        }
                                    )
                                }
                            } else {
                                Toast.makeText(ctx, "No APK asset found in release.", Toast.LENGTH_LONG).show()
                            }
                        }
                    ) {
                        Text(if (info.isNewerAvailable) "Update Now" else "Re-install $currentVer")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateInfoDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showDownloadedInstructionDialog && downloadedApkFile != null) {
            val apkFile = downloadedApkFile!!
            AlertDialog(
                onDismissRequest = { showDownloadedInstructionDialog = false },
                title = { Text("Update Downloaded!") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Saved to Downloads/${apkFile.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        Text("How to Install:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "1. Simply uninstall the current app.\n2. Tap ${apkFile.name} in your Downloads folder to install.\n\nAll your reading history, bookmarks, and settings are preserved on disk!",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showDownloadedInstructionDialog = false }
                    ) {
                        Text("OK")
                    }
                }
            )
        }

        if (isAppUpdating) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("App Update") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Downloading latest release update from GitHub...", style = MaterialTheme.typography.bodyMedium)
                        if (updateProgressTotal > 0) {
                            val pct = ((updateProgressCurrent.toFloat() / updateProgressTotal.toFloat()) * 100).toInt()
                            val downloadedMb = String.format(java.util.Locale.US, "%.1f", updateProgressCurrent / (1024f * 1024f))
                            val totalMb = String.format(java.util.Locale.US, "%.1f", updateProgressTotal / (1024f * 1024f))
                            Text("Progress: $pct% ($downloadedMb MB / $totalMb MB)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            LinearProgressIndicator(
                                progress = { updateProgressCurrent.toFloat() / updateProgressTotal.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(8.dp)
                            )
                        }
                    }
                },
                confirmButton = {}
            )
        }

        if (showHistory) {
            AlertDialog(
                onDismissRequest = { showHistory = false },
                title = {
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("History", style = MaterialTheme.typography.titleLarge)
                            if (reader.historyTab == 1 && reader.searchHistory.isNotEmpty()) {
                                TextButton(
                                    onClick = { reader.clearSearchHistory() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Clear All", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        TabRow(
                            selectedTabIndex = reader.historyTab,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Tab(
                                selected = reader.historyTab == 0,
                                onClick = { reader.setAndSaveHistoryTab(0) },
                                text = { Text("Reading") }
                            )
                            Tab(
                                selected = reader.historyTab == 1,
                                onClick = { reader.setAndSaveHistoryTab(1) },
                                text = { Text("Search History") }
                            )
                        }
                    }
                },
                text = {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp)) {
                        if (reader.historyTab == 0) {
                            if (reader.history.isEmpty()) {
                                Text("No reading history yet.", modifier = Modifier.padding(vertical = 16.dp))
                            } else {
                                LazyColumn(
                                    state = readingHistoryListState,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(reader.history) { h ->
                                        val isTutorial = h.name == "Tutorial" || h.uri.contains("PdfHelperReader_Tutorial.pdf")
                                        val firstOpenedStr = remember(h.firstOpenedMs, h.timeMs) {
                                            val t = if (h.firstOpenedMs > 0) h.firstOpenedMs else h.timeMs
                                            java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
                                        }
                                        val lastClosedStr = remember(h.lastClosedMs, h.timeMs) {
                                            val t = if (h.lastClosedMs > 0) h.lastClosedMs else h.timeMs
                                            java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
                                        }

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    reader.onBookPicked(Uri.parse(h.uri), h.name)
                                                    showHistory = false
                                                }
                                                .padding(vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(h.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Spacer(Modifier.height(2.dp))
                                                Text("First Opened: $firstOpenedStr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                Text("Last Closed: $lastClosedStr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (!isTutorial) {
                                                    TextButton(onClick = {
                                                        val deleted = reader.deleteAssociatedVocab(h)
                                                        if (deleted) {
                                                            Toast.makeText(ctx, "Vocab file deleted", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(ctx, "No vocab file found to delete", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }) {
                                                        Text("Del vocab", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                    TextButton(onClick = { reader.removeFromHistory(h) }) {
                                                        Text("Del", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            if (reader.searchHistory.isEmpty()) {
                                Text("No search history yet.", modifier = Modifier.padding(vertical = 16.dp))
                            } else {
                                LazyColumn(
                                    state = searchHistoryListState,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(reader.searchHistory) { s ->
                                        val timeStr = remember(s.timeMs) {
                                            java.text.SimpleDateFormat("MMM dd, yyyy · HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.timeMs))
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    reader.searchGoogle(s.query)
                                                    showHistory = false
                                                }
                                                .padding(vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = s.query,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (s.formattedQuery != s.query && s.formattedQuery.isNotBlank()) {
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        text = s.formattedQuery,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Spacer(Modifier.height(2.dp))
                                                Text(
                                                    text = timeStr,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            TextButton(
                                                onClick = { reader.removeFromSearchHistory(s) },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("Del", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } }
            )
        }

        if (showBrowser) {
            FileBrowserDialog(
                reader = reader,
                listState = browserListState,
                onToggleMinMax = {
                    showBrowser = false
                    reader.previousVocabMode = reader.vocabMode
                    reader.vocabMode = VocabMode.BROWSER
                    if (splitMode != SplitMode.SPLIT_BOTH) {
                        splitMode = SplitMode.SPLIT_BOTH
                    }
                },
                onDismiss = { showBrowser = false },
                onFileSelected = { file ->
                    val targetUri = file.uri ?: Uri.fromFile(File(file.path))
                    reader.onBookPicked(targetUri, file.name)
                    showBrowser = false
                }
            )
        }

        val startOcr: (Boolean) -> Unit = { force ->
            isOcrGenerating = true
            ocrProgressCurrent = 0
            ocrProgressTotal = 0
            reader.generateOcr(
                forceOcr = force,
                onProgress = { c, t ->
                    ocrProgressCurrent = c
                    ocrProgressTotal = t
                },
                onDone = { generatedTempFile ->
                    try {
                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val ocrDir = File(downloadsDir, "PdfHelperReader_files")
                        if (!ocrDir.exists()) ocrDir.mkdirs()
                        
                        val originalName = reader.book.name?.removeSuffix(".pdf") ?: "book"
                        val baseName = originalName.removeSuffix("_ocr")
                        val expectedName = "${baseName}_ocr.pdf"
                        val destFile = File(ocrDir, expectedName)
                        if (destFile.exists()) destFile.delete()
                        
                        generatedTempFile.copyTo(destFile, overwrite = true)
                        generatedTempFile.delete()
                        
                        android.media.MediaScannerConnection.scanFile(
                            ctx, arrayOf(destFile.absolutePath), arrayOf("application/pdf"), null
                        )
                        
                        reader.onBookPicked(Uri.fromFile(destFile))
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    isOcrGenerating = false
                },
                onError = {
                    isOcrGenerating = false
                    Toast.makeText(ctx, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                }
            )
        }

        Box(Modifier.fillMaxSize()) {
            if (showVocabThresholdDialog) {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val vocabDir = File(downloadsDir, "PdfHelperReader_files")
                val expectedName = reader.expectedVocab ?: "vocab.pdf"
                val existingFile = File(vocabDir, expectedName)
                val prefs = remember { ctx.getSharedPreferences("dual_pdf_reader", Context.MODE_PRIVATE) }
                val savedThreshold = try {
                    prefs.getFloat("last_vocab_threshold", 37f)
                } catch (_: Exception) {
                    try {
                        prefs.getString("last_vocab_threshold", null)?.toFloatOrNull() ?: 37f
                    } catch (_: Exception) {
                        37f
                    }
                }

                VocabThresholdDialog(
                    initialThreshold = savedThreshold,
                    existingFileExists = existingFile.exists(),
                    onDismiss = { showVocabThresholdDialog = false },
                    onGenerate = { selectedThreshold ->
                        showVocabThresholdDialog = false
                        prefs.edit().remove("last_vocab_threshold").putFloat("last_vocab_threshold", selectedThreshold).apply()
                        executeGenerateVocab(selectedThreshold)
                    },
                    onOpenExisting = {
                        showVocabThresholdDialog = false
                        Toast.makeText(ctx, "Opening existing vocab file...", Toast.LENGTH_SHORT).show()
                        reader.onVocabPicked(Uri.fromFile(existingFile))
                    }
                )
            }

            if (showOcrConfirmDialog && !reader.isBookEpub && !reader.isCurrentBookEpub()) {
                AlertDialog(
                    onDismissRequest = { showOcrConfirmDialog = false },
                    title = { Text("Confirm OCR") },
                    text = { Text("Apply OCR to this PDF? This will process the document and create a searchable text layer in a new PDF saved to Downloads/PdfHelperReader_files.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showOcrConfirmDialog = false
                            startOcr(false)
                        }) { Text("Confirm") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showOcrConfirmDialog = false }) { Text("Cancel") }
                    }
                )
            }

            if (showForceOcrDialog && !reader.isBookEpub && !reader.isCurrentBookEpub()) {
                AlertDialog(
                    onDismissRequest = { showForceOcrDialog = false },
                    title = { Text("Document Does Not Need OCR") },
                    text = {
                        Text(
                            (if (ocrDialogMessage.isNotBlank()) "$ocrDialogMessage\n\n" else "") +
                            "Would you like to force OCR anyway? This will recognize text on all pages and create a new searchable PDF in Downloads/PdfHelperReader_files."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showForceOcrDialog = false
                            startOcr(true)
                        }) { Text("Force OCR") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showForceOcrDialog = false }) { Text("Cancel") }
                    }
                )
            }

            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().height(topBarHeight).horizontalScroll(ribbonScrollState).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    var btnIndex = 0

                    @Composable
                    fun RibbonBtn(
                        onClick: () -> Unit,
                        enabled: Boolean = true,
                        text: String,
                        textColor: androidx.compose.ui.graphics.Color? = null,
                        content: (@Composable () -> Unit)? = null
                    ) {
                        val currIdx = btnIndex++
                        val defaultFontCol = MaterialTheme.colorScheme.primary
                        val tealFontCol = androidx.compose.ui.graphics.Color(0xFF00796B)

                        val fontColor = textColor ?: if (currIdx % 2 == 0) {
                            defaultFontCol
                        } else {
                            tealFontCol
                        }

                        TextButton(
                            onClick = onClick,
                            enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = fontColor,
                                disabledContentColor = fontColor.copy(alpha = 0.35f)
                            )
                        ) {
                            if (content != null) {
                                content()
                            } else {
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (enabled) fontColor else fontColor.copy(alpha = 0.35f)
                                )
                            }
                        }
                    }

                    RibbonBtn(
                        enabled = reader.book.doc != null,
                        onClick = {
                            reader.addBookmark()
                            Toast.makeText(ctx, "Bookmarked!", Toast.LENGTH_SHORT).show()
                        },
                        text = "B"
                    )

                    RibbonBtn(
                        onClick = { showBookmarks = true },
                        text = "BL"
                    )

                    RibbonBtn(
                        onClick = { showHighlights = true },
                        text = "HL"
                    )

                    RibbonBtn(
                        onClick = {
                            splitMode = when (splitMode) {
                                SplitMode.SPLIT_BOTH -> SplitMode.ONLY_BOOK
                                SplitMode.ONLY_BOOK -> SplitMode.ONLY_VOCAB
                                SplitMode.ONLY_VOCAB -> SplitMode.SPLIT_BOTH
                            }
                        },
                        text = when (splitMode) {
                            SplitMode.SPLIT_BOTH -> "Both"
                            SplitMode.ONLY_BOOK -> "Book"
                            SplitMode.ONLY_VOCAB -> "Vocab"
                        }
                    )

                    RibbonBtn(
                        onClick = {
                            if (reader.vocabMode == VocabMode.BROWSER) {
                                reader.vocabMode = reader.previousVocabMode ?: VocabMode.VOCAB_PDF
                            }
                            showBrowser = false
                            isZenMode = true
                            showZenControls = true
                        },
                        text = "Z"
                    )

                    RibbonBtn(
                        enabled = reader.book.doc != null && isVocabPdfActive && isContinuousMode && isPdfViewMode,
                        onClick = {
                            isSyncedReading = !isSyncedReading
                            if (isSyncedReading && isVocabPdfActive) {
                                val bookDoc = reader.book.doc
                                val vocabDoc = reader.vocab.doc
                                if (bookDoc != null && vocabDoc != null && bookDoc.pageCount > 0) {
                                    val currentBookPage = reader.book.page + 1
                                    val mappedPage = reader.getMappedVocabPage(currentBookPage)
                                    val targetVocabPage = if (mappedPage != null) {
                                        (mappedPage - 1).coerceIn(0, vocabDoc.pageCount - 1)
                                    } else {
                                        val factor = vocabDoc.pageCount.toFloat() / bookDoc.pageCount.toFloat()
                                        ((currentBookPage * factor) - 1).toInt().coerceIn(0, vocabDoc.pageCount - 1)
                                    }
                                    reader.setVocabPage(targetVocabPage)
                                    scope.launch {
                                        vocabListState.scrollToItem(targetVocabPage, 0)
                                    }
                                }
                            }
                            Toast.makeText(ctx, if (isSyncedReading) "Synced Reading ON (Synced!)" else "Synced Reading OFF", Toast.LENGTH_SHORT).show()
                        },
                        text = if (isSyncedReading && isVocabPdfActive && isContinuousMode && isPdfViewMode) "SR: On" else "SR: Off"
                    )

                    RibbonBtn(
                        enabled = reader.book.doc != null && isContinuousMode && isPdfViewMode,
                        onClick = {
                            isAutoScroll = !isAutoScroll
                            Toast.makeText(ctx, if (isAutoScroll) "Auto Scroll ON (1 height / 5 min)" else "Auto Scroll OFF", Toast.LENGTH_SHORT).show()
                        },
                        text = if (isAutoScroll && isContinuousMode && isPdfViewMode) "AS: On" else "AS: Off"
                    )

                    RibbonBtn(
                        enabled = reader.book.doc != null && isVocabPdfActive,
                        onClick = {
                            val bookDoc = reader.book.doc
                            val vocabDoc = reader.vocab.doc
                            if (bookDoc != null && vocabDoc != null && bookDoc.pageCount > 0) {
                                val currentBookPage = reader.book.page + 1
                                val mappedPage = reader.getMappedVocabPage(currentBookPage)
                                val targetVocabPage = if (mappedPage != null) {
                                    (mappedPage - 1).coerceIn(0, vocabDoc.pageCount - 1)
                                } else {
                                    val factor = vocabDoc.pageCount.toFloat() / bookDoc.pageCount.toFloat()
                                    ((currentBookPage * factor) - 1).toInt().coerceIn(0, vocabDoc.pageCount - 1)
                                }
                                reader.setVocabPage(targetVocabPage)
                                Toast.makeText(ctx, "Synced Vocab to page ${targetVocabPage + 1}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(ctx, "Open both book and vocab documents to sync", Toast.LENGTH_SHORT).show()
                            }
                        },
                        text = "Sync"
                    )

                    RibbonBtn(
                        onClick = {
                            reader.vocabMode = when (reader.vocabMode) {
                                VocabMode.VOCAB_PDF -> {
                                    if (splitMode != SplitMode.SPLIT_BOTH) {
                                        splitMode = SplitMode.SPLIT_BOTH
                                    }
                                    VocabMode.GOOGLE_WEB
                                }
                                VocabMode.GOOGLE_WEB -> {
                                    isSyncedReading = false
                                    VocabMode.VOCAB_PDF
                                }
                                VocabMode.BROWSER -> {
                                    VocabMode.VOCAB_PDF
                                }
                            }
                            if (reader.vocabMode == VocabMode.GOOGLE_WEB) {
                                isSyncedReading = false
                            }
                        },
                        text = when (reader.vocabMode) {
                            VocabMode.VOCAB_PDF -> "G:Vocab"
                            VocabMode.GOOGLE_WEB -> "G:Google"
                            VocabMode.BROWSER -> "G:Files"
                        }
                    )

                    RibbonBtn(
                        onClick = { reader.toggleDarkMode() },
                        text = "T"
                    )

                    RibbonBtn(
                        onClick = {
                            reader.book.readingMode = when (reader.book.readingMode) {
                                ReadingMode.PAGE_BY_PAGE -> ReadingMode.CONTINUOUS
                                ReadingMode.CONTINUOUS -> {
                                    isAutoScroll = false
                                    isSyncedReading = false
                                    ReadingMode.PAGE_BY_PAGE
                                }
                            }
                            reader.vocab.readingMode = reader.book.readingMode
                        },
                        text = when (reader.book.readingMode) {
                            ReadingMode.PAGE_BY_PAGE -> "R:P"
                            ReadingMode.CONTINUOUS -> "R:C"
                        }
                    )

                    RibbonBtn(
                        onClick = {
                            reader.book.viewMode = when (reader.book.viewMode) {
                                ViewMode.PDF_VIEW -> {
                                    isAutoScroll = false
                                    isSyncedReading = false
                                    ViewMode.TEXT_VIEW
                                }
                                ViewMode.TEXT_VIEW -> ViewMode.PDF_VIEW
                            }
                            reader.vocab.viewMode = reader.book.viewMode
                        },
                        text = when (reader.book.viewMode) {
                            ViewMode.PDF_VIEW -> "V:PDF"
                            ViewMode.TEXT_VIEW -> "V:TXT"
                        }
                    )

                    RibbonBtn(
                        onClick = { showHistory = true },
                        text = "H"
                    )

                    if (reader.book.doc != null) {
                        val isEpub = reader.isBookEpub || reader.isCurrentBookEpub()
                        RibbonBtn(
                            enabled = !isEpub,
                            onClick = {
                                scope.launch {
                                    val statusMsg = reader.checkOcrStatus()
                                    if (statusMsg != null) {
                                        ocrDialogMessage = statusMsg
                                        showForceOcrDialog = true
                                    } else {
                                        showOcrConfirmDialog = true
                                    }
                                }
                            },
                            text = "O"
                        )
                    }

                    RibbonBtn(
                        onClick = { showLobDialog = true },
                        text = "LOB"
                    )

                    RibbonBtn(
                        onClick = {
                            if (reader.vocabMode == VocabMode.BROWSER) {
                                reader.vocabMode = reader.previousVocabMode ?: VocabMode.VOCAB_PDF
                                showBrowser = true
                            } else {
                                showBrowser = true
                                if (!reader.browserHasScanned) {
                                    reader.scanBrowserFiles()
                                }
                            }
                        },
                        text = "Browse"
                    )

                    RibbonBtn(
                        onClick = { pickBook.launch(DOCUMENT_MIMES) },
                        text = "OB"
                    )

                    RibbonBtn(
                        onClick = { pickVocab.launch(DOCUMENT_MIMES) },
                        text = "OV"
                    )

                    RibbonBtn(
                        onClick = {
                            if (reader.book.doc == null) {
                                Toast.makeText(ctx, "Please open a book first", Toast.LENGTH_SHORT).show()
                            } else {
                                generateVocabAction()
                            }
                        },
                        text = "LV"
                    )

                    RibbonBtn(
                        onClick = {
                            if (isAppUpdating || isCheckingUpdateInfo) return@RibbonBtn
                            isCheckingUpdateInfo = true
                            scope.launch {
                                try {
                                    val currentVer = getCurrentAppVersionName(ctx)
                                    appUpdateInfo = checkAppUpdateInfo(ctx, currentVer)
                                    showUpdateInfoDialog = true
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, "Update check failed: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isCheckingUpdateInfo = false
                                }
                            }
                        },
                        text = "Update",
                        content = {
                            if (isCheckingUpdateInfo) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Update", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    )

                    RibbonBtn(
                        onClick = { showAppInfoDialog = true },
                        text = "App Info"
                    )
                }
                HorizontalDivider()

                DocumentPanesLayout(
                    reader = reader,
                    splitMode = splitMode,
                    isLandscape = isLandscape,
                    isZenMode = false,
                    showZenControls = showZenControls,
                    onToggleZenControls = { showZenControls = !showZenControls },
                    onExitZen = { isZenMode = false },
                    onTripleTap = handleTripleTap,
                    onToggleSplitMode = {
                        splitMode = when (splitMode) {
                            SplitMode.SPLIT_BOTH -> SplitMode.ONLY_BOOK
                            SplitMode.ONLY_BOOK -> SplitMode.ONLY_VOCAB
                            SplitMode.ONLY_VOCAB -> SplitMode.SPLIT_BOTH
                        }
                    },
                    onSetSplitMode = {
                        splitMode = it
                        showGoogleSearchBanner = false
                    },
                    pickBook = pickBook,
                    pickVocab = pickVocab,
                    pickTree = pickTree,
                    isGenerating = isGenerating,
                    generatedFile = generatedFile,
                    genProgressCurrent = genProgressCurrent,
                    genProgressTotal = genProgressTotal,
                    onGenerateVocab = generateVocabAction,
                    onCancelVocab = { reader.cancelVocab(); isGenerating = false },
                    bookListState = bookListState,
                    vocabListState = vocabListState,
                    browserListState = browserListState,
                    onOpenFullBrowser = { showBrowser = true }
                )
            }

            if (isOcrGenerating) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Card {
                        Column(Modifier.padding(24.dp).fillMaxWidth(0.8f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("OCRing PDF...", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(16.dp))
                            if (ocrProgressTotal > 0) {
                                val progress = ocrProgressCurrent.toFloat() / ocrProgressTotal
                                val percentage = (progress * 100).toInt()
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(8.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text("Applying OCR: $percentage% ($ocrProgressCurrent/$ocrProgressTotal)", style = MaterialTheme.typography.bodySmall)
                            } else {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text("Applying OCR: 0%", style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = {
                                reader.cancelOcr()
                                isOcrGenerating = false
                            }) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }

            if (showGoogleSearchBanner && lastCopiedText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 32.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .padding(8.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = MaterialTheme.shapes.medium,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Search \"${lastCopiedText.take(25)}\" in Google?",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { showGoogleSearchBanner = false }) {
                                    Text("Dismiss", color = MaterialTheme.colorScheme.inverseOnSurface)
                                }
                                Button(onClick = {
                                    reader.vocabMode = VocabMode.GOOGLE_WEB
                                    reader.searchGoogle(lastCopiedText)
                                    splitMode = SplitMode.SPLIT_BOTH
                                    showGoogleSearchBanner = false
                                }) {
                                    Text("Google")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DocumentPanesLayout(
    modifier: Modifier = Modifier,
    reader: Reader,
    splitMode: SplitMode,
    isLandscape: Boolean,
    isZenMode: Boolean = false,
    showZenControls: Boolean = true,
    onToggleZenControls: () -> Unit = {},
    onExitZen: () -> Unit = {},
    onTripleTap: () -> Unit = {},
    onToggleSplitMode: () -> Unit = {},
    onSetSplitMode: (SplitMode) -> Unit = {},
    pickBook: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    pickVocab: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    pickTree: androidx.activity.result.ActivityResultLauncher<Uri?>,
    isGenerating: Boolean,
    generatedFile: File?,
    genProgressCurrent: Int,
    genProgressTotal: Int,
    onGenerateVocab: () -> Unit,
    onCancelVocab: () -> Unit,
    bookListState: androidx.compose.foundation.lazy.LazyListState? = null,
    vocabListState: androidx.compose.foundation.lazy.LazyListState? = null,
    browserListState: androidx.compose.foundation.lazy.LazyListState? = null,
    onOpenFullBrowser: () -> Unit = {}
) {
    val ctx = LocalContext.current

    val emptyBookCard: @Composable () -> Unit = {
        MessageCard(
            title = if (reader.book.message != null) "Could not open the book" else "No book open",
            body = reader.book.message ?: "Choose a PDF to start reading."
        ) {
            Button(onClick = { pickBook.launch(DOCUMENT_MIMES) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open book")
            }
        }
    }

    val emptyVocabCard: @Composable () -> Unit = {
        if (isGenerating && generatedFile == null) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Card {
                    Column(Modifier.padding(24.dp).fillMaxWidth(0.8f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Generating Vocabulary PDF...", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(16.dp))
                        if (genProgressTotal > 0) {
                            val progress = genProgressCurrent.toFloat() / genProgressTotal
                            val percentage = (progress * 100).toInt()
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Generating Vocab PDF: $percentage% ($genProgressCurrent/$genProgressTotal)", style = MaterialTheme.typography.bodySmall)
                        } else {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Generating Vocab PDF: 0%", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = onCancelVocab) {
                            Text("Cancel")
                        }
                    }
                }
            }
        } else if (reader.book.doc == null) {
            MessageCard("Vocabulary", "Open a book first; its \"_vocab.pdf\" sidecar loads automatically.") {}
        } else {
            MessageCard(
                title = "Vocabulary PDF unavailable",
                body = (reader.vocab.message ?: "Not loaded.") +
                    "\nAndroid only lets the app see files you grant. Pick the vocab file once, " +
                    "or grant access to the folder so it is found automatically."
            ) {
                Button(onClick = onGenerateVocab, modifier = Modifier.fillMaxWidth()) {
                    Text("Generate Vocab PDF")
                }
                Button(onClick = { pickVocab.launch(DOCUMENT_MIMES) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose vocab PDF")
                }
                OutlinedButton(onClick = { pickTree.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant folder access")
                }
            }
        }
    }

    if (isLandscape) {
        Row(modifier) {
            if (splitMode != SplitMode.ONLY_VOCAB) {
                PdfPane(
                    "BOOK",
                    reader.book,
                    if (splitMode == SplitMode.SPLIT_BOTH) Modifier.weight(1f).fillMaxHeight() else Modifier.fillMaxSize(),
                    reader::setBookPage,
                    reader.isDarkMode,
                    isZenMode,
                    showZenControls,
                    onToggleZenControls,
                    reader,
                    splitMode,
                    onExitZen,
                    onTripleTap,
                    onToggleSplitMode,
                    onSetSplitMode,
                    emptyBookCard,
                    externalListState = bookListState
                )
            }
            if (splitMode == SplitMode.SPLIT_BOTH) {
                VerticalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
            }
            if (splitMode != SplitMode.ONLY_BOOK) {
                PdfPane(
                    "VOCAB",
                    reader.vocab,
                    if (splitMode == SplitMode.SPLIT_BOTH) Modifier.weight(1f).fillMaxHeight() else Modifier.fillMaxSize(),
                    reader::setVocabPage,
                    reader.isDarkMode,
                    isZenMode,
                    showZenControls,
                    onToggleZenControls,
                    reader,
                    splitMode,
                    onExitZen,
                    onTripleTap,
                    onToggleSplitMode,
                    onSetSplitMode,
                    emptyVocabCard,
                    externalListState = if (reader.vocabMode == VocabMode.BROWSER) browserListState else vocabListState,
                    onOpenFullBrowser = onOpenFullBrowser
                )
            }
        }
    } else {
        Column(modifier) {
            if (splitMode != SplitMode.ONLY_VOCAB) {
                PdfPane(
                    "BOOK",
                    reader.book,
                    if (splitMode == SplitMode.SPLIT_BOTH) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxSize(),
                    reader::setBookPage,
                    reader.isDarkMode,
                    isZenMode,
                    showZenControls,
                    onToggleZenControls,
                    reader,
                    splitMode,
                    onExitZen,
                    onTripleTap,
                    onToggleSplitMode,
                    onSetSplitMode,
                    emptyBookCard,
                    externalListState = bookListState
                )
            }
            if (splitMode == SplitMode.SPLIT_BOTH) {
                HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
            }
            if (splitMode != SplitMode.ONLY_BOOK) {
                PdfPane(
                    "VOCAB",
                    reader.vocab,
                    if (splitMode == SplitMode.SPLIT_BOTH) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxSize(),
                    reader::setVocabPage,
                    reader.isDarkMode,
                    isZenMode,
                    showZenControls,
                    onToggleZenControls,
                    reader,
                    splitMode,
                    onExitZen,
                    onTripleTap,
                    onToggleSplitMode,
                    onSetSplitMode,
                    emptyVocabCard,
                    externalListState = if (reader.vocabMode == VocabMode.BROWSER) browserListState else vocabListState,
                    onOpenFullBrowser = onOpenFullBrowser
                )
            }
        }
    }
}

@Composable
fun PdfPane(
    label: String,
    pane: Pane,
    modifier: Modifier,
    onPage: (Int) -> Unit,
    isDarkMode: Boolean,
    isZenMode: Boolean = false,
    showZenControls: Boolean = true,
    onToggleZenControls: () -> Unit = {},
    reader: Reader,
    splitMode: SplitMode = SplitMode.SPLIT_BOTH,
    onExitZen: () -> Unit = {},
    onTripleTap: () -> Unit = {},
    onToggleSplitMode: () -> Unit = {},
    onSetSplitMode: (SplitMode) -> Unit = {},
    empty: @Composable () -> Unit,
    externalListState: androidx.compose.foundation.lazy.LazyListState? = null,
    onOpenFullBrowser: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var showPageDialog by remember { mutableStateOf(false) }

    val splitLabel = when (splitMode) {
        SplitMode.SPLIT_BOTH -> "Both"
        SplitMode.ONLY_BOOK -> "Book"
        SplitMode.ONLY_VOCAB -> "Vocab"
    }

    Column(modifier) {
        val isGoogleWebInVocab = (label == "VOCAB" && reader.vocabMode == VocabMode.GOOGLE_WEB)
        val isBrowserInVocab = (label == "VOCAB" && reader.vocabMode == VocabMode.BROWSER)
        if (!isZenMode && !isGoogleWebInVocab && !isBrowserInVocab) {
            PaneBar(label, pane, onPage)
        }
        val boxBg = if (isBrowserInVocab) {
            MaterialTheme.colorScheme.surface
        } else if (isDarkMode) {
            androidx.compose.ui.graphics.Color(0xFF1E1E1E)
        } else {
            androidx.compose.ui.graphics.Color(0xFFF5F5F5)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(boxBg)
                .pointerInput(isZenMode) {
                    detectMultiTapGestures(
                        onDoubleTap = if (isZenMode) { { onToggleZenControls() } } else null,
                        onTripleTap = { onTripleTap() }
                    )
                }
        ) {
            if (isGoogleWebInVocab) {
                GoogleWebView(
                    reader = reader,
                    url = reader.googleSearchQuery,
                    rawQuery = reader.googleRawQuery,
                    searchToken = reader.googleSearchToken,
                    isDarkMode = isDarkMode,
                    onBackToVocab = { reader.vocabMode = VocabMode.VOCAB_PDF }
                )
            } else if (isBrowserInVocab) {
                FileBrowserContent(
                    reader = reader,
                    listState = externalListState ?: rememberLazyListState(),
                    isMinMode = true,
                    onToggleMinMax = {
                        reader.vocabMode = reader.previousVocabMode ?: VocabMode.VOCAB_PDF
                        onOpenFullBrowser()
                    },
                    onDismiss = {},
                    onFileSelected = { file ->
                        val targetUri = file.uri ?: Uri.fromFile(File(file.path))
                        reader.onBookPicked(targetUri, file.name)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val doc = pane.doc
                if (doc != null) {
                    if (pane.viewMode == ViewMode.TEXT_VIEW) {
                        TextView(
                            doc = doc,
                            page = pane.page,
                            isDarkMode = isDarkMode,
                            uri = pane.uri,
                            isZenMode = isZenMode,
                            onToggleZenControls = onToggleZenControls,
                            onTripleTap = onTripleTap
                        )
                    } else if (pane.readingMode == ReadingMode.CONTINUOUS) {
                        ContinuousPageView(
                            doc = doc,
                            isDarkMode = isDarkMode,
                            pane = pane,
                            onPage = onPage,
                            reader = reader,
                            externalListState = externalListState,
                            isZenMode = isZenMode,
                            onToggleZenControls = onToggleZenControls,
                            onTripleTap = onTripleTap,
                            onOpenGoogle = { onSetSplitMode(SplitMode.SPLIT_BOTH) }
                        )
                    } else {
                        PageView(
                            doc = doc,
                            page = pane.page,
                            isDarkMode = isDarkMode,
                            pane = pane,
                            onPage = onPage,
                            reader = reader,
                            isZenMode = isZenMode,
                            onToggleZenControls = onToggleZenControls,
                            onTripleTap = onTripleTap,
                            onOpenGoogle = { onSetSplitMode(SplitMode.SPLIT_BOTH) }
                        )
                    }
                } else {
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) { empty() }
                }
            }

            if (isZenMode && showZenControls) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 0.dp),
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "${pane.page + 1}",
                            modifier = Modifier.clickable {
                                if ((pane.doc?.pageCount ?: 0) > 0) {
                                    showPageDialog = true
                                }
                            }.padding(vertical = 1.dp, horizontal = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White
                        )

                        HorizontalDivider(modifier = Modifier.width(12.dp), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f))
                        Text(
                            text = "B",
                            modifier = Modifier.clickable {
                                reader.addBookmark()
                                Toast.makeText(ctx, "Bookmarked!", Toast.LENGTH_SHORT).show()
                            }.padding(vertical = 1.dp, horizontal = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                        Text(
                            text = "G",
                            modifier = Modifier.clickable {
                                if (reader.vocabMode == VocabMode.VOCAB_PDF) {
                                    reader.vocabMode = VocabMode.GOOGLE_WEB
                                    if (splitMode != SplitMode.SPLIT_BOTH) {
                                        onSetSplitMode(SplitMode.SPLIT_BOTH)
                                    }
                                    Toast.makeText(ctx, "Google Mode ON (Split View)", Toast.LENGTH_SHORT).show()
                                } else {
                                    reader.vocabMode = VocabMode.VOCAB_PDF
                                    Toast.makeText(ctx, "Google Mode OFF", Toast.LENGTH_SHORT).show()
                                }
                            }.padding(vertical = 1.dp, horizontal = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                        Text(
                            text = splitLabel,
                            modifier = Modifier.clickable {
                                onToggleSplitMode()
                            }.padding(vertical = 1.dp, horizontal = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                        Text(
                            text = "X",
                            modifier = Modifier.clickable(onClick = onExitZen).padding(vertical = 1.dp, horizontal = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                    }
                }
            }

            val shouldShowPopup = when (splitMode) {
                SplitMode.SPLIT_BOTH -> {
                    if (reader.book.activeSelection != null || reader.book.activeHighlightMenu != null) {
                        label == "VOCAB"
                    } else if (reader.vocab.activeSelection != null || reader.vocab.activeHighlightMenu != null) {
                        label == "BOOK"
                    } else {
                        false
                    }
                }
                SplitMode.ONLY_BOOK -> label == "BOOK" && (reader.book.activeSelection != null || reader.book.activeHighlightMenu != null)
                SplitMode.ONLY_VOCAB -> label == "VOCAB" && (reader.vocab.activeSelection != null || reader.vocab.activeHighlightMenu != null)
            }
            val dictSummary = reader.dictSummary
            if (shouldShowPopup && dictSummary != null && reader.showDictPopup && !reader.isDraggingSelection) {
                val align = if (splitMode == SplitMode.SPLIT_BOTH) {
                    if (label == "BOOK") Alignment.BottomCenter else Alignment.TopCenter
                } else {
                    Alignment.BottomCenter
                }
                val topPadding = if (align == Alignment.TopCenter) {
                    if (isGoogleWebInVocab) 38.dp else 10.dp
                } else 0.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(align)
                        .padding(
                            top = topPadding,
                            bottom = if (align == Alignment.BottomCenter) 14.dp else 0.dp,
                            start = 6.dp,
                            end = 6.dp
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    WordSynonymAntonymPopup(
                        summary = dictSummary,
                        onDismiss = { reader.showDictPopup = false }
                    )
                }
            }
        }
    }

    if (showPageDialog) {
        val count = pane.doc?.pageCount ?: 0
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPageDialog = false },
            title = { Text("Go to page") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    label = { Text("1 – $count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { onPage(it - 1) }
                    showPageDialog = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showPageDialog = false }) { Text("Cancel") } }
        )
    }
}

/** Per-pane navigation: Prev / "n / total" (tap to jump) / Next. */
@Composable
fun PaneBar(label: String, pane: Pane, onPage: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    val count = pane.doc?.pageCount ?: 0
    val isContinuous = pane.readingMode == ReadingMode.CONTINUOUS

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                val pad = PaddingValues(horizontal = 3.dp, vertical = 0.dp)
                val btnStyle = MaterialTheme.typography.labelSmall

                if (!isContinuous) {
                    TextButton(
                        enabled = count > 0 && pane.page > 0,
                        contentPadding = pad,
                        modifier = Modifier.height(26.dp),
                        onClick = { onPage(pane.page - 1) }
                    ) { Text("‹ Prev", style = btnStyle) }
                }

                TextButton(
                    enabled = count > 0,
                    contentPadding = pad,
                    modifier = Modifier.height(26.dp),
                    onClick = { showDialog = true }
                ) {
                    Text(if (count > 0) "${pane.page + 1} / $count" else "– / –", style = btnStyle, fontWeight = FontWeight.Bold)
                }

                if (!isContinuous) {
                    TextButton(
                        enabled = count > 0 && pane.page < count - 1,
                        contentPadding = pad,
                        modifier = Modifier.height(26.dp),
                        onClick = { onPage(pane.page + 1) }
                    ) { Text("Next ›", style = btnStyle) }

                    Spacer(Modifier.width(2.dp))

                    TextButton(
                        enabled = count > 0,
                        contentPadding = pad,
                        modifier = Modifier.height(26.dp),
                        onClick = { pane.zoom = (pane.zoom - 0.25f).coerceAtLeast(1.0f) }
                    ) { Text("-", style = btnStyle) }

                    TextButton(
                        enabled = count > 0,
                        contentPadding = pad,
                        modifier = Modifier.height(26.dp),
                        onClick = { pane.zoom = 1.0f }
                    ) { Text("${(pane.zoom * 100).toInt()}%", style = btnStyle) }

                    TextButton(
                        enabled = count > 0,
                        contentPadding = pad,
                        modifier = Modifier.height(26.dp),
                        onClick = { pane.zoom = (pane.zoom + 0.25f).coerceAtMost(5.0f) }
                    ) { Text("+", style = btnStyle) }
                }
            }

            Spacer(Modifier.width(12.dp))

            Row(
                modifier = Modifier
                    .clickable { showNameDialog = true }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = pane.name ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Document Name") },
            text = { Text(pane.name ?: "Unknown") },
            confirmButton = { TextButton(onClick = { showNameDialog = false }) { Text("OK") } }
        )
    }

    if (showDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Go to page") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    label = { Text("1 – $count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toIntOrNull()?.let { onPage(it - 1) }
                    showDialog = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("Cancel") } }
        )
    }
}

/**
 * Detects two-finger pinch-to-zoom gestures at [PointerEventPass.Initial] so child
 * gesture detectors (tap, double-tap, long-press, selection handles, scroll) do not
 * intercept or cancel the pinch gesture.
 *
 * Uses [speedFactor] (default 0.085f) for a relaxed, fine-grained, smooth zoom feel.
 * Provides [zoomRatio] (newZoom / currentZoom) and [newZoom].
 */
fun Modifier.pinchToZoom(
    zoomState: () -> Float,
    onZoomChange: (zoomRatio: Float, newZoom: Float) -> Unit,
    minZoom: Float = 1.0f,
    maxZoom: Float = 5.0f,
    speedFactor: Float = 0.085f,
    enabled: Boolean = true
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
        } while (event.changes.none { it.pressed })

        var previousDistance: Float? = null

        while (true) {
            val currentEvent = awaitPointerEvent(PointerEventPass.Initial)
            val pressedChanges = currentEvent.changes.filter { it.pressed }

            if (pressedChanges.size < 2) {
                previousDistance = null
                if (pressedChanges.isEmpty()) {
                    break
                }
            } else {
                val p1 = pressedChanges[0]
                val p2 = pressedChanges[1]
                val currentDistance = kotlin.math.hypot(p1.position.x - p2.position.x, p1.position.y - p2.position.y)

                if (previousDistance != null && previousDistance > 20f && currentDistance > 20f) {
                    val rawFactor = currentDistance / previousDistance
                    val dampedFactor = 1f + (rawFactor - 1f) * speedFactor
                    val currentZoom = zoomState()
                    val newZoom = (currentZoom * dampedFactor).coerceIn(minZoom, maxZoom)
                    val effectiveZoomRatio = if (currentZoom > 0f) newZoom / currentZoom else 1f

                    if (kotlin.math.abs(newZoom - currentZoom) > 0.0005f) {
                        onZoomChange(effectiveZoomRatio, newZoom)
                    }
                }

                previousDistance = currentDistance
                pressedChanges.forEach { it.consume() }
            }
        }
    }
}

/** Renders one page fit-to-width inside a scrollable area with pinch-to-zoom, centering, and horizontal page swiping. */
@Composable
fun PageView(
    doc: PdfDoc,
    page: Int,
    isDarkMode: Boolean,
    pane: Pane,
    onPage: (Int) -> Unit,
    reader: Reader,
    isZenMode: Boolean = false,
    onToggleZenControls: () -> Unit = {},
    onTripleTap: () -> Unit = {},
    onOpenGoogle: (() -> Unit)? = null
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(isZenMode, pane) {
                detectMultiTapGestures(
                    onDoubleTap = if (isZenMode) { { onToggleZenControls() } } else null,
                    onTripleTap = { onTripleTap() },
                    onTap = {
                        reader.book.activeSelection = null
                        reader.vocab.activeSelection = null
                        reader.book.activeHighlightMenu = null
                        reader.vocab.activeHighlightMenu = null
                        reader.dictSummary = null
                        reader.showDictPopup = false
                    }
                )
            }
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        var image by remember(doc, page) { 
            mutableStateOf<ImageBitmap?>(doc.getAnyCachedBitmap(page)?.asImageBitmap()) 
        }
        var error by remember(doc) { mutableStateOf<String?>(null) }
        val verticalScrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()

        LaunchedEffect(page) { 
            verticalScrollState.scrollTo(0)
            horizontalScrollState.scrollTo(0)
        }

        LaunchedEffect(doc, page, widthPx, pane.zoom) {
            error = null
            try {
                if (image != null) delay(150)
                val targetW = renderWidth(widthPx, pane.zoom)
                val bmp = doc.render(page, targetW)
                bmp.prepareToDraw()
                image = bmp.asImageBitmap()
                doc.preloadRange(page, targetW, radius = 2)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not render this page"
            }
        }

        val invertMatrix = remember {
            androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
                -1f,  0f,  0f, 0f, 255f,
                 0f, -1f,  0f, 0f, 255f,
                 0f,  0f,  -1f, 0f, 255f,
                 0f,  0f,  0f, 1f,   0f
            ))
        }
        val colorFilter = if (isDarkMode) androidx.compose.ui.graphics.ColorFilter.colorMatrix(invertMatrix) else null

        val ratio = doc.getPageRatio(page) ?: 0.707f
        val displayedW = with(LocalDensity.current) { (widthPx * pane.zoom).toDp() }
        val displayedH = with(LocalDensity.current) { ((widthPx * pane.zoom) / ratio).toDp() }

        LaunchedEffect(pane.zoom) {
            if (pane.zoom <= 1.01f) {
                horizontalScrollState.scrollTo(0)
                verticalScrollState.scrollTo(0)
            } else {
                val targetScrollX = ((widthPx * (pane.zoom - 1f)) / 2f).roundToInt()
                horizontalScrollState.scrollTo(targetScrollX)
                val pageH = (widthPx * pane.zoom) / ratio
                if (pageH > heightPx) {
                    val targetScrollY = ((pageH - heightPx) / 2f).roundToInt()
                    verticalScrollState.scrollTo(targetScrollY)
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pinchToZoom(
                    zoomState = { pane.zoom },
                    onZoomChange = { _, newZoom ->
                        pane.zoom = newZoom
                    },
                    speedFactor = 0.085f,
                    enabled = !pane.isDraggingSelection
                )
                .horizontalScroll(horizontalScrollState, enabled = pane.zoom > 1.01f && !pane.isDraggingSelection)
                .verticalScroll(verticalScrollState, enabled = !pane.isDraggingSelection),
            contentAlignment = Alignment.Center
        ) {
            InteractivePdfPage(
                doc = doc,
                pageIndex = page,
                bitmap = image,
                displayedWidth = displayedW,
                displayedHeight = displayedH,
                colorFilter = colorFilter,
                pane = pane,
                reader = reader,
                isZenMode = isZenMode,
                onToggleZenControls = onToggleZenControls,
                onTripleTap = onTripleTap,
                onDoubleTapPage = { offset ->
                    val totalCount = doc.pageCount
                    val halfWidth: Float = with(density) { displayedW.toPx() } / 2.0f
                    if (offset.x > halfWidth) {
                        if (pane.page < totalCount - 1) {
                            onPage(pane.page + 1)
                        }
                    } else {
                        if (pane.page > 0) {
                            onPage(pane.page - 1)
                        }
                    }
                }
            )
        }

        if (image == null) {
            Box(Modifier.fillMaxSize().background(if (isDarkMode) androidx.compose.ui.graphics.Color(0xFF222222) else androidx.compose.ui.graphics.Color(0xFFEEEEEE)))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }

        // Sticky Action Toolbar Overlay in PageView (fixed at top, does not scroll with document)
        val activeSel = pane.activeSelection
        if (activeSel != null && !pane.isDraggingSelection) {
            val marginPx = with(density) { 10.dp.toPx() }
            PaneSelectionToolbar(pane, doc, reader, marginPx, onOpenGoogle = onOpenGoogle)
        }
        val activeHighlight = pane.activeHighlightMenu
        if (activeHighlight != null && activeSel == null && !pane.isDraggingSelection) {
            val marginPx = with(density) { 10.dp.toPx() }
            PaneHighlightToolbar(pane, reader, activeHighlight, marginPx, onOpenGoogle = onOpenGoogle)
        }
    }
}

data class OnlineBook(
    val title: String,
    val fileName: String,
    val downloadUrl: String
)

data class OnlineAuthorGroup(
    val author: String,
    val books: List<OnlineBook>
)

fun loadOnlineBooksCatalog(ctx: Context): List<OnlineAuthorGroup> {
    return try {
        ctx.assets.open("online_books.json").use { input ->
            java.io.InputStreamReader(input, "UTF-8").use { reader ->
                val type = object : TypeToken<List<OnlineAuthorGroup>>() {}.type
                Gson().fromJson(reader, type)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

data class GutenbergBookItem(
    val id: Int,
    val title: String,
    val author: String,
    val epubUrl: String?,
    val pdfUrl: String?
)

suspend fun fetchGutenbergBooks(query: String? = null): List<GutenbergBookItem> = withContext(Dispatchers.IO) {
    try {
        val searchUrl = if (!query.isNullOrBlank()) {
            "https://www.gutenberg.org/ebooks/search/?query=${Uri.encode(query)}"
        } else {
            "https://www.gutenberg.org/browse/scores/top"
        }

        val url = java.net.URL(searchUrl)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        conn.connectTimeout = 10000
        conn.readTimeout = 12000

        if (conn.responseCode in 200..399) {
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val list = mutableListOf<GutenbergBookItem>()
            val pattern = java.util.regex.Pattern.compile("href=[\"']/ebooks/(\\d+)[\"'][^>]*>[\\s\\S]*?<span class=[\"']title[\"']>([^<]+)</span>(?:[\\s\\S]*?<span class=[\"']subtitle[\"']>([^<]+)</span>)?")
            val matcher = pattern.matcher(html)

            while (matcher.find()) {
                val gid = matcher.group(1)?.toIntOrNull() ?: continue
                val title = matcher.group(2)?.trim() ?: "Untitled"
                val author = matcher.group(3)?.trim() ?: "Classic Literature"

                val epubUrl = "https://www.gutenberg.org/cache/epub/$gid/pg$gid.epub"
                val pdfUrl = "https://www.gutenberg.org/cache/epub/$gid/pg$gid.txt"

                list.add(GutenbergBookItem(gid, title, author, epubUrl, pdfUrl))
                if (list.size >= 30) break
            }

            if (list.isNotEmpty()) return@withContext list
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    try {
        val searchUrl = if (!query.isNullOrBlank()) {
            "https://gutendex.com/books/?search=${Uri.encode(query)}"
        } else {
            "https://gutendex.com/books/"
        }

        val url = java.net.URL(searchUrl)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        conn.connectTimeout = 8000
        conn.readTimeout = 10000

        if (conn.responseCode in 200..399) {
            val jsonStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = Gson().fromJson(jsonStr, com.google.gson.JsonObject::class.java)
            val results = root.getAsJsonArray("results") ?: return@withContext emptyList()

            val list = mutableListOf<GutenbergBookItem>()
            for (elem in results) {
                val obj = elem.asJsonObject
                val id = obj.get("id")?.asInt ?: 0
                val title = obj.get("title")?.asString ?: "Untitled"
                val authorsArray = obj.getAsJsonArray("authors")
                val authorName = if (authorsArray != null && authorsArray.size() > 0) {
                    authorsArray.get(0).asJsonObject.get("name")?.asString ?: "Unknown Author"
                } else {
                    "Unknown Author"
                }

                val epubUrl = "https://www.gutenberg.org/cache/epub/$id/pg$id.epub"
                val pdfUrl = "https://www.gutenberg.org/cache/epub/$id/pg$id.txt"

                list.add(GutenbergBookItem(id, title, authorName, epubUrl, pdfUrl))
            }
            list
        } else {
            emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun getCurrentAppVersionName(ctx: Context): String {
    return try {
        val pInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val vName = pInfo.versionName ?: "2.4.0"
        if (vName.startsWith("v")) vName else "v$vName"
    } catch (e: Exception) {
        "v2.4.0"
    }
}

data class AppUpdateInfo(
    val latestTag: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val isNewerAvailable: Boolean
)

suspend fun checkAppUpdateInfo(ctx: Context, currentVersion: String = getCurrentAppVersionName(ctx)): AppUpdateInfo = withContext(Dispatchers.IO) {
    // 1. Primary Strategy: Check Location header with instanceFollowRedirects = false (bypasses REST API rate limits)
    try {
        val webUrl = java.net.URL("https://github.com/ohidsajol/PdfHelperReader/releases/latest")
        val conn = webUrl.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        var location = conn.getHeaderField("Location") ?: ""
        if (location.isBlank() && conn.responseCode in 300..399) {
            location = conn.getHeaderField("location") ?: ""
        }

        if (location.contains("/tag/")) {
            val latestTag = location.substringAfterLast("/tag/").trim().split("/", "?", "#").first()
            if (latestTag.isNotBlank()) {
                val apkUrl = "https://github.com/ohidsajol/PdfHelperReader/releases/download/$latestTag/PdfHelperReader-$latestTag.apk"
                val isNewer = latestTag.lowercase().trim() != currentVersion.lowercase().trim()
                return@withContext AppUpdateInfo(
                    latestTag = latestTag,
                    releaseTitle = "PdfHelperReader $latestTag",
                    releaseNotes = "New release $latestTag is available on GitHub.",
                    apkDownloadUrl = apkUrl,
                    isNewerAvailable = isNewer
                )
            }
        }
    } catch (_: Exception) {
        // Fallthrough
    }

    // 2. Secondary Strategy: Fetch HTML of releases/latest with instanceFollowRedirects = true
    try {
        val webUrl = java.net.URL("https://github.com/ohidsajol/PdfHelperReader/releases/latest")
        val conn = webUrl.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        if (conn.responseCode in 200..399) {
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val tagMatch = Regex("/releases/tag/(v[0-9a-zA-Z\\.-]+)").find(html)
            if (tagMatch != null) {
                val latestTag = tagMatch.groupValues[1]
                val apkUrl = "https://github.com/ohidsajol/PdfHelperReader/releases/download/$latestTag/PdfHelperReader-$latestTag.apk"
                val isNewer = latestTag.lowercase().trim() != currentVersion.lowercase().trim()
                return@withContext AppUpdateInfo(
                    latestTag = latestTag,
                    releaseTitle = "PdfHelperReader $latestTag",
                    releaseNotes = "New release $latestTag is available on GitHub.",
                    apkDownloadUrl = apkUrl,
                    isNewerAvailable = isNewer
                )
            }
        }
    } catch (_: Exception) {
        // Fallthrough
    }

    // 3. Fallback Strategy: GitHub REST API
    val githubApiUrl = "https://api.github.com/repos/ohidsajol/PdfHelperReader/releases/latest"
    val url = java.net.URL(githubApiUrl)
    val conn = url.openConnection() as java.net.HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
    conn.connectTimeout = 8000
    conn.readTimeout = 8000

    if (conn.responseCode in 200..399) {
        val jsonStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = Gson().fromJson(jsonStr, com.google.gson.JsonObject::class.java)
        val latestTag = root.get("tag_name")?.asString ?: "v4.0.1"
        val releaseTitle = root.get("name")?.asString ?: "PdfHelperReader Update"
        val releaseNotes = root.get("body")?.asString ?: ""

        val assets = root.getAsJsonArray("assets")
        var apkDownloadUrl = ""
        if (assets != null) {
            for (elem in assets) {
                val obj = elem.asJsonObject
                val name = obj.get("name")?.asString ?: ""
                if (name.lowercase().endsWith(".apk")) {
                    apkDownloadUrl = obj.get("browser_download_url")?.asString ?: ""
                    break
                }
            }
        }
        if (apkDownloadUrl.isBlank()) {
            apkDownloadUrl = "https://github.com/ohidsajol/PdfHelperReader/releases/download/$latestTag/PdfHelperReader-$latestTag.apk"
        }

        val isNewer = latestTag.lowercase().trim() != currentVersion.lowercase().trim()
        AppUpdateInfo(latestTag, releaseTitle, releaseNotes, apkDownloadUrl, isNewer)
    } else {
        throw IOException("GitHub update check failed (HTTP ${conn.responseCode})")
    }
}

suspend fun downloadAppUpdateApk(
    ctx: Context,
    downloadUrl: String,
    onProgress: (Int, Int) -> Unit,
    onDone: (File) -> Unit,
    onError: (Exception) -> Unit
) {
    withContext(Dispatchers.IO) {
        var conn: java.net.HttpURLConnection? = null
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val fileName = downloadUrl.substringAfterLast("/").takeIf { it.endsWith(".apk") }
                ?: "PdfHelperReader-v4.0.1.apk"
            val updateApkFile = File(downloadsDir, fileName)
            if (updateApkFile.exists()) {
                runCatching { updateApkFile.delete() }
            }

            val url = java.net.URL(downloadUrl)
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true

            if (conn.responseCode in 200..399) {
                val totalBytes = conn.contentLength
                var downloadedBytes = 0

                val fos = updateApkFile.outputStream()
                try {
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        var read = input.read(buffer)
                        while (read != -1) {
                            fos.write(buffer, 0, read)
                            downloadedBytes += read
                            withContext(Dispatchers.Main) {
                                onProgress(downloadedBytes, totalBytes)
                            }
                            read = input.read(buffer)
                        }
                    }
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (e: Exception) {}
                } finally {
                    runCatching { fos.close() }
                }

                runCatching {
                    updateApkFile.setReadable(true, false)
                    updateApkFile.setWritable(true, false)
                }

                withContext(Dispatchers.Main) {
                    onDone(updateApkFile)
                }
            } else {
                throw IOException("Server returned HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError(e)
            }
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

fun startDownloadManagerUpdate(ctx: Context, apkDownloadUrl: String, title: String = "DualPDF Reader Update") {
    try {
        val targetDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.getExternalFilesDir(null) ?: ctx.filesDir
        if (!targetDir.exists()) targetDir.mkdirs()

        // Clean up older update APKs in targetDir
        targetDir.listFiles { f -> f.name.startsWith("PdfHelperReader_update") }?.forEach { it.delete() }

        val updateApkFile = File(targetDir, "PdfHelperReader_update_${System.currentTimeMillis()}.apk")

        val downloadManager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
            ?: throw IOException("DownloadManager service unavailable")

        val request = android.app.DownloadManager.Request(Uri.parse(apkDownloadUrl)).apply {
            setTitle(title)
            setDescription("Downloading latest release update...")
            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(updateApkFile))
            setMimeType("application/vnd.android.package-archive")
        }

        val downloadId = downloadManager.enqueue(request)
        Toast.makeText(ctx, "Downloading update in background... Check notification bar.", Toast.LENGTH_LONG).show()

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (id == downloadId) {
                    try {
                        c?.unregisterReceiver(this)
                    } catch (e: Exception) {}

                    var realApkFile = updateApkFile
                    try {
                        val query = android.app.DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        if (cursor != null && cursor.moveToFirst()) {
                            val localUriIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI)
                            if (localUriIdx != -1) {
                                val localUriStr = cursor.getString(localUriIdx)
                                if (!localUriStr.isNullOrBlank()) {
                                    val f = File(Uri.parse(localUriStr).path ?: "")
                                    if (f.exists() && f.length() > 1000) {
                                        realApkFile = f
                                    }
                                }
                            }
                            cursor.close()
                        }
                    } catch (e: Exception) {}

                    val installUri = downloadManager.getUriForDownloadedFile(downloadId)
                        ?: Uri.fromFile(realApkFile)

                    installDownloadedApkUri(c ?: ctx, installUri, realApkFile)
                }
            }
        }

        val filter = android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, "Update error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

var pendingApkToInstall: File? = null

fun installDownloadedApkUri(ctx: Context, apkUri: Uri, apkFile: File) {
    try {
        if (!apkFile.exists() || apkFile.length() < 1000) {
            Toast.makeText(ctx, "Downloaded APK is invalid or missing.", Toast.LENGTH_LONG).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                pendingApkToInstall = apkFile
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                Toast.makeText(ctx, "Please allow unknown app installs, then return to the app.", Toast.LENGTH_LONG).show()
                return
            }
        }

        pendingApkToInstall = null

        val packageInstaller = ctx.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(ctx.packageName)

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.openWrite("package_update", 0, apkFile.length()).use { out ->
            apkFile.inputStream().use { input ->
                input.copyTo(out)
            }
            session.fsync(out)
        }

        val callbackIntent = Intent(ctx, MainActivity::class.java).apply {
            action = "ACTION_INSTALL_COMPLETE"
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            ctx,
            sessionId,
            callbackIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )

        session.commit(pendingIntent.intentSender)
        session.close()
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(ctx, "Install error: ${e.javaClass.simpleName} - ${e.message}", Toast.LENGTH_LONG).show()
    }
}

suspend fun downloadGutenbergBook(
    ctx: Context,
    book: GutenbergBookItem,
    isEpub: Boolean,
    onDone: (File) -> Unit,
    onError: (Exception) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, "PdfHelperReader_files")
            if (!targetDir.exists()) targetDir.mkdirs()

            val cleanTitle = book.title.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(40)
            val ext = if (isEpub) ".epub" else ".pdf"
            val destFile = File(targetDir, "${cleanTitle}$ext")

            if (destFile.exists() && destFile.length() > 5000) {
                withContext(Dispatchers.Main) {
                    onDone(destFile)
                }
                return@withContext
            }

            if (isEpub) {
                val candidateUrls = listOf(
                    "https://www.gutenberg.org/cache/epub/${book.id}/pg${book.id}.epub",
                    "https://www.gutenberg.org/cache/epub/${book.id}/pg${book.id}-images.epub",
                    "https://www.gutenberg.org/ebooks/${book.id}.epub3.images"
                )

                var downloadedEpub = false
                for (cUrl in candidateUrls) {
                    try {
                        val url = java.net.URL(cUrl)
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        conn.connectTimeout = 12000
                        conn.readTimeout = 20000
                        conn.instanceFollowRedirects = true

                        if (conn.responseCode in 200..399) {
                            val tempStream = conn.inputStream
                            val headerBuffer = ByteArray(10)
                            val read = tempStream.read(headerBuffer)

                            if (read >= 4 && headerBuffer[0] == 'P'.code.toByte() && headerBuffer[1] == 'K'.code.toByte()) {
                                destFile.outputStream().use { output ->
                                    output.write(headerBuffer, 0, read)
                                    tempStream.copyTo(output)
                                }
                                downloadedEpub = true
                                break
                            } else {
                                conn.disconnect()
                            }
                        } else {
                            conn.disconnect()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (downloadedEpub && destFile.exists() && destFile.length() > 5000) {
                    android.media.MediaScannerConnection.scanFile(
                        ctx, arrayOf(destFile.absolutePath), null, null
                    )
                    withContext(Dispatchers.Main) {
                        onDone(destFile)
                    }
                    return@withContext
                } else {
                    throw IOException("Could not download a valid EPUB document for ${book.title}")
                }
            } else {
                val targetUrl = "https://www.gutenberg.org/cache/epub/${book.id}/pg${book.id}.txt"
                val url = java.net.URL(targetUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                conn.connectTimeout = 12000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true

                if (conn.responseCode in 200..399) {
                    val rawText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    if (rawText.isBlank() || rawText.length < 500) {
                        throw IOException("Text content unavailable for ${book.title}")
                    }
                    val epubData = EpubBook(book.title, book.author, listOf(rawText))
                    EpubParser.convertEpubToPdf(epubData, destFile)

                    android.media.MediaScannerConnection.scanFile(
                        ctx, arrayOf(destFile.absolutePath), arrayOf("application/pdf"), null
                    )
                    withContext(Dispatchers.Main) {
                        onDone(destFile)
                    }
                } else {
                    throw IOException("Server returned HTTP ${conn.responseCode}")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError(e)
            }
        }
    }
}

suspend fun downloadOnlineBook(ctx: Context, book: OnlineBook, onDone: (File) -> Unit, onError: (Exception) -> Unit) {
    withContext(Dispatchers.IO) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(downloadsDir, "PdfHelperReader_files")
            if (!targetDir.exists()) targetDir.mkdirs()

            val destFile = File(targetDir, book.fileName)

            // Tier 1: Check if already present on disk
            if (destFile.exists() && destFile.length() > 5000) {
                withContext(Dispatchers.Main) {
                    onDone(destFile)
                }
                return@withContext
            }

            // Tier 2: Download directly from GitHub raw CDN repository
            val url = java.net.URL(book.downloadUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true

            if (conn.responseCode in 200..399) {
                destFile.outputStream().use { output ->
                    conn.inputStream.use { input ->
                        val headerBuffer = ByteArray(10)
                        val read = input.read(headerBuffer)
                        val headerStr = String(headerBuffer, 0, read.coerceAtLeast(0), Charsets.US_ASCII)

                        if (!headerStr.startsWith("%PDF-")) {
                            destFile.delete()
                            throw IOException("Downloaded file is not a valid PDF document.")
                        }
                        output.write(headerBuffer, 0, read)
                        input.copyTo(output)
                    }
                }
                android.media.MediaScannerConnection.scanFile(
                    ctx, arrayOf(destFile.absolutePath), arrayOf("application/pdf"), null
                )
                withContext(Dispatchers.Main) {
                    onDone(destFile)
                }
            } else {
                throw IOException("Server returned HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError(e)
            }
        }
    }
}

fun convertTextToPdf(title: String, author: String, text: String, outputFile: File) {
    val document = com.itextpdf.text.Document(com.itextpdf.text.PageSize.A4, 36f, 36f, 36f, 36f)
    val writer = com.itextpdf.text.pdf.PdfWriter.getInstance(document, outputFile.outputStream())
    document.open()

    val titleFont = com.itextpdf.text.FontFactory.getFont(com.itextpdf.text.FontFactory.HELVETICA_BOLD, 22f, com.itextpdf.text.BaseColor.BLACK)
    val authorFont = com.itextpdf.text.FontFactory.getFont(com.itextpdf.text.FontFactory.HELVETICA_OBLIQUE, 14f, com.itextpdf.text.BaseColor.DARK_GRAY)
    val bodyFont = com.itextpdf.text.FontFactory.getFont(com.itextpdf.text.FontFactory.HELVETICA, 10f, com.itextpdf.text.BaseColor.BLACK)

    val pTitle = com.itextpdf.text.Paragraph(title, titleFont)
    pTitle.alignment = com.itextpdf.text.Element.ALIGN_CENTER
    pTitle.spacingAfter = 10f
    document.add(pTitle)

    val pAuthor = com.itextpdf.text.Paragraph("By $author", authorFont)
    pAuthor.alignment = com.itextpdf.text.Element.ALIGN_CENTER
    pAuthor.spacingAfter = 20f
    document.add(pAuthor)

    val cleanText = text.replace("\r\n", "\n")
    val lines = cleanText.split("\n")

    var paragraphText = StringBuilder()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            if (paragraphText.isNotEmpty()) {
                val p = com.itextpdf.text.Paragraph(paragraphText.toString(), bodyFont)
                p.spacingAfter = 6f
                document.add(p)
                paragraphText = StringBuilder()
            }
        } else {
            if (paragraphText.isNotEmpty()) paragraphText.append(" ")
            paragraphText.append(trimmed)
        }
    }
    if (paragraphText.isNotEmpty()) {
        val p = com.itextpdf.text.Paragraph(paragraphText.toString(), bodyFont)
        document.add(p)
    }

    document.close()
}

@Composable
fun MessageCard(title: String, body: String, actions: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}

suspend fun extractPageText(ctx: Context, uri: Uri?, doc: PdfDoc, pageIndex: Int): String = withContext(Dispatchers.IO) {
    doc.getPageText(pageIndex)?.let { return@withContext it }
    try {
        val reader = doc.getOrInitTextReader(ctx, uri) ?: return@withContext "No text reader available."
        val text = com.itextpdf.text.pdf.parser.PdfTextExtractor.getTextFromPage(reader, pageIndex + 1)
        val result = text.ifEmpty { "No selectable text found on this page." }
        doc.putPageText(pageIndex, result)
        result
    } catch (t: Throwable) {
        t.rethrowIfFatal()
        "Could not extract text for this page."
    }
}

@Composable
fun TextView(
    doc: PdfDoc,
    page: Int,
    isDarkMode: Boolean,
    uri: Uri?,
    isZenMode: Boolean = false,
    onToggleZenControls: () -> Unit = {},
    onTripleTap: () -> Unit = {}
) {
    val ctx = LocalContext.current
    var textContent by remember(doc, page, uri) { mutableStateOf("Loading text...") }

    LaunchedEffect(doc, page, uri) {
        textContent = extractPageText(ctx, uri, doc, page)
        // Prefetch neighbors
        launch(Dispatchers.IO) {
            if (page + 1 < doc.pageCount) extractPageText(ctx, uri, doc, page + 1)
            if (page - 1 >= 0) extractPageText(ctx, uri, doc, page - 1)
        }
    }

    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isZenMode) {
                    detectMultiTapGestures(
                        onDoubleTap = if (isZenMode) { { onToggleZenControls() } } else null,
                        onTripleTap = { onTripleTap() }
                    )
                }
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            contentAlignment = Alignment.TopStart
        ) {
            Text(
                text = textContent,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isDarkMode) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
fun PaneSelectionToolbar(
    pane: Pane,
    doc: PdfDoc,
    reader: Reader,
    finalTopPx: Float,
    onOpenGoogle: (() -> Unit)? = null
) {
    val activeSel = pane.activeSelection ?: return
    val ctx = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var currentHighlightGroupId by remember(activeSel) { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, finalTopPx.roundToInt()) }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        SelectionFloatingToolbar(
            onHighlight = { colorHex ->
                val sel = pane.activeSelection ?: activeSel
                val groupId = currentHighlightGroupId ?: UUID.randomUUID().toString()

                if (currentHighlightGroupId != null) {
                    reader.highlights.removeAll { it.groupId == currentHighlightGroupId }
                }
                currentHighlightGroupId = groupId

                val newHighlights = (sel.minPage..sel.maxPage).mapNotNull { p ->
                    val pWords = doc.getWords(p) ?: doc.getOrExtractWords(ctx, pane.uri ?: (if (pane == reader.book) reader.getBookUri() else null), p)
                    val pRange = sel.wordRangeForPage(p, pWords.size)
                    if (pRange != null && pWords.isNotEmpty()) {
                        val (sChar, eChar) = sel.charOffsetsForPage(p, pRange)
                        val pText = PdfTextHelper.getSelectedText(pWords, pRange.first, pRange.last, sChar, eChar)
                            .filter { it >= ' ' || it == '\n' || it == '\t' }.trim()
                        val pRects = PdfTextHelper.getSelectionRects(pWords, pRange.first, pRange.last, sChar, eChar)
                        if (pText.isNotBlank()) {
                            Highlight(
                                pageIndex = p,
                                text = pText,
                                colorHex = colorHex,
                                rects = pRects,
                                groupId = groupId
                            )
                        } else null
                    } else null
                }
                if (newHighlights.isNotEmpty()) {
                    reader.addHighlights(newHighlights)
                    Toast.makeText(ctx, "Highlighted!", Toast.LENGTH_SHORT).show()
                }
            },
            onCopy = {
                val sel = pane.activeSelection ?: activeSel
                val fullText = (sel.minPage..sel.maxPage).mapNotNull { p ->
                    val pWords = doc.getWords(p) ?: doc.getOrExtractWords(ctx, pane.uri ?: (if (pane == reader.book) reader.getBookUri() else null), p)
                    val pRange = sel.wordRangeForPage(p, pWords.size)
                    if (pRange != null && pWords.isNotEmpty()) {
                        val (sChar, eChar) = sel.charOffsetsForPage(p, pRange)
                        PdfTextHelper.getSelectedText(pWords, pRange.first, pRange.last, sChar, eChar).takeIf { it.isNotBlank() }
                    } else null
                }.joinToString("\n").filter { it >= ' ' || it == '\n' || it == '\t' }.trim()
                android.util.Log.i("SelectionDebug", "onCopy: sel=$sel, textLen=${fullText.length}, preview='${fullText.take(100)}' ... '${fullText.takeLast(100)}'")
                if (fullText.isNotBlank()) {
                    clipboardManager.setText(AnnotatedString(fullText))
                    Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            },
            onSearch = {
                val sel = pane.activeSelection ?: activeSel
                val fullText = (sel.minPage..sel.maxPage).mapNotNull { p ->
                    val pWords = doc.getWords(p) ?: doc.getOrExtractWords(ctx, pane.uri ?: (if (pane == reader.book) reader.getBookUri() else null), p)
                    val pRange = sel.wordRangeForPage(p, pWords.size)
                    if (pRange != null && pWords.isNotEmpty()) {
                        val (sChar, eChar) = sel.charOffsetsForPage(p, pRange)
                        PdfTextHelper.getSelectedText(pWords, pRange.first, pRange.last, sChar, eChar).takeIf { it.isNotBlank() }
                    } else null
                }.joinToString(" ")
                val cleanQuery = fullText.filter { it >= ' ' || it == '\n' || it == '\t' }.replace(Regex("\\s+"), " ").trim()
                android.util.Log.i("SelectionDebug", "onSearch: sel=$sel, queryLen=${cleanQuery.length}, preview='${cleanQuery.take(100)}' ... '${cleanQuery.takeLast(100)}'")
                if (cleanQuery.isNotBlank()) {
                    reader.showDictPopup = false
                    reader.dictSummary = null
                    reader.searchGoogle(cleanQuery)
                    onOpenGoogle?.invoke()
                }
            },
            onShare = {
                val sel = pane.activeSelection ?: activeSel
                val fullText = (sel.minPage..sel.maxPage).mapNotNull { p ->
                    val pWords = doc.getWords(p) ?: doc.getOrExtractWords(ctx, pane.uri ?: (if (pane == reader.book) reader.getBookUri() else null), p)
                    val pRange = sel.wordRangeForPage(p, pWords.size)
                    if (pRange != null && pWords.isNotEmpty()) {
                        val (sChar, eChar) = sel.charOffsetsForPage(p, pRange)
                        PdfTextHelper.getSelectedText(pWords, pRange.first, pRange.last, sChar, eChar).takeIf { it.isNotBlank() }
                    } else null
                }.joinToString("\n").filter { it >= ' ' || it == '\n' || it == '\t' }.trim()
                if (fullText.isNotBlank()) {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, fullText)
                        type = "text/plain"
                    }
                    ctx.startActivity(Intent.createChooser(sendIntent, "Share text"))
                }
            },
            onSelectAll = {
                val otherPane = if (pane == reader.book) reader.vocab else reader.book
                otherPane.activeSelection = null
                otherPane.activeHighlightMenu = null
                val sel = pane.activeSelection ?: activeSel
                if (!sel.isSinglePage) {
                    // Multi-page selection -> select entire document
                    val lastPage = (doc.pageCount - 1).coerceAtLeast(0)
                    val lastWords = doc.getWords(lastPage) ?: doc.getOrExtractWords(ctx, pane.uri ?: (if (pane == reader.book) reader.getBookUri() else null), lastPage)
                    val lastWordIdx = (lastWords.size - 1).coerceAtLeast(0)
                    val lastChar = if (lastWords.isNotEmpty()) lastWords[lastWordIdx].text.length else -1
                    pane.activeSelection = TextSelection(
                        startPageIndex = 0,
                        startWordIndex = 0,
                        startCharOffset = 0,
                        endPageIndex = lastPage,
                        endWordIndex = lastWordIdx,
                        endCharOffset = lastChar
                    )
                } else {
                    // Single-page selection -> select all words on the current page
                    val curPage = sel.startPageIndex
                    val pageWords = doc.getWords(curPage) ?: doc.getOrExtractWords(ctx, pane.uri ?: (if (pane == reader.book) reader.getBookUri() else null), curPage)
                    if (pageWords.isNotEmpty()) {
                        val lastIdx = pageWords.size - 1
                        val lastChar = pageWords[lastIdx].text.length
                        pane.activeSelection = TextSelection(
                            startPageIndex = curPage,
                            startWordIndex = 0,
                            startCharOffset = 0,
                            endPageIndex = curPage,
                            endWordIndex = lastIdx,
                            endCharOffset = lastChar
                        )
                    }
                }
            },
            onDismiss = {
                pane.activeSelection = null
                reader.book.activeSelection = null
                reader.vocab.activeSelection = null
                reader.dictSummary = null
                reader.showDictPopup = false
            }
        )
    }
}

@Composable
fun WordSynonymAntonymPopup(
    summary: VocabGenerator.WordSummary,
    onDismiss: () -> Unit
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 410.dp)
            .padding(horizontal = 4.dp)
            .pointerInput(Unit) {
                detectTapGestures { /* consume taps inside popup */ }
            }
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            // Header Row: Word, Score badge, and Dismiss '✕' button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = summary.word,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (summary.reference != null && !summary.reference.equals(summary.word, ignoreCase = true)) {
                        Text(
                            text = "(Ref: ${summary.reference})",
                            style = MaterialTheme.typography.labelMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                    if (summary.score != null) {
                        val scoreText = if (summary.score % 1.0f == 0.0f) summary.score.toInt().toString() else summary.score.toString()
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(0.dp)
                        ) {
                            Text(
                                text = "score $scoreText",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else if (summary.isOnline) {
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.padding(0.dp)
                        ) {
                            Text(
                                text = "online",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }

            Spacer(Modifier.height(5.dp))

            // Scrollable list of Definitions, Synonyms & Antonyms (max 180dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Definitions
                if (summary.definitions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        summary.definitions.forEach { defItem ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                if (defItem.type.isNotBlank()) {
                                    Text(
                                        text = "• ${defItem.type.lowercase()}: ",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "• ",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = defItem.def,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (summary.synonyms.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "Synonyms: ",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color(0xFF1E88E5)
                        )
                        Text(
                            text = summary.synonyms.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (summary.antonyms.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "Antonyms: ",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color(0xFFE53935)
                        )
                        Text(
                            text = summary.antonyms.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VocabThresholdDialog(
    initialThreshold: Float,
    existingFileExists: Boolean,
    onDismiss: () -> Unit,
    onGenerate: (Float) -> Unit,
    onOpenExisting: (() -> Unit)? = null
) {
    var threshold by remember { mutableFloatStateOf(initialThreshold.coerceIn(0f, 100f)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Generate Vocabulary PDF", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Select maximum wordfreq score threshold (0 - 100). Words with score ≤ threshold will be included in the vocabulary document.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Prominent Score Display Card
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Score Threshold",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                            Text(
                                text = "${threshold.roundToInt()}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        val category = when {
                            threshold <= 25f -> "Rare / Archaic"
                            threshold <= 40f -> "Advanced / Literary"
                            threshold <= 60f -> "Intermediate"
                            else -> "Comprehensive"
                        }
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                        ) {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Slider with - and + step buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = { threshold = (threshold - 1f).coerceIn(0f, 100f) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it.roundToInt().toFloat() },
                        valueRange = 0f..100f,
                        modifier = Modifier.weight(1f)
                    )

                    FilledTonalIconButton(
                        onClick = { threshold = (threshold + 1f).coerceIn(0f, 100f) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Quick Preset Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "Rare: 25" to 25f,
                        "Literary: 37" to 37f,
                        "Mid: 50" to 50f,
                        "All: 100" to 100f
                    ).forEach { (label, value) ->
                        FilterChip(
                            selected = threshold.roundToInt() == value.roundToInt(),
                            onClick = { threshold = value },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)) }
                        )
                    }
                }

                if (existingFileExists) {
                    Text(
                        text = "An existing vocab file exists and will be regenerated.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onGenerate(threshold) }) {
                Text("Generate")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (existingFileExists && onOpenExisting != null) {
                    OutlinedButton(onClick = onOpenExisting) {
                        Text("Open Existing")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun PaneHighlightToolbar(
    pane: Pane,
    reader: Reader,
    highlight: Highlight,
    finalTopPx: Float,
    onOpenGoogle: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, finalTopPx.roundToInt()) }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        ExistingHighlightToolbar(
            highlight = highlight,
            onColorChange = { newColorHex ->
                reader.updateHighlightColor(highlight, newColorHex)
                pane.activeHighlightMenu = highlight.copy(colorHex = newColorHex)
            },
            onDelete = {
                reader.removeHighlight(highlight)
                Toast.makeText(ctx, "Highlight deleted", Toast.LENGTH_SHORT).show()
                pane.activeHighlightMenu = null
                reader.book.activeHighlightMenu = null
                reader.vocab.activeHighlightMenu = null
            },
            onCopy = {
                val related = reader.getRelatedHighlights(highlight)
                val fullText = related.joinToString("\n") { it.text }
                    .filter { it >= ' ' || it == '\n' || it == '\t' }.trim()
                if (fullText.isNotBlank()) {
                    clipboardManager.setText(AnnotatedString(fullText))
                    Toast.makeText(ctx, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            },
            onSearch = {
                val related = reader.getRelatedHighlights(highlight)
                val fullQuery = related.joinToString(" ") { it.text }
                    .filter { it >= ' ' || it == '\n' || it == '\t' }.replace(Regex("\\s+"), " ").trim()
                if (fullQuery.isNotBlank()) {
                    reader.showDictPopup = false
                    reader.dictSummary = null
                    reader.searchGoogle(fullQuery)
                    onOpenGoogle?.invoke()
                }
            },
            onDismiss = {
                pane.activeHighlightMenu = null
                reader.book.activeHighlightMenu = null
                reader.vocab.activeHighlightMenu = null
            }
        )
    }
}

@Composable
fun ContinuousPageView(
    doc: PdfDoc,
    isDarkMode: Boolean,
    pane: Pane,
    onPage: (Int) -> Unit,
    reader: Reader,
    externalListState: androidx.compose.foundation.lazy.LazyListState? = null,
    isZenMode: Boolean = false,
    onToggleZenControls: () -> Unit = {},
    onTripleTap: () -> Unit = {},
    onOpenGoogle: (() -> Unit)? = null
) {
    val listState = externalListState ?: rememberLazyListState(initialFirstVisibleItemIndex = pane.page)

    LaunchedEffect(doc, pane.page) {
        if (!pane.isDraggingSelection && !listState.isScrollInProgress && pane.page in 0 until doc.pageCount) {
            if (kotlin.math.abs(pane.page - listState.firstVisibleItemIndex) > 1) {
                listState.scrollToItem(pane.page)
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                listState.firstVisibleItemIndex
            } else {
                val midpoint = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                val centerItem = visibleItems.firstOrNull { item ->
                    item.offset <= midpoint && (item.offset + item.size) >= midpoint
                } ?: visibleItems.first()
                centerItem.index
            }
        }
        .distinctUntilChanged()
        .collect { index ->
            doc.focusIndex = index
            if (index != pane.page) {
                onPage(index)
            }
        }
    }

    LaunchedEffect(pane.page) {
        if (!pane.isDraggingSelection && !listState.isScrollInProgress && kotlin.math.abs(pane.page - listState.firstVisibleItemIndex) > 1) {
            listState.scrollToItem(pane.page)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(isZenMode, pane) {
                detectMultiTapGestures(
                    onDoubleTap = if (isZenMode) { { onToggleZenControls() } } else null,
                    onTripleTap = { onTripleTap() },
                    onTap = {
                        reader.book.activeSelection = null
                        reader.vocab.activeSelection = null
                        reader.book.activeHighlightMenu = null
                        reader.vocab.activeHighlightMenu = null
                        reader.dictSummary = null
                        reader.showDictPopup = false
                    }
                )
            }
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val paneCenterY = heightPx / 2f
        val coroutineScope = rememberCoroutineScope()
        val onRequestScroll: (Float) -> Unit = { delta ->
            coroutineScope.launch {
                listState.scrollBy(delta)
            }
        }
        val horizontalScrollState = rememberScrollState()
        val totalWidth = with(density) { (widthPx * pane.zoom).toDp() }

        LaunchedEffect(pane.zoom) {
            if (pane.zoom <= 1.01f) {
                horizontalScrollState.scrollTo(0)
            } else {
                val targetScrollX = ((widthPx * (pane.zoom - 1f)) / 2f).roundToInt()
                horizontalScrollState.scrollTo(targetScrollX)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pinchToZoom(
                    zoomState = { pane.zoom },
                    onZoomChange = { zoomRatio, newZoom ->
                        val oldZoom = pane.zoom
                        pane.zoom = newZoom
                        if (newZoom > 1.005f || oldZoom > 1.005f) {
                            val deltaY = (listState.firstVisibleItemScrollOffset + paneCenterY) * (zoomRatio - 1f)
                            listState.dispatchRawDelta(-deltaY)
                        }
                    },
                    speedFactor = 0.085f,
                    enabled = !pane.isDraggingSelection
                )
                .horizontalScroll(
                    state = horizontalScrollState,
                    enabled = pane.zoom > 1.01f && !pane.isDraggingSelection
                )
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .width(totalWidth)
                    .fillMaxHeight(),
                userScrollEnabled = !pane.isDraggingSelection,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(
                    count = doc.pageCount,
                    key = { it }
                ) { index ->
                    ContinuousPageItem(
                        doc = doc,
                        index = index,
                        widthPx = widthPx,
                        isDarkMode = isDarkMode,
                        pane = pane,
                        reader = reader,
                        isZenMode = isZenMode,
                        onToggleZenControls = onToggleZenControls,
                        onTripleTap = onTripleTap,
                        onRequestScroll = onRequestScroll
                    )
                    Spacer(
                        Modifier
                            .height(16.dp)
                            .fillMaxWidth()
                            .pointerInput(pane) {
                                detectTapGestures(
                                    onTap = {
                                        if (!pane.isDraggingSelection) {
                                            reader.book.activeSelection = null
                                            reader.vocab.activeSelection = null
                                            reader.book.activeHighlightMenu = null
                                            reader.vocab.activeHighlightMenu = null
                                            reader.dictSummary = null
                                            reader.showDictPopup = false
                                        }
                                    }
                                )
                            }
                    )
                }
            }
        }

        // Sticky Action Toolbar Overlay in ContinuousPageView (fixed at top, does not scroll with document)
        val activeSel = pane.activeSelection
        if (activeSel != null && !pane.isDraggingSelection) {
            val marginPx = with(density) { 10.dp.toPx() }
            PaneSelectionToolbar(pane, doc, reader, marginPx, onOpenGoogle = onOpenGoogle)
        }
        val activeHighlight = pane.activeHighlightMenu
        if (activeHighlight != null && activeSel == null && !pane.isDraggingSelection) {
            val marginPx = with(density) { 10.dp.toPx() }
            PaneHighlightToolbar(pane, reader, activeHighlight, marginPx, onOpenGoogle = onOpenGoogle)
        }
    }
}

@Composable
fun ContinuousPageItem(
    doc: PdfDoc,
    index: Int,
    widthPx: Int,
    isDarkMode: Boolean,
    pane: Pane,
    reader: Reader,
    isZenMode: Boolean = false,
    onToggleZenControls: () -> Unit = {},
    onTripleTap: () -> Unit = {},
    onRequestScroll: ((Float) -> Unit)? = null
) {
    val ratio = doc.getPageRatio(index) ?: 0.707f // Default A4 ratio (1 / sqrt(2))
    
    var image by remember(doc, index) { 
        mutableStateOf<ImageBitmap?>(doc.getAnyCachedBitmap(index)?.asImageBitmap()) 
    }

    LaunchedEffect(doc, index, widthPx, pane.zoom) {
        try {
            if (image != null) delay(150)
            val targetW = renderWidth(widthPx, pane.zoom)
            val bmp = doc.render(index, targetW)
            bmp.prepareToDraw()
            image = bmp.asImageBitmap()
            doc.preloadRange(index, targetW, radius = 2)
        } catch (_: Exception) {}
    }

    val invertMatrix = remember { 
        androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
            -1f,  0f,  0f, 0f, 255f,
             0f, -1f,  0f, 0f, 255f,
             0f,  0f,  -1f, 0f, 255f,
             0f,  0f,  0f, 1f,   0f
        ))
    }
    val colorFilter = if (isDarkMode) androidx.compose.ui.graphics.ColorFilter.colorMatrix(invertMatrix) else null

    val displayedW = with(LocalDensity.current) { (widthPx * pane.zoom).toDp() }
    val displayedH = with(LocalDensity.current) { ((widthPx * pane.zoom) / ratio).toDp() }

    Box(
        modifier = Modifier
            .width(displayedW)
            .height(displayedH),
        contentAlignment = Alignment.Center
    ) {
        InteractivePdfPage(
            doc = doc,
            pageIndex = index,
            bitmap = image,
            displayedWidth = displayedW,
            displayedHeight = displayedH,
            colorFilter = colorFilter,
            pane = pane,
            reader = reader,
            isZenMode = isZenMode,
            onToggleZenControls = onToggleZenControls,
            onTripleTap = onTripleTap,
            onRequestScroll = onRequestScroll
        )
        if (image == null) {
            Box(Modifier.fillMaxSize().background(if (isDarkMode) androidx.compose.ui.graphics.Color(0xFF222222) else androidx.compose.ui.graphics.Color(0xFFEEEEEE)))
        }
    }
}

@Composable
fun GoogleWebView(
    reader: Reader,
    url: String,
    rawQuery: String = "",
    searchToken: Long = 0L,
    isDarkMode: Boolean = false,
    onBackToVocab: () -> Unit = {}
) {
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(reader.googleWebView) }
    var currentLoadedUrl by remember { mutableStateOf(reader.googleWebView?.url ?: "") }
    var canGoBack by remember { mutableStateOf(reader.googleWebView?.canGoBack() == true) }
    var canGoForward by remember { mutableStateOf(reader.googleWebView?.canGoForward() == true) }
    var pageTitle by remember { mutableStateOf(reader.googleWebView?.title ?: "Google Search") }
    var lastInjectedToken by remember { mutableLongStateOf(0L) }
    var isPageLoading by remember { mutableStateOf(reader.googleWebView == null) }

    val bgColor = if (isDarkMode) androidx.compose.ui.graphics.Color(0xFF202124) else androidx.compose.ui.graphics.Color.White
    val nativeBgColor = if (isDarkMode) android.graphics.Color.parseColor("#202124") else android.graphics.Color.WHITE

    fun injectFullQueryIntoSearchBox(view: android.webkit.WebView?, pageUrl: String?) {
        val sanitizedQuery = rawQuery.filter { it >= ' ' || it == '\n' || it == '\t' }
        if (pageUrl?.contains("google.") != true || sanitizedQuery.isBlank()) return
        if (lastInjectedToken == searchToken && searchToken != 0L) return
        lastInjectedToken = searchToken

        val escaped = org.json.JSONObject.quote(sanitizedQuery)
        val js = """
            (function() {
                var fullText = $escaped;
                if (!fullText) return;
                function apply() {
                    var selectors = [
                        'textarea[name="q"]',
                        'input[name="q"]',
                        'textarea.gLFyf',
                        'input.gLFyf',
                        'input[type="search"]',
                        'textarea',
                        'input[type="text"]'
                    ];
                    for (var s = 0; s < selectors.length; s++) {
                        var el = document.querySelector(selectors[s]);
                        if (el) {
                            var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value")?.set
                                || Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, "value")?.set;
                            if (nativeSetter) {
                                nativeSetter.call(el, fullText);
                            } else {
                                el.value = fullText;
                            }
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                            break;
                        }
                    }
                }
                apply();
                setTimeout(apply, 300);
                setTimeout(apply, 600);
                setTimeout(apply, 1000);
                setTimeout(apply, 1800);
                setTimeout(apply, 2800);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    LaunchedEffect(url, searchToken) {
        if (url.isNotBlank()) {
            if (searchToken > 0L && searchToken != reader.googleLastLoadedToken) {
                reader.googleLastLoadedToken = searchToken
                currentLoadedUrl = url
                isPageLoading = true
                webViewRef?.loadUrl(url)
            } else if (url != currentLoadedUrl && (webViewRef?.url.isNullOrBlank())) {
                currentLoadedUrl = url
                isPageLoading = true
                webViewRef?.loadUrl(url)
            } else {
                injectFullQueryIntoSearchBox(webViewRef, webViewRef?.url)
            }
        }
    }

    BackHandler(enabled = true) {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBackToVocab()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = { webViewRef?.goBack() },
                    enabled = canGoBack,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("‹ Back", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }

                TextButton(
                    onClick = { webViewRef?.goForward() },
                    enabled = canGoForward,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Forward ›", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }

                TextButton(
                    onClick = {
                        isPageLoading = true
                        webViewRef?.reload()
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("↻ Reload", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = pageTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(bgColor)
        ) {
            AndroidView(
                factory = { ctx ->
                    val wv = reader.getOrCreateGoogleWebView(ctx)
                    (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                    wv.setBackgroundColor(nativeBgColor)
                    wv.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageStarted(view: android.webkit.WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, pageUrl, favicon)
                            isPageLoading = true
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                        }

                        override fun onPageFinished(view: android.webkit.WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)
                            isPageLoading = false
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                            pageTitle = view?.title ?: "Google Search"
                            injectFullQueryIntoSearchBox(view, pageUrl)
                        }

                        override fun doUpdateVisitedHistory(view: android.webkit.WebView?, pageUrl: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, pageUrl, isReload)
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                        }
                    }

                    if (wv.url.isNullOrBlank() || (searchToken > 0L && searchToken != reader.googleLastLoadedToken)) {
                        reader.googleLastLoadedToken = searchToken
                        wv.loadUrl(url)
                        currentLoadedUrl = url
                        isPageLoading = true
                    } else {
                        currentLoadedUrl = wv.url ?: url
                        canGoBack = wv.canGoBack()
                        canGoForward = wv.canGoForward()
                        pageTitle = wv.title ?: "Google Search"
                        isPageLoading = false
                    }
                    webViewRef = wv
                    wv
                },
                update = { webView ->
                    webViewRef = webView
                    webView.setBackgroundColor(nativeBgColor)
                    if (searchToken > 0L && searchToken != reader.googleLastLoadedToken && url.isNotBlank()) {
                        reader.googleLastLoadedToken = searchToken
                        currentLoadedUrl = url
                        isPageLoading = true
                        webView.loadUrl(url)
                    }
                },
                onRelease = { webView ->
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isPageLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

data class SelectionToolbarInfo(
    val rect: androidx.compose.ui.geometry.Rect,
    val onCopy: (() -> Unit)?,
    val onSelectAll: (() -> Unit)?
)

class AppTextToolbar : TextToolbar {
    var toolbarInfo by mutableStateOf<SelectionToolbarInfo?>(null)
        private set

    override val status: TextToolbarStatus
        get() = if (toolbarInfo != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun hide() {
        toolbarInfo = null
    }

    override fun showMenu(
        rect: androidx.compose.ui.geometry.Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        toolbarInfo = SelectionToolbarInfo(rect, onCopyRequested, onSelectAllRequested)
    }
}

/* =========================================================================
 *  Built-in File Manager / Browser
 * ========================================================================= */

enum class BrowserSortMode {
    TIME,
    NAME
}

data class BookFileInfo(
    val uri: Uri?,
    val name: String,
    val path: String,
    val size: Long,
    val dateModifiedMs: Long,
    val isEpub: Boolean
)

fun scanDeviceBookFiles(ctx: Context): List<BookFileInfo> {
    val result = mutableListOf<BookFileInfo>()
    val seenPaths = HashSet<String>()

    try {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val selection = "(${MediaStore.Files.FileColumns.DATA} LIKE '%.pdf' OR " +
                "${MediaStore.Files.FileColumns.DATA} LIKE '%.epub' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.pdf' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.epub' OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/pdf' OR " +
                "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/epub+zip')"

        ctx.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            null,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = if (idCol >= 0) cursor.getLong(idCol) else -1L
                val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                val displayName = if (nameCol >= 0) cursor.getString(nameCol) else null
                val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                val dateSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null

                val name = displayName ?: data?.substringAfterLast('/') ?: "Unknown"
                if (name.startsWith(".")) continue
                val lowerName = name.lowercase()
                val isEpub = lowerName.endsWith(".epub") || (mime?.contains("epub", ignoreCase = true) == true)
                val isPdf = lowerName.endsWith(".pdf") || (mime?.contains("pdf", ignoreCase = true) == true)
                if (!isPdf && !isEpub) continue

                var fileObj: File? = null
                if (!data.isNullOrBlank()) {
                    val f = File(data)
                    if (f.exists()) {
                        if (seenPaths.contains(f.absolutePath)) continue
                        seenPaths.add(f.absolutePath)
                        fileObj = f
                    }
                }

                val uri = if (fileObj != null) {
                    Uri.fromFile(fileObj)
                } else if (id >= 0) {
                    ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                } else {
                    continue
                }

                val pathStr = fileObj?.absolutePath ?: data ?: name
                val fileSize = if (fileObj != null && fileObj.length() > 0) fileObj.length() else size
                val timeMs = if (fileObj != null && fileObj.lastModified() > 0) {
                    fileObj.lastModified()
                } else if (dateSec > 0) {
                    dateSec * 1000L
                } else 0L

                result.add(
                    BookFileInfo(
                        uri = uri,
                        name = name,
                        path = pathStr,
                        size = fileSize,
                        dateModifiedMs = timeMs,
                        isEpub = isEpub
                    )
                )
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    try {
        val commonDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PdfHelperReader_files"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            File(Environment.getExternalStorageDirectory(), "Books")
        )

        for (dir in commonDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && !f.name.startsWith(".") && f.length() > 0) {
                        val lower = f.name.lowercase()
                        val isPdf = lower.endsWith(".pdf")
                        val isEpub = lower.endsWith(".epub")
                        if ((isPdf || isEpub) && !seenPaths.contains(f.absolutePath)) {
                            seenPaths.add(f.absolutePath)
                            result.add(
                                BookFileInfo(
                                    uri = Uri.fromFile(f),
                                    name = f.name,
                                    path = f.absolutePath,
                                    size = f.length(),
                                    dateModifiedMs = f.lastModified(),
                                    isEpub = isEpub
                                )
                            )
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return result
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.getDefault(), "%.1f %s", value, units[digitGroups])
}

fun formatFileDate(timeMs: Long): String {
    if (timeMs <= 0) return ""
    val sdf = SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault())
    return sdf.format(Date(timeMs))
}

fun formatDisplayPath(path: String): String {
    val prefix = "/storage/emulated/0/"
    return if (path.startsWith(prefix)) {
        path.removePrefix(prefix)
    } else {
        path
    }
}

@Composable
fun FileBrowserDialog(
    reader: Reader,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggleMinMax: () -> Unit,
    onDismiss: () -> Unit,
    onFileSelected: (BookFileInfo) -> Unit
) {
    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            FileBrowserContent(
                reader = reader,
                listState = listState,
                isMinMode = false,
                onToggleMinMax = onToggleMinMax,
                onDismiss = onDismiss,
                onFileSelected = onFileSelected,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun FileBrowserContent(
    reader: Reader,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isMinMode: Boolean,
    onToggleMinMax: () -> Unit,
    onDismiss: () -> Unit = {},
    onFileSelected: (BookFileInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf(reader.browserSearchQuery) }
    val allFiles = reader.browserFiles
    val sortMode = reader.browserSortMode

    val displayedFiles = remember(allFiles, searchQuery, isMinMode) {
        if (isMinMode || searchQuery.isBlank()) {
            allFiles
        } else {
            val q = searchQuery.trim().lowercase()
            allFiles.filter { it.name.lowercase().contains(q) || it.path.lowercase().contains(q) }
        }
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = if (isMinMode) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        if (!isMinMode) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Browse Files",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (reader.isScanningBrowserFiles) {
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        Text(
                            text = if (reader.isScanningBrowserFiles) "Scanning device..." else "${displayedFiles.size} of ${allFiles.size} books",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { reader.scanBrowserFiles(force = true) },
                            enabled = !reader.isScanningBrowserFiles
                        ) {
                            Text("Refresh")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Close", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        reader.browserSearchQuery = it
                    },
                    placeholder = { Text("Search title or folder...", fontSize = 14.sp) },
                    singleLine = true,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            TextButton(onClick = {
                                searchQuery = ""
                                reader.browserSearchQuery = ""
                            }) {
                                Text("✕", fontSize = 16.sp)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            HorizontalDivider()
        }

        // Content Row: File List on Left + Sidebar on Right Edge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Left area: File List
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (displayedFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (reader.isScanningBrowserFiles) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text("Scanning storage for PDF & EPUB files...")
                            }
                        } else if (!isMinMode && searchQuery.isNotEmpty()) {
                            Text("No files match \"$searchQuery\"")
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No PDF or EPUB files found.")
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { reader.scanBrowserFiles(force = true) }) {
                                    Text("Scan Again")
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        items(
                            items = displayedFiles,
                            key = { it.path }
                        ) { file ->
                            BrowserFileItem(
                                file = file,
                                onClick = { onFileSelected(file) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 3.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            )
                        }
                    }
                }
            }

            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            )

            // Right Edge: Sidebar with "Time", "Name", and "Min/Max" button
            Surface(
                modifier = Modifier
                    .width(68.dp)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "SORT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(10.dp))

                        // "Time" Option
                        SidebarSortCard(
                            title = "Time",
                            subtitle = "Recent",
                            isSelected = sortMode == BrowserSortMode.TIME,
                            onClick = {
                                if (sortMode != BrowserSortMode.TIME) {
                                    reader.updateBrowserSortMode(BrowserSortMode.TIME)
                                }
                            }
                        )

                        Spacer(Modifier.height(8.dp))

                        // "Name" Option
                        SidebarSortCard(
                            title = "Name",
                            subtitle = "A - Z",
                            isSelected = sortMode == BrowserSortMode.NAME,
                            onClick = {
                                if (sortMode != BrowserSortMode.NAME) {
                                    reader.updateBrowserSortMode(BrowserSortMode.NAME)
                                }
                            }
                        )
                    }

                    // "Min/Max" button at the bottom of the right edge
                    SidebarMinMaxButton(
                        isMinMode = isMinMode,
                        onClick = onToggleMinMax
                    )
                }
            }
        }
    }
}
}

@Composable
fun SidebarMinMaxButton(
    isMinMode: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Min/Max",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = if (isMinMode) "Max" else "Min",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun SidebarSortCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
fun BrowserFileItem(
    file: BookFileInfo,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = if (file.isEpub) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f)
            },
            modifier = Modifier
                .width(42.dp)
                .height(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (file.isEpub) "EPUB" else "PDF",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (file.isEpub) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val displayPath = formatDisplayPath(file.path)
            if (displayPath.isNotEmpty() && displayPath != file.name) {
                Text(
                    text = displayPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatFileSize(file.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                val dateStr = formatFileDate(file.dateModifiedMs)
                if (dateStr.isNotEmpty()) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
