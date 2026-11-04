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
    val pos: List<TestPosEntry>
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
                if (parsedList.isNotEmpty()) {
                    return TestWordEntry(pos = parsedList)
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

        fun lookupWithLemmatization(word: String): TestWordEntry? {
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
                if (wLower.endsWith("ied") && wLower.length > 4) {
                    candidates.add(wLower.dropLast(3) + "y")
                }
            }

            if (wLower.endsWith("ing") && wLower.length > 5) {
                candidates.add(wLower.dropLast(3))
                candidates.add(wLower.dropLast(3) + "e")
            }

            if (wLower.endsWith("ly") && wLower.length > 4) {
                candidates.add(wLower.dropLast(2))
                if (wLower.endsWith("ily") && wLower.length > 4) {
                    candidates.add(wLower.dropLast(3) + "y")
                }
            }

            for (cand in candidates) {
                entry = lookupEntry(cand)
                if (entry != null) return entry
            }

            return null
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
    }

    @Test
    fun testSaline() {
        val entry = lookupEntry("saline")
        assertNotNull(entry)
        assertEquals(2, entry!!.pos.size)
        assertEquals("noun", entry.pos[0].type)
        assertEquals("adjective", entry.pos[1].type)
        assertTrue(entry.pos[1].ant.contains("freshwater"))
    }

    @Test
    fun testCursed() {
        val entry = lookupEntry("cursed")
        assertNotNull(entry)
        assertEquals(3, entry!!.pos.size)
        assertEquals("noun", entry.pos[0].type)
        assertEquals("verb", entry.pos[1].type)
        assertEquals("adjective", entry.pos[2].type)
    }

    @Test
    fun testRootReference() {
        // "uniclinal" points to "$#$Nonoclinal"
        val entry = lookupEntry("uniclinal")
        assertNotNull(entry)
        assertTrue(entry!!.pos.isNotEmpty())
    }

    @Test
    fun testLemmatization() {
        val entry = lookupWithLemmatization("salines")
        assertNotNull(entry)
        assertTrue(entry!!.pos.isNotEmpty())
    }
}
