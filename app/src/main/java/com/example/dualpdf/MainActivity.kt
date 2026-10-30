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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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

private val PDF_MIME = arrayOf("application/pdf")

/* =========================================================================
 *  PdfDoc — thin, thread-safe wrapper over PdfRenderer
 *  PdfRenderer allows only ONE open page at a time, so every render is
 *  serialised by a mutex. Pages are always closed in `finally`.
 * ========================================================================= */
class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val tempFile: File?,          // non-null when we had to copy a non-seekable stream
) {
    val pageCount: Int = renderer.pageCount
    private val mutex = Mutex()
    @Volatile private var closed = false

    /** Renders [index] as a white-backed ARGB bitmap, [targetWidth] px wide (clamped by MAX_DIM). */
    suspend fun render(index: Int, targetWidth: Int): Bitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed) { "Document is closed" }
            val page = renderer.openPage(index)
            try {
                val scale = min(targetWidth.toFloat() / page.width, MAX_DIM.toFloat() / page.height)
                val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AColor.WHITE)           // PdfRenderer needs an opaque background
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                page.close()
            }
        }
    }

    /** Waits for any in-flight render, then releases renderer, descriptor and temp copy. */
    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (!closed) {
                closed = true
                runCatching { renderer.close() }
                runCatching { pfd.close() }
                tempFile?.delete()
            }
        }
    }

    companion object {
        private const val MAX_DIM = 6000

        /** Opens [uri]; falls back to a cache copy if the provider's stream isn't seekable. */
        fun open(ctx: Context, uri: Uri): PdfDoc {
            if (uri.scheme == "file") {
                val file = File(uri.path!!)
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                return PdfDoc(pfd, PdfRenderer(pfd), null)
            }
            val resolver = ctx.contentResolver
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw IOException("The provider returned no file descriptor")
            try {
                return PdfDoc(pfd, PdfRenderer(pfd), null)
            } catch (e: Exception) {
                runCatching { pfd.close() }
                if (e !is IOException && e !is IllegalArgumentException) throw e
            }
            // Fallback: copy to private cache, then open the copy.
            val cache = File.createTempFile("pdf_", ".pdf", ctx.cacheDir)
            try {
                val input = resolver.openInputStream(uri)
                    ?: throw FileNotFoundException("Cannot open $uri")
                input.use { i -> cache.outputStream().use { o -> i.copyTo(o) } }
                val copyPfd = ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    return PdfDoc(copyPfd, PdfRenderer(copyPfd), cache)
                } catch (e: Exception) {
                    runCatching { copyPfd.close() }
                    throw e
                }
            } catch (e: Exception) {
                cache.delete()
                throw e
            }
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

    /** "The_Brothers_Karamazov.pdf" -> "The_Brothers_Karamazov_vocab.pdf" */
    fun expectedName(bookName: String): String =
        bookName.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "") + "_vocab.pdf"

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
class Pane {
    var doc by mutableStateOf<PdfDoc?>(null)
    var page by mutableIntStateOf(0)
    var name by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)   // error text shown in a card
}

data class Bookmark(val id: Long, val bookPage: Int, val vocabPage: Int, val name: String)
data class HistoryEntry(val uri: String, val name: String, val timeMs: Long)

class Reader(private val ctx: Context, private val scope: CoroutineScope) {
    private val prefs = ctx.getSharedPreferences("dual_pdf_reader", Context.MODE_PRIVATE)
    private val lock = Mutex()                      // serialises open/reload operations

    val book = Pane()
    val vocab = Pane()
    var expectedVocab by mutableStateOf<String?>(null)
        private set

    val bookmarks = mutableStateListOf<Bookmark>()
    val history = mutableStateListOf<HistoryEntry>()

    private var bookUri: Uri? = null
    private var bookKey = ""

    init {
        // Remove stale temp copies from earlier runs.
        ctx.cacheDir.listFiles { f -> f.name.startsWith("pdf_") }?.forEach { it.delete() }
    }

    private fun keyOf(uri: Uri) = uri.toString().hashCode().toUInt().toString(16)

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

    // ---- public entry points -------------------------------------------------

    private fun loadHistory() {
        history.clear()
        val json = prefs.getString("history", "[]")
        try {
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            val list: List<HistoryEntry> = Gson().fromJson(json, type)
            history.addAll(list.sortedByDescending { it.timeMs })
        } catch (e: Exception) {}
    }

