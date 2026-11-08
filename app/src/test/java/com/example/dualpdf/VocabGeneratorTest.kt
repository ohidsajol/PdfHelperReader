package com.example.dualpdf

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileReader

data class TestPosEntry(
    val type: String,
    val def: String,
    val eg: List<String> = emptyList(),
    val syn: List<String> = emptyList(),
    val ant: List<String> = emptyList()
)

data class TestWordEntry(
    val pos: List<TestPosEntry>,
    val score: Float? = null
)

class VocabGeneratorTest {

    companion object {
        private lateinit var dictionary: Map<String, String>
        private val gson = Gson()

        @JvmStatic
        @BeforeClass
        fun setUp() {
            val file = File("src/main/assets/dictionary_master.json")
            val targetFile = if (file.exists()) file else File("app/src/main/assets/dictionary_master.json")
            assertTrue("dictionary_master.json should exist", targetFile.exists())

            val map = HashMap<String, String>(1000000)
            FileReader(targetFile).use { fileReader ->
                val reader = JsonReader(fileReader)
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
            dictionary = map
            VocabGenerator.initWithMapForTesting(map)
            assertNotNull(dictionary)
            assertTrue(dictionary.isNotEmpty())
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

        private fun parsePosEntry(item: JsonObject): TestPosEntry? {
            val posType = item.get("type")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
            val posDef = item.get("def")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""

            val egList = parseStringList(item.get("eg"))
            val synList = parseStringList(item.get("syn"))
            val antList = parseStringList(item.get("ant"))

            if (posType.isEmpty() && posDef.isEmpty() && egList.isEmpty() && synList.isEmpty() && antList.isEmpty()) {
                return null
            }

            return TestPosEntry(
                type = posType,
                def = posDef,
                eg = egList,
                syn = synList,
                ant = antList
            )
        }

        private fun parseWordEntry(element: JsonElement?): TestWordEntry? {
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
                val parsedList = mutableListOf<TestPosEntry>()
                for (posElem in posArray) {
                    if (posElem.isJsonObject) {
                        val posEntry = parsePosEntry(posElem.asJsonObject)
                        if (posEntry != null) {
                            parsedList.add(posEntry)
                        }
                    }
                }
                val score = targetObj.get("score")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asFloat
                if (parsedList.isNotEmpty()) {
                    return TestWordEntry(pos = parsedList, score = score)
                }
            }

            return null
        }

        fun lookupEntry(
            word: String,
            visited: MutableSet<String> = mutableSetOf()
        ): TestWordEntry? {
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
    }

    @Test
    fun testAnopheles() {
        val entry = lookupEntry("anopheles")
        assertNotNull(entry)
        assertEquals(1, entry!!.pos.size)
        assertEquals("noun", entry.pos[0].type)
        assertTrue(entry.pos[0].def.contains("malaria"))
        assertEquals(1, entry.pos[0].eg.size)
        assertEquals(2, entry.pos[0].syn.size)
        assertEquals(29.25f, entry.score)
    }

    @Test
    fun testSarong() {
        val entry = lookupEntry("sarong")
        assertNotNull(entry)
        assertEquals(1, entry!!.pos.size)
        assertEquals("noun", entry.pos[0].type)
        assertEquals(28.38f, entry.score)
    }

    @Test
    fun testKnurly() {
        val entry = lookupEntry("knurly")
        assertNotNull(entry)
        assertEquals(1, entry!!.pos.size)
        assertEquals("adjective", entry.pos[0].type)
        assertEquals(1.0f, entry.score)
    }

    @Test
    fun testLookupSynonymsAndAntonyms() {
        val sarongSummary = VocabGenerator.lookupSynonymsAndAntonyms("sarong")
        assertNotNull(sarongSummary)
        assertEquals("sarong", sarongSummary!!.word)
        assertEquals(28.38f, sarongSummary.score)
        assertTrue(sarongSummary.definitions.isNotEmpty())
        assertEquals("noun", sarongSummary.definitions[0].type)
        assertTrue(sarongSummary.definitions[0].def.contains("garment"))
        assertTrue(sarongSummary.synonyms.contains("wrap"))
        assertTrue(sarongSummary.antonyms.contains("shirt"))

        val punctSummary = VocabGenerator.lookupSynonymsAndAntonyms("\"'sarong,'\"")
        assertNotNull(punctSummary)
        assertEquals("sarong", punctSummary!!.word)

        val pluralSummary = VocabGenerator.lookupSynonymsAndAntonyms("sarongs")
        assertNotNull(pluralSummary)
        assertTrue(pluralSummary!!.synonyms.contains("wrap"))
        assertTrue(pluralSummary.antonyms.contains("shirt"))

        val anophelesSummary = VocabGenerator.lookupSynonymsAndAntonyms("anopheles")
        assertNotNull(anophelesSummary)
        assertTrue(anophelesSummary!!.synonyms.isNotEmpty())
        assertTrue(anophelesSummary.antonyms.isEmpty())

        val nullSummary = VocabGenerator.lookupSynonymsAndAntonyms("xyznonexistentword123")
        org.junit.Assert.assertNull(nullSummary)
    }

    @Test
    fun testThresholdFilteringLogic() {
        val sarongEntry = VocabGenerator.lookupWithLemmatization("sarong")
        assertNotNull(sarongEntry)
        val score = sarongEntry!!.score ?: 0f
        assertEquals(28.38f, score)

        val threshold = 37f
        assertTrue("Sarong score (28.38) should be <= threshold 37", score <= threshold)

        val lowThreshold = 20f
        assertTrue("Sarong score (28.38) should NOT be <= threshold 20", score > lowThreshold)

        val ephemeralEntry = VocabGenerator.lookupWithLemmatization("ephemeral")
        assertNotNull(ephemeralEntry)
        val ephScore = ephemeralEntry!!.score ?: 0f
        assertEquals(37.38f, ephScore)
        assertTrue("Ephemeral (37.38) should NOT be included when threshold is 37", ephScore > 37f)
        assertTrue("Ephemeral (37.38) should be included when threshold is 38", ephScore <= 38f)
    }

    @Test
    fun testCheckUpdateRedirect() {
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

            if (conn.responseCode in 300..399 && location.isNotBlank()) {
                assertTrue("Location header should contain /tag/, got: $location", location.contains("/tag/"))
                val latestTag = location.substringAfterLast("/tag/").trim().split("/", "?", "#").first()
                assertTrue("latestTag should start with v, got: $latestTag", latestTag.startsWith("v"))
            }
        } catch (_: java.io.IOException) {
            // Transient network issue on GitHub endpoint in test environment
        }
    }

    @Test
    fun testMultiWordLookupReturnsNull() {
        val result = VocabGenerator.lookupSynonymsAndAntonyms("aberration and ubiquitous")
        org.junit.Assert.assertNull("Multi-word selection must not look up first word", result)
    }

    @Test
    fun testSingleWordLookupWorks() {
        val result = VocabGenerator.lookupSynonymsAndAntonyms("aberration")
        assertNotNull("Single word in dictionary should return summary", result)
        assertEquals("aberration", result?.word)
    }
}
