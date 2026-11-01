package com.example.dualpdf

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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.rememberLazyListState
import android.content.res.Configuration
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
        (Runtime.getRuntime().maxMemory() / 4).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        // Removed entryRemoved and recycle() to prevent "Canvas: trying to use a recycled bitmap" crash in Compose
    }

    private val ocrCache = android.util.LruCache<String, List<OcrBox>>(50)
    private val preloadJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    
    private val pageRatios = java.util.concurrent.ConcurrentHashMap<Int, Float>()
    private val textCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    var itextReader: com.itextpdf.text.pdf.PdfReader? = null
        private set

    suspend fun getOrInitTextReader(ctx: Context, uri: Uri?): com.itextpdf.text.pdf.PdfReader? = withContext(textDispatcher) {
        if (itextReader != null) return@withContext itextReader
        if (uri == null) return@withContext null
        try {
            val resolver = ctx.contentResolver
            val input = resolver.openInputStream(uri)
            input?.use { itextReader = com.itextpdf.text.pdf.PdfReader(it) }
            itextReader
        } catch (e: Exception) {
            null
        }
    }

    @Volatile var focusIndex: Int = 0

    fun getPageText(index: Int): String? = textCache[index]
    fun putPageText(index: Int, text: String) { textCache[index] = text }

    fun getCachedOcrBoxes(key: String): List<OcrBox>? = ocrCache.get(key)
    fun putCachedOcrBoxes(key: String, boxes: List<OcrBox>) { ocrCache.put(key, boxes) }

    fun getPageRatio(index: Int): Float? = pageRatios[index]

    /** Renders [index] as a white-backed ARGB bitmap, [targetWidth] px wide (clamped by MAX_DIM). */
    suspend fun render(index: Int, targetWidth: Int): Bitmap = withContext(renderDispatcher) {
        val cacheKey = "${index}_${targetWidth}"
        bitmapCache.get(cacheKey)?.let { return@withContext it }

        check(!closed) { "Document is closed" }
        bitmapCache.get(cacheKey)?.let { return@withContext it }

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
            runCatching { itextReader?.close() }
            tempFile?.delete()
            bitmapCache.evictAll()
            ocrCache.evictAll()
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
    GOOGLE_WEB
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
    var googleSearchQuery by mutableStateOf("https://www.google.com")
        private set

    fun searchGoogle(query: String) {
        vocabMode = VocabMode.GOOGLE_WEB
        val encoded = Uri.encode(query)
        googleSearchQuery = "https://www.google.com/search?q=$encoded"
    }

    var expectedVocab by mutableStateOf<String?>(null)
        private set

    var isDarkMode by mutableStateOf(false)
        private set

    val bookmarks = mutableStateListOf<Bookmark>()
    val history = mutableStateListOf<HistoryEntry>()

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
                        if (k.startsWith("book_page_") || k.startsWith("vocab_page_")) {
                            if (v is Number) editor.putInt(k, v.toInt())
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

    fun syncFromDiskAndRestore() {
        cleanStorageDirectoryIfExceedsLimit()
        syncFromDisk()
        isDarkMode = getBoolPref("is_dark_mode", false)
        loadBookmarks()
        loadHistory()

        val lastBook = prefs.getString("last_book_uri", null)
        if (!lastBook.isNullOrBlank() && book.doc == null) {
            openBook(Uri.parse(lastBook))
        }
    }

    /** Reopen whatever was open last time. */
    fun restore() {
        syncFromDisk()
        isDarkMode = getBoolPref("is_dark_mode", false)
        
        loadBookmarks()
        loadHistory()
        
        val defaultExtracted = getBoolPref("default_book_extracted_v6", false)
        if (!defaultExtracted) {
            try {
                val destFile = File(ctx.getExternalFilesDir(null), "PdfHelperReader_Tutorial.pdf")
                if (destFile.exists()) destFile.delete()
                ctx.assets.open("PdfHelperReader_Tutorial.pdf").use { i ->
                    destFile.outputStream().use { o -> i.copyTo(o) }
                }
                editPrefs { putBoolean("default_book_extracted_v6", true) }
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
            val pageToSet = targetPage ?: if (mapped != null) (mapped - 1) else prefs.getInt("vocab_page_$bookKey", 0)
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

                val result = VocabGenerator.generate(ctx, inputPdf, outputPdf, pageRange) { current, total ->
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
            } catch (e: Exception) {
                if (e !is CancellationException && coroutineContext.isActive) {
                    withContext(Dispatchers.Main) {
                        onError(e)
                    }
                }
            }
        }
    }

    fun cancelVocab() {
        vocabJob?.cancel()
        vocabJob = null
    }

    fun generateOcr(onProgress: (Int, Int) -> Unit, onDone: (File) -> Unit, onError: (Exception) -> Unit) {
        val activeDoc = book.doc ?: return
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
                
                OcrProcessor.processPdf(ctx, inputPdf, outputPdf) { current, total ->
                    scope.launch(Dispatchers.Main) {
                        onProgress(current, total)
                    }
                }
                
                inputPdf.delete()
                withContext(Dispatchers.Main) {
                    onDone(outputPdf)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    withContext(Dispatchers.Main) {
                        onError(e)
                    }
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
    
    fun addBookmark() {
        val doc = book.doc ?: return
        val bookPageIndex = book.page
        val vocabPageIndex = vocab.page
        val now = System.currentTimeMillis()
        val defaultName = "Book: ${bookPageIndex + 1} | Vocab: ${vocabPageIndex + 1}"

        scope.launch {
            var words = emptyList<String>()

            // 1. Try cached OCR boxes
            val cachedOcr = doc.getCachedOcrBoxes("ocr_${bookPageIndex}")
            if (!cachedOcr.isNullOrEmpty()) {
                words = cachedOcr.map { it.text.trim() }.filter { it.isNotBlank() }
            }

            // 2. If OCR boxes were empty, try iText page text
            if (words.size < 5) {
                val rawText = extractPageText(ctx, book.uri, doc, bookPageIndex)
                if (!rawText.startsWith("No ") && !rawText.startsWith("Could ")) {
                    words = rawText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
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
                    book.page = prefs.getInt("book_page_$bookKey", 0).coerceIn(0, doc.pageCount - 1)
                    book.doc = doc
                    book.uri = uri
                    editPrefs { putString("last_book_uri", uri.toString()) }
                    loadBookmarks()
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
            val savedVocabPage = prefs.getInt("vocab_page_$bookKey", -1)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = Reader(applicationContext, scope)
        reader.restore()
        checkPermissions()
        setContent { AppTheme { ReaderScreen(reader) } }
    }

    override fun onResume() {
        super.onResume()
        reader.syncFromDiskAndRestore()
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
    
    var isOcrGenerating by remember { mutableStateOf(false) }
    var ocrProgressCurrent by remember { mutableIntStateOf(0) }
    var ocrProgressTotal by remember { mutableIntStateOf(0) }

    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showLobDialog by remember { mutableStateOf(false) }
    var isDownloadingLob by remember { mutableStateOf(false) }
    var downloadingBookTitle by remember { mutableStateOf("") }

    var splitMode by remember { mutableStateOf(SplitMode.SPLIT_BOTH) }
    var showOcrConfirmDialog by remember { mutableStateOf(false) }
    var isZenMode by remember { mutableStateOf(false) }

    var lastCopiedText by remember { mutableStateOf("") }
    var showGoogleSearchBanner by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            runCatching {
                val clipData = clipboard?.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotBlank() && text != lastCopiedText) {
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

    val generateVocabAction: () -> Unit = {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val vocabDir = File(downloadsDir, "PdfHelperReader_files")
        val expectedName = reader.expectedVocab ?: "vocab.pdf"
        val existingFile = File(vocabDir, expectedName)

        if (existingFile.exists()) {
            Toast.makeText(ctx, "Vocab file already exists! Opening...", Toast.LENGTH_SHORT).show()
            reader.onVocabPicked(Uri.fromFile(existingFile))
        } else {
            val currentBookPage = reader.book.page + 1

            isGenerating = true
            genProgressCurrent = 0
            genProgressTotal = 0

            Toast.makeText(ctx, "Generating Vocabulary PDF...", Toast.LENGTH_SHORT).show()

            reader.generateVocab(
                pageRange = null,
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
                        onExitZen = { isZenMode = false },
                        onToggleSplitMode = {
                            splitMode = when (splitMode) {
                                SplitMode.SPLIT_BOTH -> SplitMode.ONLY_BOOK
                                SplitMode.ONLY_BOOK -> SplitMode.ONLY_VOCAB
                                SplitMode.ONLY_VOCAB -> SplitMode.SPLIT_BOTH
                            }
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
                        vocabListState = vocabListState
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

        if (showLobDialog) {
            val catalog = remember { loadOnlineBooksCatalog(ctx) }
            var lobTabSource by remember { mutableIntStateOf(0) }
            var gutenbergSearchQuery by remember { mutableStateOf("") }
            var gutenbergResults by remember { mutableStateOf<List<GutenbergBookItem>>(emptyList()) }
            var isSearchingGutenberg by remember { mutableStateOf(false) }

            LaunchedEffect(lobTabSource, gutenbergSearchQuery) {
                if (lobTabSource == 1) {
                    isSearchingGutenberg = true
                    if (gutenbergSearchQuery.isNotBlank()) delay(400)
                    gutenbergResults = fetchGutenbergBooks(gutenbergSearchQuery)
                    isSearchingGutenberg = false
                }
            }

            AlertDialog(
                onDismissRequest = { if (!isDownloadingLob) showLobDialog = false },
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
                        } else if (lobTabSource == 0) {
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
                                                                showLobDialog = false
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
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    FilledTonalButton(
                                                        onClick = {
                                                            isDownloadingLob = true
                                                            downloadingBookTitle = "${b.title} (EPUB)"
                                                            scope.launch {
                                                                downloadGutenbergBook(ctx, b, isEpub = true, onDone = { file ->
                                                                    isDownloadingLob = false
                                                                    showLobDialog = false
                                                                    reader.onBookPicked(Uri.fromFile(file))
                                                                    Toast.makeText(ctx, "${b.title} loaded!", Toast.LENGTH_SHORT).show()
                                                                }, onError = { err ->
                                                                    isDownloadingLob = false
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
                                                            isDownloadingLob = true
                                                            downloadingBookTitle = "${b.title} (PDF)"
                                                            scope.launch {
                                                                downloadGutenbergBook(ctx, b, isEpub = false, onDone = { file ->
                                                                    isDownloadingLob = false
                                                                    showLobDialog = false
                                                                    reader.onBookPicked(Uri.fromFile(file))
                                                                    Toast.makeText(ctx, "${b.title} loaded!", Toast.LENGTH_SHORT).show()
                                                                }, onError = { err ->
                                                                    isDownloadingLob = false
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
                },
                confirmButton = {
                    TextButton(
                        onClick = { showLobDialog = false },
                        enabled = !isDownloadingLob
                    ) { Text("Close") }
                }
            )
        }

        if (showHistory) {
            AlertDialog(
                onDismissRequest = { showHistory = false },
                title = { Text("History") },
                text = {
                    if (reader.history.isEmpty()) {
                        Text("No history yet.")
                    } else {
                        LazyColumn {
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
                },
                confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Close") } }
            )
        }

        Box(Modifier.fillMaxSize()) {
            if (showOcrConfirmDialog) {
                AlertDialog(
                    onDismissRequest = { showOcrConfirmDialog = false },
                    title = { Text("Confirm OCR") },
                    text = { Text("Apply OCR to this PDF? This will process the document and create a searchable text layer in a new PDF saved to Downloads/PdfHelperReader_files.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showOcrConfirmDialog = false
                            isOcrGenerating = true
                            ocrProgressCurrent = 0
                            ocrProgressTotal = 0
                            reader.generateOcr(
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
                                        val expectedName = "${originalName}_ocr.pdf"
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
                        }) { Text("Confirm") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showOcrConfirmDialog = false }) { Text("Cancel") }
                    }
                )
            }

            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().height(topBarHeight).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val pad = PaddingValues(horizontal = 4.dp)
                    
                    TextButton(
                        enabled = reader.book.doc != null,
                        onClick = {
                            reader.addBookmark()
                            Toast.makeText(ctx, "Bookmarked!", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = pad
                    ) { Text("B") }

                    TextButton(onClick = { showBookmarks = true }, contentPadding = pad) { Text("BL") }

                    TextButton(onClick = {
                        splitMode = when (splitMode) {
                            SplitMode.SPLIT_BOTH -> SplitMode.ONLY_BOOK
                            SplitMode.ONLY_BOOK -> SplitMode.ONLY_VOCAB
                            SplitMode.ONLY_VOCAB -> SplitMode.SPLIT_BOTH
                        }
                    }, contentPadding = pad) {
                        Text(when (splitMode) {
                            SplitMode.SPLIT_BOTH -> "Both"
                            SplitMode.ONLY_BOOK -> "Book"
                            SplitMode.ONLY_VOCAB -> "Vocab"
                        })
                    }

                    TextButton(onClick = { isZenMode = true }, contentPadding = pad) { Text("Z") }

                    TextButton(
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
                        contentPadding = pad
                    ) {
                        Text(
                            text = if (isSyncedReading && isVocabPdfActive && isContinuousMode && isPdfViewMode) "SR: On" else "SR: Off",
                            color = if (isSyncedReading && isVocabPdfActive && isContinuousMode && isPdfViewMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    TextButton(
                        enabled = reader.book.doc != null && isContinuousMode && isPdfViewMode,
                        onClick = {
                            isAutoScroll = !isAutoScroll
                            Toast.makeText(ctx, if (isAutoScroll) "Auto Scroll ON (1 height / 5 min)" else "Auto Scroll OFF", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = pad
                    ) {
                        Text(
                            text = if (isAutoScroll && isContinuousMode && isPdfViewMode) "AS: On" else "AS: Off",
                            color = if (isAutoScroll && isContinuousMode && isPdfViewMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    TextButton(
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
                        contentPadding = pad
                    ) { Text("Sync") }

                    TextButton(onClick = {
                        reader.vocabMode = when (reader.vocabMode) {
                            VocabMode.VOCAB_PDF -> VocabMode.GOOGLE_WEB
                            VocabMode.GOOGLE_WEB -> {
                                isSyncedReading = false
                                VocabMode.VOCAB_PDF
                            }
                        }
                        if (reader.vocabMode == VocabMode.GOOGLE_WEB) {
                            isSyncedReading = false
                        }
                    }, contentPadding = pad) {
                        Text(when (reader.vocabMode) {
                            VocabMode.VOCAB_PDF -> "G:Vocab"
                            VocabMode.GOOGLE_WEB -> "G:Google"
                        })
                    }

                    TextButton(onClick = { reader.toggleDarkMode() }, contentPadding = pad) { Text("T") }

                    TextButton(onClick = {
                        reader.book.readingMode = when (reader.book.readingMode) {
                            ReadingMode.PAGE_BY_PAGE -> ReadingMode.CONTINUOUS
                            ReadingMode.CONTINUOUS -> {
                                isAutoScroll = false
                                isSyncedReading = false
                                ReadingMode.PAGE_BY_PAGE
                            }
                        }
                        reader.vocab.readingMode = reader.book.readingMode
                    }, contentPadding = pad) {
                        Text(when (reader.book.readingMode) {
                            ReadingMode.PAGE_BY_PAGE -> "R:P"
                            ReadingMode.CONTINUOUS -> "R:C"
                        })
                    }

                    TextButton(onClick = {
                        reader.book.viewMode = when (reader.book.viewMode) {
                            ViewMode.PDF_VIEW -> {
                                isAutoScroll = false
                                isSyncedReading = false
                                ViewMode.TEXT_VIEW
                            }
                            ViewMode.TEXT_VIEW -> ViewMode.PDF_VIEW
                        }
                        reader.vocab.viewMode = reader.book.viewMode
                    }, contentPadding = pad) {
                        Text(when (reader.book.viewMode) {
                            ViewMode.PDF_VIEW -> "V:PDF"
                            ViewMode.TEXT_VIEW -> "V:TXT"
                        })
                    }

                    TextButton(onClick = { showHistory = true }, contentPadding = pad) { Text("H") }

                    if (reader.book.doc != null) {
                        TextButton(onClick = { showOcrConfirmDialog = true }, contentPadding = pad) { Text("O") }
                    }

                    TextButton(onClick = { showLobDialog = true }, contentPadding = pad) { Text("LOB") }

                    Spacer(modifier = Modifier.width(8.dp))

                    FilledTonalButton(
                        onClick = { pickBook.launch(DOCUMENT_MIMES) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("OB") }

                    Spacer(modifier = Modifier.width(4.dp))

                    FilledTonalButton(
                        onClick = { pickVocab.launch(DOCUMENT_MIMES) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("OV") }

                    Spacer(modifier = Modifier.width(4.dp))

                    FilledTonalButton(
                        onClick = {
                            if (reader.book.doc == null) {
                                Toast.makeText(ctx, "Please open a book first", Toast.LENGTH_SHORT).show()
                            } else {
                                generateVocabAction()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("LV") }
                }
                HorizontalDivider()

                DocumentPanesLayout(
                    reader = reader,
                    splitMode = splitMode,
                    isLandscape = isLandscape,
                    isZenMode = false,
                    onToggleSplitMode = {
                        splitMode = when (splitMode) {
                            SplitMode.SPLIT_BOTH -> SplitMode.ONLY_BOOK
                            SplitMode.ONLY_BOOK -> SplitMode.ONLY_VOCAB
                            SplitMode.ONLY_VOCAB -> SplitMode.SPLIT_BOTH
                        }
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
                    vocabListState = vocabListState
                )
            }

            if (isOcrGenerating) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Card {
                        Column(Modifier.padding(24.dp).fillMaxWidth(0.8f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("OCRing PDF...", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(16.dp))
                            if (ocrProgressTotal > 0) {
                                val progress = genProgressCurrent.toFloat() / genProgressTotal
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
    onExitZen: () -> Unit = {},
    onToggleSplitMode: () -> Unit = {},
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
    vocabListState: androidx.compose.foundation.lazy.LazyListState? = null
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

    when (splitMode) {
        SplitMode.SPLIT_BOTH -> {
            if (isLandscape) {
                Row(modifier) {
                    PdfPane("BOOK", reader.book, Modifier.weight(1f).fillMaxHeight(), reader::setBookPage, reader.isDarkMode, isZenMode, reader, splitMode, onExitZen, onToggleSplitMode, emptyBookCard, externalListState = bookListState)
                    VerticalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
                    PdfPane("VOCAB", reader.vocab, Modifier.weight(1f).fillMaxHeight(), reader::setVocabPage, reader.isDarkMode, isZenMode, reader, splitMode, onExitZen, onToggleSplitMode, emptyVocabCard, externalListState = vocabListState)
                }
            } else {
                Column(modifier) {
                    PdfPane("BOOK", reader.book, Modifier.weight(1f).fillMaxWidth(), reader::setBookPage, reader.isDarkMode, isZenMode, reader, splitMode, onExitZen, onToggleSplitMode, emptyBookCard, externalListState = bookListState)
                    HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary)
                    PdfPane("VOCAB", reader.vocab, Modifier.weight(1f).fillMaxWidth(), reader::setVocabPage, reader.isDarkMode, isZenMode, reader, splitMode, onExitZen, onToggleSplitMode, emptyVocabCard, externalListState = vocabListState)
                }
            }
        }
        SplitMode.ONLY_BOOK -> {
            PdfPane("BOOK", reader.book, modifier, reader::setBookPage, reader.isDarkMode, isZenMode, reader, splitMode, onExitZen, onToggleSplitMode, emptyBookCard, externalListState = bookListState)
        }
        SplitMode.ONLY_VOCAB -> {
            PdfPane("VOCAB", reader.vocab, modifier, reader::setVocabPage, reader.isDarkMode, isZenMode, reader, splitMode, onExitZen, onToggleSplitMode, emptyVocabCard, externalListState = vocabListState)
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
    reader: Reader,
    splitMode: SplitMode = SplitMode.SPLIT_BOTH,
    onExitZen: () -> Unit = {},
    onToggleSplitMode: () -> Unit = {},
    empty: @Composable () -> Unit,
    externalListState: androidx.compose.foundation.lazy.LazyListState? = null
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
        if (!isZenMode && !isGoogleWebInVocab) {
            PaneBar(label, pane, onPage)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (isGoogleWebInVocab) {
                GoogleWebView(reader.googleSearchQuery, onBackToVocab = { reader.vocabMode = VocabMode.VOCAB_PDF })
            } else {
                val doc = pane.doc
                if (doc != null) {
                    if (pane.viewMode == ViewMode.TEXT_VIEW) {
                        TextView(doc, pane.page, isDarkMode, pane.uri)
                    } else if (pane.readingMode == ReadingMode.CONTINUOUS) {
                        ContinuousPageView(doc, isDarkMode, pane, onPage, externalListState = externalListState)
                    } else {
                        PageView(doc, pane.page, isDarkMode, pane, onPage)
                    }
                } else {
                    Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) { empty() }
                }
            }

            if (isZenMode) {
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

                        val showButtons = label == "VOCAB" || (label == "BOOK" && reader.vocab.doc == null)
                        if (showButtons) {
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
                                    reader.vocabMode = when (reader.vocabMode) {
                                        VocabMode.VOCAB_PDF -> VocabMode.GOOGLE_WEB
                                        VocabMode.GOOGLE_WEB -> VocabMode.VOCAB_PDF
                                    }
                                    Toast.makeText(ctx, "Google Mode toggled", Toast.LENGTH_SHORT).show()
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
                        } else if (label == "BOOK") {
                            HorizontalDivider(modifier = Modifier.width(12.dp), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f))
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

/** Renders one page fit-to-width inside a scrollable area with pinch-to-zoom, centering, and horizontal page swiping. */
@Composable
fun PageView(doc: PdfDoc, page: Int, isDarkMode: Boolean, pane: Pane, onPage: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        var image by remember(doc) { mutableStateOf<ImageBitmap?>(null) }
        var ocrBoxes by remember(doc, page) { mutableStateOf<List<OcrBox>>(emptyList()) }
        var error by remember(doc) { mutableStateOf<String?>(null) }
        var loading by remember(doc) { mutableStateOf(true) }
        val verticalScrollState = rememberScrollState()
        val horizontalScrollState = rememberScrollState()

        LaunchedEffect(page) { 
            verticalScrollState.scrollTo(0)
            horizontalScrollState.scrollTo(0)
        }

        LaunchedEffect(doc, page, widthPx, pane.zoom) {
            loading = true
            error = null
            try {
                val targetW = renderWidth(widthPx, pane.zoom)
                
                val bmp = doc.render(page, targetW)
                bmp.prepareToDraw()
                image = bmp.asImageBitmap()
                loading = false
                ocrBoxes = extractOcrBoxes(doc, page, bmp)
                
                doc.preloadRange(page, targetW, radius = 3)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not render this page"
            } finally {
                loading = false
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

        val focusManager = LocalFocusManager.current
        val displayedW = with(LocalDensity.current) { (widthPx * pane.zoom).toDp() }

        SelectionContainer {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(doc, page) {
                        detectTapGestures(
                            onTap = {
                                focusManager.clearFocus()
                            },
                            onDoubleTap = { offset ->
                                val totalCount = doc.pageCount
                                val halfWidth = size.width / 2f
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
                    .pointerInput(doc, page) {
                        detectTransformGestures { _, _, zoomChange, _ ->
                            val newZoom = (pane.zoom * zoomChange).coerceIn(1.0f, 5.0f)
                            if (kotlin.math.abs(newZoom - pane.zoom) > 0.01f) {
                                pane.zoom = newZoom
                            }
                        }
                    }
                    .horizontalScroll(horizontalScrollState)
                    .verticalScroll(verticalScrollState),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.width(displayedW)
                ) {
                    image?.let { bmp ->
                        val displayedH = with(LocalDensity.current) { (displayedW.toPx() * (bmp.height.toFloat() / bmp.width.toFloat())).toDp() }

                        Image(
                            bitmap = bmp,
                            contentDescription = "PDF page ${page + 1}",
                            modifier = Modifier.width(displayedW),
                            contentScale = ContentScale.FillWidth,
                            filterQuality = FilterQuality.Medium,
                            colorFilter = colorFilter
                        )

                        Box(Modifier.matchParentSize()) {
                            ocrBoxes.forEach { box ->
                                Text(
                                    text = box.text,
                                    color = androidx.compose.ui.graphics.Color.Transparent,
                                    fontSize = with(LocalDensity.current) { ((box.bottom - box.top) * displayedH.value * 0.85f).dp.toSp() },
                                    softWrap = false,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .absoluteOffset(
                                            x = (box.left * displayedW.value).dp,
                                            y = (box.top * displayedH.value).dp
                                        )
                                        .width(((box.right - box.left) * displayedW.value).dp)
                                        .height(((box.bottom - box.top) * displayedH.value).dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (image == null) {
            Box(Modifier.fillMaxSize().background(if (isDarkMode) androidx.compose.ui.graphics.Color(0xFF222222) else androidx.compose.ui.graphics.Color(0xFFEEEEEE)))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(16.dp))
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

                val formatsObj = obj.getAsJsonObject("formats")
                var epubUrl: String? = null
                var pdfUrl: String? = null

                if (formatsObj != null) {
                    if (formatsObj.has("application/epub+zip")) {
                        epubUrl = formatsObj.get("application/epub+zip")?.asString
                    }
                    if (formatsObj.has("application/pdf")) {
                        pdfUrl = formatsObj.get("application/pdf")?.asString
                    } else if (formatsObj.has("text/plain; charset=utf-8")) {
                        pdfUrl = formatsObj.get("text/plain; charset=utf-8")?.asString
                    }
                }

                if (epubUrl == null && id > 0) {
                    epubUrl = "https://www.gutenberg.org/cache/epub/$id/pg$id.epub"
                }
                if (pdfUrl == null && id > 0) {
                    pdfUrl = "https://www.gutenberg.org/cache/epub/$id/pg$id.txt"
                }

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

            val targetUrl = if (isEpub) (book.epubUrl ?: "https://www.gutenberg.org/cache/epub/${book.id}/pg${book.id}.epub")
                            else (book.pdfUrl ?: "https://www.gutenberg.org/cache/epub/${book.id}/pg${book.id}.txt")

            val url = java.net.URL(targetUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true

            if (conn.responseCode in 200..399) {
                if (isEpub || targetUrl.endsWith(".pdf") || conn.contentType?.contains("pdf") == true) {
                    destFile.outputStream().use { output ->
                        conn.inputStream.use { input ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    val rawText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val epubData = EpubBook(book.title, book.author, listOf(rawText))
                    EpubParser.convertEpubToPdf(epubData, destFile)
                }

                android.media.MediaScannerConnection.scanFile(
                    ctx, arrayOf(destFile.absolutePath), null, null
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
    } catch (e: Exception) {
        "Could not extract text for this page."
    }
}

@Composable
fun TextView(doc: PdfDoc, page: Int, isDarkMode: Boolean, uri: Uri?) {
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
fun ContinuousPageView(
    doc: PdfDoc,
    isDarkMode: Boolean,
    pane: Pane,
    onPage: (Int) -> Unit,
    externalListState: androidx.compose.foundation.lazy.LazyListState? = null
) {
    val listState = externalListState ?: rememberLazyListState(initialFirstVisibleItemIndex = pane.page)

    LaunchedEffect(doc, pane.page) {
        if (!listState.isScrollInProgress && pane.page in 0 until doc.pageCount) {
            if (kotlin.math.abs(pane.page - listState.firstVisibleItemIndex) > 1 || listState.firstVisibleItemIndex == 0) {
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
        if (!listState.isScrollInProgress && kotlin.math.abs(pane.page - listState.firstVisibleItemIndex) > 1) {
            listState.scrollToItem(pane.page)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(
                count = doc.pageCount,
                key = { it }
            ) { index ->
                ContinuousPageItem(doc, index, widthPx, isDarkMode, pane, listState)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun ContinuousPageItem(doc: PdfDoc, index: Int, widthPx: Int, isDarkMode: Boolean, pane: Pane, listState: androidx.compose.foundation.lazy.LazyListState) {
    val ratio = doc.getPageRatio(index) ?: 0.707f // Default A4 ratio (1 / sqrt(2))
    
    var image by remember(doc, index) { mutableStateOf<ImageBitmap?>(null) }
    var ocrBoxes by remember(doc, index) { mutableStateOf<List<OcrBox>>(emptyList()) }
    var loading by remember(doc, index) { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(doc, index, widthPx, pane.zoom) {
        loading = true
        try {
            val targetW = renderWidth(widthPx, pane.zoom)
            
            val bmp = doc.render(index, targetW)
            bmp.prepareToDraw()
            image = bmp.asImageBitmap()
            loading = false
            
            // Wait for scroll to settle before doing OCR
            snapshotFlow { listState.isScrollInProgress }.first { !it }
            kotlinx.coroutines.delay(150)
            ocrBoxes = extractOcrBoxes(doc, index, bmp)
            
            doc.preloadRange(index, targetW, radius = 1)
        } catch (e: Exception) {
        } finally {
            loading = false
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

    val displayedW = with(LocalDensity.current) { (widthPx * pane.zoom).toDp() }

    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .pointerInput(index) {
                    detectTapGestures(
                        onTap = {
                            focusManager.clearFocus()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.width(displayedW)
            ) {
                image?.let { bmp ->
                    val displayedH = with(LocalDensity.current) { (displayedW.toPx() * (bmp.height.toFloat() / bmp.width.toFloat())).toDp() }

                    Image(
                        bitmap = bmp,
                        contentDescription = "Page ${index + 1}",
                        modifier = Modifier.width(displayedW),
                        contentScale = ContentScale.FillWidth,
                        filterQuality = FilterQuality.Medium,
                        colorFilter = colorFilter
                    )

                    Box(Modifier.matchParentSize()) {
                        ocrBoxes.forEach { box ->
                            Text(
                                text = box.text,
                                color = androidx.compose.ui.graphics.Color.Transparent,
                                fontSize = with(LocalDensity.current) { ((box.bottom - box.top) * displayedH.value * 0.85f).dp.toSp() },
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier
                                    .absoluteOffset(
                                        x = (box.left * displayedW.value).dp,
                                        y = (box.top * displayedH.value).dp
                                    )
                                    .width(((box.right - box.left) * displayedW.value).dp)
                                    .height(((box.bottom - box.top) * displayedH.value).dp)
                            )
                        }
                    }
                }
            }
        }
        if (image == null) {
            Box(Modifier.fillMaxSize().background(if (isDarkMode) androidx.compose.ui.graphics.Color(0xFF222222) else androidx.compose.ui.graphics.Color(0xFFEEEEEE)))
        }
    }
}

@Composable
fun GoogleWebView(url: String, onBackToVocab: () -> Unit = {}) {
    var webViewRef by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var currentLoadedUrl by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("Google Search") }

    LaunchedEffect(url) {
        if (url != currentLoadedUrl && url.isNotBlank()) {
            currentLoadedUrl = url
            webViewRef?.loadUrl(url)
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

    Column(modifier = Modifier.fillMaxSize()) {
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
                    onClick = { webViewRef?.reload() },
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
        AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.textZoom = 110
                    setInitialScale(110)
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageStarted(view: android.webkit.WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, pageUrl, favicon)
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                        }

                        override fun onPageFinished(view: android.webkit.WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                            pageTitle = view?.title ?: "Google Search"
                        }

                        override fun doUpdateVisitedHistory(view: android.webkit.WebView?, pageUrl: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, pageUrl, isReload)
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                        }
                    }
                    loadUrl(url)
                    currentLoadedUrl = url
                    webViewRef = this
                }
            },
            update = { webView ->
                webViewRef = webView
                if (url != currentLoadedUrl && url.isNotBlank()) {
                    currentLoadedUrl = url
                    webView.loadUrl(url)
                }
            },
            onRelease = { webView ->
                webView.destroy()
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
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
