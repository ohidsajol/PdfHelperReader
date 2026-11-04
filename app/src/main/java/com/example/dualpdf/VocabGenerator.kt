package com.example.dualpdf

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

data class PosEntry(
    val type: String,
    val def: String,
    val eg: List<String> = emptyList(),
    val syn: List<String> = emptyList(),
    val ant: List<String> = emptyList()
)

data class WordEntry(
    val pos: List<PosEntry>
)

data class VocabResult(
    val outputPdf: File,
    val pageMap: Map<Int, Int> // bookPage -> vocabPage
)

object VocabGenerator {

    private var commonWords = setOf<String>()
    private var dictionary = mapOf<String, String>()
    private val gson = Gson()
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

        // Stream and load fine-tuned master JSON dictionary efficiently
        val map = HashMap<String, String>(1000000)
        ctx.assets.open("dictionary_master.json").use { input ->
            InputStreamReader(input, "UTF-8").use { isr ->
                val reader = JsonReader(isr)
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName().lowercase()
                    val token = reader.peek()
                    if (token == JsonToken.BEGIN_OBJECT) {
                        val element = JsonParser.parseReader(reader)
                        map[key] = element.toString()
                    } else if (token == JsonToken.STRING) {
                        map[key] = reader.nextString()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        dictionary = map
        initialized = true
    }

    private fun parseStringList(element: JsonElement?): List<String> {
        if (element == null || element.isJsonNull) return emptyList()
        val result = mutableListOf<String>()
        when {
            element.isJsonPrimitive -> {
                val str = element.asString.trim()
                if (str.isNotEmpty()) result.add(str)
            }
            element.isJsonArray -> {
                for (item in element.asJsonArray) {
                    result.addAll(parseStringList(item))
                }
            }
            element.isJsonObject -> {
                for ((_, value) in element.asJsonObject.entrySet()) {
                    result.addAll(parseStringList(value))
                }
            }
        }
        return result.filter { it.isNotBlank() }
    }

    private fun parsePosEntry(item: JsonObject): PosEntry? {
        val posType = item.get("type")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
        val posDef = item.get("def")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""

        val egList = parseStringList(item.get("eg"))
        val synList = parseStringList(item.get("syn"))
        val antList = parseStringList(item.get("ant"))

        if (posType.isEmpty() && posDef.isEmpty() && egList.isEmpty() && synList.isEmpty() && antList.isEmpty()) {
            return null
        }

        return PosEntry(
            type = posType,
            def = posDef,
            eg = egList,
            syn = synList,
            ant = antList
        )
    }

    private fun parseWordEntry(element: JsonElement?): WordEntry? {
        if (element == null || element.isJsonNull) return null

        var currentElement = element

        val prim = currentElement?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        if (prim != null && prim.isString) {
            val str = prim.asString.trim()
            if (str.startsWith("{")) {
                try {
                    currentElement = gson.fromJson(str, JsonElement::class.java)
                } catch (_: Exception) {
                    return null
                }
            }
        }

        val targetObj = currentElement?.takeIf { it.isJsonObject }?.asJsonObject
        if (targetObj != null) {
            val posArray = targetObj.getAsJsonArray("pos") ?: return null
            val parsedList = mutableListOf<PosEntry>()
            for (posElem in posArray) {
                if (posElem.isJsonObject) {
                    val posEntry = parsePosEntry(posElem.asJsonObject)
                    if (posEntry != null) {
                        parsedList.add(posEntry)
                    }
                }
            }
            if (parsedList.isNotEmpty()) {
                return WordEntry(pos = parsedList)
            }
        }

        return null
    }

    fun lookupEntry(
        word: String,
        visited: MutableSet<String> = mutableSetOf()
    ): WordEntry? {
        val wLower = word.lowercase().trim()
        if (wLower in visited) return null
        visited.add(wLower)

        val raw = dictionary[wLower] ?: return null

        if (raw.startsWith("\$#\$")) {
            val ref = raw.removePrefix("\$#\$").trim().lowercase()
            val refClean = ref.split(",", "|", "under").firstOrNull()?.trim() ?: ref
            val refNormalized = refClean.replace("æ", "e").replace("œ", "e")

            var entry = lookupEntry(refClean, visited)
            if (entry == null && refNormalized != refClean) {
                entry = lookupEntry(refNormalized, visited)
            }
            if (entry == null && refClean.startsWith("n")) {
                entry = lookupEntry("m" + refClean.substring(1), visited)
            }
            if (entry == null) {
                entry = lookupEntry(ref, visited)
            }
            return entry
        }

        return try {
            val element = JsonParser.parseString(raw)
            parseWordEntry(element)
        } catch (_: Exception) {
            null
        }
    }

    fun lookupWithLemmatization(word: String): WordEntry? {
        val wLower = word.lowercase().trim()
        var entry = lookupEntry(wLower)
        if (entry != null) return entry

        val candidates = mutableListOf<String>()

        if (wLower.endsWith("s") && wLower.length > 3) {
            candidates.add(wLower.dropLast(1))
            if (wLower.endsWith("es") && wLower.length > 4) {
                candidates.add(wLower.dropLast(2))
            }
            if (wLower.endsWith("ies") && wLower.length > 4) {
                candidates.add(wLower.dropLast(3) + "y")
            }
        }

        if (wLower.endsWith("ed") && wLower.length > 4) {
            candidates.add(wLower.dropLast(2))
            candidates.add(wLower.dropLast(1))
            if (wLower.endsWith("ied")) {
                candidates.add(wLower.dropLast(3) + "y")
            }
        }

        if (wLower.endsWith("ing") && wLower.length > 5) {
            candidates.add(wLower.dropLast(3))
            candidates.add(wLower.dropLast(3) + "e")
        }

        if (wLower.endsWith("ly") && wLower.length > 4) {
            candidates.add(wLower.dropLast(2))
            if (wLower.endsWith("ily")) {
                candidates.add(wLower.dropLast(3) + "y")
            }
        }

        for (cand in candidates) {
            entry = lookupEntry(cand)
            if (entry != null) return entry
        }

        return null
    }

    private fun buildPosSpannable(pos: PosEntry): SpannableStringBuilder {
        val sb = SpannableStringBuilder()

        // 1. Part of Speech Badge / Label
        if (pos.type.isNotBlank()) {
            val start = sb.length
            sb.append("• ").append(pos.type.lowercase()).append(": ")
            sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.rgb(45, 55, 72)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 2. Definition
        if (pos.def.isNotBlank()) {
            val start = sb.length
            sb.append(pos.def)
            sb.setSpan(ForegroundColorSpan(Color.rgb(45, 55, 72)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 3. Example(s)
        if (pos.eg.isNotEmpty()) {
            val egText = pos.eg.joinToString(" ") { it.trim() }
            if (egText.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n  ")
                val start = sb.length
                sb.append("e.g. \"").append(egText).append("\"")
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.rgb(74, 85, 104)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // 4. Synonyms
        if (pos.syn.isNotEmpty()) {
            val synText = pos.syn.joinToString(", ") { it.trim() }
            if (synText.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n  ")
                val labelStart = sb.length
                sb.append("Synonyms: ")
                sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), labelStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.rgb(43, 108, 176)), labelStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                val valStart = sb.length
                sb.append(synText)
                sb.setSpan(ForegroundColorSpan(Color.rgb(43, 108, 176)), valStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // 5. Antonyms
        if (pos.ant.isNotEmpty()) {
            val antText = pos.ant.joinToString(", ") { it.trim() }
            if (antText.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n  ")
                val labelStart = sb.length
                sb.append("Antonyms: ")
                sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), labelStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.rgb(197, 48, 48)), labelStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                val valStart = sb.length
                sb.append(antText)
                sb.setSpan(ForegroundColorSpan(Color.rgb(113, 128, 150)), valStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        return sb
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
            color = Color.rgb(45, 55, 72)
            textSize = 11f
            isAntiAlias = true
        }
        val wordPaint = TextPaint().apply {
            color = Color.rgb(26, 54, 93) // deep navy
            textSize = 13f
            isFakeBoldText = true
            isAntiAlias = true
        }
        val headerPaint = TextPaint().apply {
            color = Color.rgb(43, 108, 176) // blue
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
                val validWords = mutableListOf<Pair<String, WordEntry>>()
                val seen = mutableSetOf<String>()

                for (word in tokens) {
                    if (seen.contains(word)) continue
                    if (commonWords.contains(word)) continue

                    val entry = lookupWithLemmatization(word)
                    if (entry != null && entry.pos.isNotEmpty()) {
                        seen.add(word)
                        validWords.add(word to entry)
                    }
                }

                // Draw Page
                var pageInfo = PdfDocument.PageInfo.Builder(612, 792, vocabPageNum).create()
                var page = androidDoc.startPage(pageInfo)
                var canvas = page.canvas

                var yOffset = 50f
                val margin = 50f
                val textWidth = 612f - (margin * 2) // 512f
                val pageBottomLimit = 740f

                // Draw header
                canvas.drawText("Page $i", margin, yOffset, headerPaint)
                yOffset += 30f

                if (validWords.isEmpty()) {
                    canvas.drawText("No uncommon words on this page.", margin, yOffset, noWordsPaint)
                    androidDoc.finishPage(page)
                    vocabPageNum++
                } else {
                    for ((word, entry) in validWords) {
                        val wordHeaderHeight = 18f

                        // Prepare StaticLayouts for all POS entries
                        val posLayouts = mutableListOf<StaticLayout>()
                        var totalWordEntryHeight = wordHeaderHeight

                        for (pos in entry.pos) {
                            val span = buildPosSpannable(pos)
                            val sl = StaticLayout.Builder.obtain(span, 0, span.length, textPaint, textWidth.toInt())
                                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                                .setLineSpacing(2f, 1f)
                                .setIncludePad(false)
                                .build()
                            posLayouts.add(sl)
                            totalWordEntryHeight += sl.height + 8f
                        }
                        totalWordEntryHeight += 10f // gap between words

                        // Page overflow check for entire word
                        if (yOffset + totalWordEntryHeight > pageBottomLimit && yOffset > 80f) {
                            androidDoc.finishPage(page)
                            vocabPageNum++

                            pageInfo = PdfDocument.PageInfo.Builder(612, 792, vocabPageNum).create()
                            page = androidDoc.startPage(pageInfo)
                            canvas = page.canvas
                            yOffset = 50f

                            canvas.drawText("Page $i (continued)", margin, yOffset, headerPaint)
                            yOffset += 30f
                        }

                        // Draw Word Headword (Bold Navy)
                        canvas.drawText(word, margin, yOffset, wordPaint)
                        yOffset += wordHeaderHeight

                        // Draw each POS block
                        for (sl in posLayouts) {
                            if (yOffset + sl.height > pageBottomLimit && yOffset > 80f) {
                                androidDoc.finishPage(page)
                                vocabPageNum++

                                pageInfo = PdfDocument.PageInfo.Builder(612, 792, vocabPageNum).create()
                                page = androidDoc.startPage(pageInfo)
                                canvas = page.canvas
                                yOffset = 50f

                                canvas.drawText("Page $i (continued)", margin, yOffset, headerPaint)
                                yOffset += 30f

                                canvas.drawText("$word (cont.)", margin, yOffset, wordPaint)
                                yOffset += wordHeaderHeight
                            }

                            canvas.save()
                            canvas.translate(margin, yOffset)
                            sl.draw(canvas)
                            canvas.restore()

                            yOffset += sl.height + 8f
                        }

                        yOffset += 10f // gap after word entry
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