    private fun saveHistory() {
        val json = Gson().toJson(history)
        prefs.edit().putString("history", json).apply()
    }

    private fun addToHistory(uri: Uri, name: String) {
        val uriStr = uri.toString()
        history.removeAll { it.uri == uriStr }
        history.add(0, HistoryEntry(uriStr, name, System.currentTimeMillis()))
        if (history.size > 50) history.removeAt(history.lastIndex)
        saveHistory()
    }
    
    fun removeFromHistory(entry: HistoryEntry) {
        history.remove(entry)
        saveHistory()
    }

    /** Reopen whatever was open last time. */
    fun restore() {
        loadBookmarks()
        loadHistory()
        
        val defaultExtracted = prefs.getBoolean("default_book_extracted", false)
        if (!defaultExtracted) {
            try {
                val destFile = File(ctx.getExternalFilesDir(null), "crime-and-punishment.pdf")
                ctx.assets.open("crime-and-punishment.pdf").use { i ->
                    destFile.outputStream().use { o -> i.copyTo(o) }
                }
                prefs.edit().putBoolean("default_book_extracted", true).apply()
                val uri = Uri.fromFile(destFile)
                addToHistory(uri, "Crime and Punishment")
                
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

    fun onBookPicked(uri: Uri) { persist(uri); openBook(uri) }

    fun onVocabPicked(uri: Uri) {
        if (bookUri == null) return
        persist(uri)
        prefs.edit().putString("vocab_uri_$bookKey", uri.toString()).apply()
        scope.launch { lock.withLock { loadVocab() } }
    }
    
    fun getBookUri(): Uri? = bookUri

    fun generateVocab(onProgress: (Int, Int) -> Unit, onDone: (File) -> Unit, onError: (Exception) -> Unit) {
        val uri = bookUri ?: return
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Copy the PDF to a local temp file because pdfbox needs a file
                val inputPdf = File.createTempFile("book_in", ".pdf", ctx.cacheDir)
                ctx.contentResolver.openInputStream(uri)?.use { i ->
                    inputPdf.outputStream().use { o -> i.copyTo(o) }
                }
                
                // 2. Output pdf
                val outputPdf = File(ctx.cacheDir, "vocab_output.pdf")
                
                // 3. Generate natively
                VocabGenerator.generate(ctx, inputPdf, outputPdf) { current, total ->
                    scope.launch(Dispatchers.Main) {
                        onProgress(current, total)
                    }
                }
                
                inputPdf.delete()
                withContext(Dispatchers.Main) {
                    onDone(outputPdf)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    fun generateOcr(onProgress: (Int, Int) -> Unit, onDone: (File) -> Unit, onError: (Exception) -> Unit) {
        val uri = bookUri ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val inputPdf = File.createTempFile("book_in", ".pdf", ctx.cacheDir)
                ctx.contentResolver.openInputStream(uri)?.use { i ->
                    inputPdf.outputStream().use { o -> i.copyTo(o) }
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
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            }
        }
    }

    fun onTreePicked(uri: Uri) {
        persist(uri)
        prefs.edit().putString("tree_uri", uri.toString()).apply()
        if (bookUri != null) scope.launch { lock.withLock { loadVocab() } }
    }

    private fun loadBookmarks() {
        bookmarks.clear()
        val json = prefs.getString("bookmarks_$bookKey", "[]")
        try {
            val type = object : TypeToken<List<Bookmark>>() {}.type
            val list: List<Bookmark> = Gson().fromJson(json, type)
            bookmarks.addAll(list)
        } catch (e: Exception) {}
    }
    
    fun addBookmark() {
        if (book.doc == null) return
        val b = Bookmark(System.currentTimeMillis(), book.page, vocab.page, "Book: ${book.page + 1} | Vocab: ${vocab.page + 1}")
        bookmarks.add(b)
        val json = Gson().toJson(bookmarks)
        prefs.edit().putString("bookmarks_$bookKey", json).apply()
    }

    fun removeBookmark(b: Bookmark) {
        bookmarks.remove(b)
        val json = Gson().toJson(bookmarks)
        prefs.edit().putString("bookmarks_$bookKey", json).apply()
    }

    fun setBookPage(i: Int) = setPage(book, "book_page_", i)
    fun setVocabPage(i: Int) = setPage(vocab, "vocab_page_", i)

    private fun setPage(pane: Pane, prefix: String, i: Int) {
        val d = pane.doc ?: return
        val p = i.coerceIn(0, d.pageCount - 1)
        pane.page = p
        prefs.edit().putInt(prefix + bookKey, p).apply()   // saved immediately, independently
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
            lock.withLock {
                closePane(book); closePane(vocab)
                vocab.message = null; vocab.name = null; expectedVocab = null
                bookUri = uri
                bookKey = keyOf(uri)
                book.message = null
                book.name = withContext(Dispatchers.IO) { Sidecar.displayName(ctx, uri) }
                    ?: uri.lastPathSegment ?: "book.pdf"
                    
                addToHistory(uri, book.name ?: "Unknown")
                
                try {
                    val doc = withContext(Dispatchers.IO) { PdfDoc.open(ctx, uri) }
                    if (doc.pageCount < 1) { doc.close(); throw IOException("The PDF has no pages") }
                    book.page = prefs.getInt("book_page_$bookKey", 0).coerceIn(0, doc.pageCount - 1)
                    book.doc = doc
                    prefs.edit().putString("last_book_uri", uri.toString()).apply()
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
        expectedVocab = expected
        vocab.name = expected
        vocab.message = null

        val saved = prefs.getString("vocab_uri_$bookKey", null)?.let(Uri::parse)
        val tree = prefs.getString("tree_uri", null)?.let(Uri::parse)
        val found = withContext(Dispatchers.IO) {
            saved?.takeIf { Sidecar.canOpen(ctx, it) } ?: Sidecar.find(ctx, uri, expected, tree)
        }
        if (found == null) {
            vocab.message = "Could not find \"$expected\" next to the book."
            return
        }
        try {
            val doc = withContext(Dispatchers.IO) { PdfDoc.open(ctx, found) }
            if (doc.pageCount < 1) { doc.close(); throw IOException("The vocabulary PDF has no pages") }
            vocab.name = withContext(Dispatchers.IO) { Sidecar.displayName(ctx, found) } ?: expected
            vocab.page = prefs.getInt("vocab_page_$bookKey", 0).coerceIn(0, doc.pageCount - 1)
            vocab.doc = doc
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            vocab.message = describe(e)
        }
    }

    private suspend fun closePane(p: Pane) {
        val d = p.doc
        p.doc = null          // UI stops using it first
        d?.close()            // then wait for in-flight render and release
    }
}

/* =========================================================================
 *  Activity
 * ========================================================================= */
class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var reader: Reader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = Reader(applicationContext, scope)
        reader.restore()
        setContent { AppTheme { ReaderScreen(reader) } }
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

@Composable
fun ReaderScreen(reader: Reader) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    
    var isGenerating by remember { mutableStateOf(false) }
    var generatedFile by remember { mutableStateOf<File?>(null) }
    var genProgressCurrent by remember { mutableIntStateOf(0) }
    var genProgressTotal by remember { mutableIntStateOf(0) }
    
    var isOcrGenerating by remember { mutableStateOf(false) }
    var ocrProgressCurrent by remember { mutableIntStateOf(0) }
    var ocrProgressTotal by remember { mutableIntStateOf(0) }

    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            reader.setBookPage(b.bookPage)
                                            reader.setVocabPage(b.vocabPage)
                                            showBookmarks = false
                                        }
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(b.name, style = MaterialTheme.typography.bodyLarge)
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
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            reader.onBookPicked(Uri.parse(h.uri))
                                            showHistory = false
                                        }
                                        .padding(vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(h.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { reader.removeFromHistory(h) }) {
                                        Text("Del", color = MaterialTheme.colorScheme.error)
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
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("pdfHelperReader", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showHistory = true }) { Text("H") }
                    if (reader.book.doc != null) {
                        TextButton(onClick = {
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
                                        val ocrDir = File(downloadsDir, "ocr_files")
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
                        }) { Text("O") }
                        TextButton(onClick = { reader.addBookmark() }) { Text("B") }
                        TextButton(onClick = { showBookmarks = true }) { Text("BL") }
                    }
                    FilledTonalButton(
                        onClick = { pickBook.launch(PDF_MIME) },
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) { Text("Open book") }
                }
                HorizontalDivider()

            // ---- TOP: book ----
            PdfPane(
                label = "BOOK",
                pane = reader.book,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onPage = reader::setBookPage
            ) {
                MessageCard(
                    title = if (reader.book.message != null) "Could not open the book" else "No book open",
                    body = reader.book.message ?: "Choose a PDF to start reading."
                ) {
                    Button(onClick = { pickBook.launch(PDF_MIME) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open book")
                    }
                }
            }

            HorizontalDivider(thickness = 3.dp, color = MaterialTheme.colorScheme.primary)

            // ---- BOTTOM: vocabulary sidecar ----
            PdfPane(
                label = "VOCAB",
                pane = reader.vocab,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                onPage = reader::setVocabPage
            ) {
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
                        Button(onClick = {
                            isGenerating = true
                            genProgressCurrent = 0
                            genProgressTotal = 0
                            reader.generateVocab(
                                onProgress = { c, t -> 
                                    genProgressCurrent = c
                                    genProgressTotal = t
                                },
                                onDone = { generatedTempFile ->
                                    try {
                                        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                        val vocabDir = File(downloadsDir, "vocab_files")
                                        if (!vocabDir.exists()) {
                                            vocabDir.mkdirs()
                                        }
                                        val expectedName = reader.expectedVocab ?: "vocab.pdf"
                                        val destFile = File(vocabDir, expectedName)
                                        if (destFile.exists()) {
                                            destFile.delete()
                                        }
                                        generatedTempFile.copyTo(destFile, overwrite = true)
                                        generatedTempFile.delete()
                                        
                                        // Tell Android's MediaScanner about the new file so it's fully accessible to the user
                                        android.media.MediaScannerConnection.scanFile(
                                            ctx,
                                            arrayOf(destFile.absolutePath),
                                            arrayOf("application/pdf"),
                                            null
                                        )
                                        
                                        reader.onVocabPicked(Uri.fromFile(destFile))
                                    } catch (e: Exception) {
                                        Toast.makeText(ctx, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                    isGenerating = false
                                },
                                onError = {
                                    isGenerating = false
                                    Toast.makeText(ctx, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            )
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Generate Vocab PDF")
                        }
                        Button(onClick = { pickVocab.launch(PDF_MIME) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Choose vocab PDF")
                        }
                        OutlinedButton(onClick = { pickTree.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Grant folder access")
                        }
                    }
                }
            } // End of PdfPane
            } // End of Column

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
                        }
                    }
                }
            }
        } // End of Box
    } // End of Surface
} // End of ReaderScreen

@Composable
fun PdfPane(
    label: String,
    pane: Pane,
    modifier: Modifier,
    onPage: (Int) -> Unit,
    empty: @Composable () -> Unit,
) {
    Column(modifier) {
        PaneBar(label, pane, onPage)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val doc = pane.doc
            if (doc != null) {
                PageView(doc, pane.page)
            } else {
                Box(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    contentAlignment = Alignment.Center
                ) { empty() }
            }
        }
    }
}

/** Per-pane navigation: Prev / "n / total" (tap to jump) / Next. */
@Composable
fun PaneBar(label: String, pane: Pane, onPage: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf(false) }
    val count = pane.doc?.pageCount ?: 0
    val pad = PaddingValues(horizontal = 8.dp)

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(pane.name ?: "—", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clickable { showNameDialog = true })
            TextButton(enabled = count > 0 && pane.page > 0, contentPadding = pad,
                onClick = { onPage(pane.page - 1) }) { Text("‹ Prev") }
            TextButton(enabled = count > 0, contentPadding = pad,
                onClick = { showDialog = true }) {
                Text(if (count > 0) "${pane.page + 1} / $count" else "– / –")
            }
            TextButton(enabled = count > 0 && pane.page < count - 1, contentPadding = pad,
                onClick = { onPage(pane.page + 1) }) { Text("Next ›") }
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

/** Renders one page fit-to-width inside a vertically scrollable area. */
@Composable
fun PageView(doc: PdfDoc, page: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        var image by remember(doc) { mutableStateOf<ImageBitmap?>(null) }
        var error by remember(doc) { mutableStateOf<String?>(null) }
        var loading by remember(doc) { mutableStateOf(true) }
        val scroll = rememberScrollState()

        LaunchedEffect(page) { scroll.scrollTo(0) }

        LaunchedEffect(doc, page, widthPx) {
            loading = true
            error = null
            try {
                // 1.5x oversampling keeps small vocabulary text crisp.
                val bmp = doc.render(page, (widthPx * 1.5f).toInt().coerceIn(200, 2048))
                image = bmp.asImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Could not render this page"
            } finally {
                loading = false
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = "PDF page ${page + 1}",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                    filterQuality = FilterQuality.High
                )
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(16.dp))
        }
    }
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
