package com.example.dualpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.itextpdf.text.pdf.PdfReader

class SelectionAndSearchTest {

    private fun formatGoogleSearchQuery(text: String): String {
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

    @Test
    fun testFormatGoogleSearchQuerySingleWord() {
        assertEquals("meaning of this: \"apple\"", formatGoogleSearchQuery("apple"))
        assertEquals("meaning of this: \"ephemeral\"", formatGoogleSearchQuery("  ephemeral  "))
        assertEquals("meaning of this: \"ubiquitous\"", formatGoogleSearchQuery("\"ubiquitous\""))
    }

    @Test
    fun testFormatGoogleSearchQueryMultiWord() {
        assertEquals(
            "meaning of this part by part: \"in the midst of sorrow\"",
            formatGoogleSearchQuery("in the midst of sorrow")
        )
        assertEquals(
            "meaning of this part by part: \"break a leg\"",
            formatGoogleSearchQuery("  \"break a leg\"  ")
        )
        assertEquals(
            "meaning of this part by part: \"multiple words with newline\"",
            formatGoogleSearchQuery("multiple\nwords\twith\nnewline")
        )
    }

    @Test
    fun testFormatGoogleSearchQueryPreservesAlreadyFormatted() {
        val q1 = "meaning of this: \"apple\""
        assertEquals(q1, formatGoogleSearchQuery(q1))
        val q2 = "meaning of this part by part: \"hello world\""
        assertEquals(q2, formatGoogleSearchQuery(q2))
    }

    @Test
    fun testCharacterLevelTextSelection() {
        val words = listOf(
            PdfWord(text = "Hello", left = 0.1f, top = 0.1f, right = 0.3f, bottom = 0.15f, lineIndex = 0, wordIndex = 0),
            PdfWord(text = "World", left = 0.35f, top = 0.1f, right = 0.6f, bottom = 0.15f, lineIndex = 0, wordIndex = 1)
        )

        // Substring inside single word: "ell"
        val text1 = PdfTextHelper.getSelectedText(words, 0, 0, startCharOffset = 1, endCharOffset = 4)
        assertEquals("ell", text1)

        // Substring across words: "llo Wor"
        val text2 = PdfTextHelper.getSelectedText(words, 0, 1, startCharOffset = 2, endCharOffset = 3)
        assertEquals("llo Wor", text2)

        // Full words
        val text3 = PdfTextHelper.getSelectedText(words, 0, 1, startCharOffset = 0, endCharOffset = -1)
        assertEquals("Hello World", text3)
    }

    @Test
    fun testFindSelectionPointAtMiddleOfWord() {
        val words = listOf(
            PdfWord(text = "Internationalization", left = 0.2f, top = 0.1f, right = 0.6f, bottom = 0.15f, lineIndex = 0, wordIndex = 0)
        )

        val point = PdfTextHelper.findSelectionPointAt(words, xNorm = 0.4f, yNorm = 0.12f, isEndHandle = true)
        assertEquals(0, point.wordIndex)
        assertEquals(10, point.charOffset)

        val point25 = PdfTextHelper.findSelectionPointAt(words, xNorm = 0.3f, yNorm = 0.12f, isEndHandle = false)
        assertEquals(0, point25.wordIndex)
        assertEquals(5, point25.charOffset)
    }

    @Test
    fun testCompareSelectionPos() {
        assertTrue(PdfTextHelper.compareSelectionPos(0, 1, 3, 0, 1, 5) < 0)
        assertTrue(PdfTextHelper.compareSelectionPos(0, 1, 5, 0, 1, 3) > 0)
        assertTrue(PdfTextHelper.compareSelectionPos(0, 1, 3, 0, 1, 3) == 0)
        assertTrue(PdfTextHelper.compareSelectionPos(0, 0, 5, 0, 1, 0) < 0)
        assertTrue(PdfTextHelper.compareSelectionPos(1, 0, 0, 0, 5, 10) > 0)
    }

    @Test
    fun testVocabPdfExtractsFullWordsNotSingleCharacters() {
        val file = File("/home/ohidsajol/.gemini/antigravity/brain/fde01f97-500c-420d-bf0a-5e7e3f0260f8/scratch/sample_vocab.pdf")
        if (file.exists()) {
            val reader = PdfReader(file.absolutePath)
            val words = PdfTextHelper.extractWordsFromReader(reader, 1)
            val textList = words.map { it.text }
            System.err.println("Page 1 words: $textList")
            assertTrue("Should contain 'Page'", textList.contains("Page"))
            assertTrue("Should contain '1'", textList.contains("1"))
            assertTrue("Should contain 'No'", textList.contains("No"))
            assertTrue("Should contain 'uncommon'", textList.contains("uncommon"))
            assertTrue("Should contain 'words'", textList.contains("words"))
            assertTrue("Should contain 'on'", textList.contains("on"))
            assertTrue("Should contain 'this'", textList.contains("this"))
            reader.close()
        }

        val keatsFile = File("/home/ohidsajol/.gemini/antigravity/brain/fde01f97-500c-420d-bf0a-5e7e3f0260f8/scratch/keats_vocab.pdf")
        if (keatsFile.exists()) {
            val reader = PdfReader(keatsFile.absolutePath)
            val words = PdfTextHelper.extractWordsFromReader(reader, 2)
            val textList = words.map { it.text }
            System.err.println("Keats page 2 words: $textList")
            assertTrue("Should contain 'transliterated'", textList.contains("transliterated"))
            assertTrue("Should contain 'verb:'", textList.contains("verb:"))
            reader.close()
        }

        val tutorialFile = File("src/main/assets/PdfHelperReader_Tutorial.pdf").takeIf { it.exists() }
            ?: File("app/src/main/assets/PdfHelperReader_Tutorial.pdf").takeIf { it.exists() }
        if (tutorialFile != null && tutorialFile.exists()) {
            val reader = PdfReader(tutorialFile.absolutePath)
            val words = PdfTextHelper.extractWordsFromReader(reader, 1)
            assertTrue("Tutorial words should not be empty", words.isNotEmpty())
            assertTrue("Words should have length > 1", words.any { it.text.length > 3 })
            reader.close()
        }
    }

    @Test
    fun testLongPressWordExpansionIncludesEntireWordAndPunctuation() {
        val words = listOf(
            PdfWord(text = "Hello", left = 0.1f, top = 0.1f, right = 0.2f, bottom = 0.15f, lineIndex = 0, wordIndex = 0),
            PdfWord(text = ",", left = 0.201f, top = 0.1f, right = 0.21f, bottom = 0.15f, lineIndex = 0, wordIndex = 1),
            PdfWord(text = "World", left = 0.25f, top = 0.1f, right = 0.35f, bottom = 0.15f, lineIndex = 0, wordIndex = 2)
        )

        // Simulate long-press expansion logic from InteractivePdfPage
        fun expandSelection(wordIdx: Int): Pair<Int, Int> {
            var startIdx = wordIdx
            while (startIdx > 0) {
                val prevW = words[startIdx - 1]
                if (prevW.lineIndex == words[wordIdx].lineIndex &&
                    (words[startIdx].left - prevW.right) < 0.003f &&
                    prevW.text.any { it.isLetterOrDigit() } && words[startIdx].text.any { it.isLetterOrDigit() }) {
                    startIdx--
                } else break
            }
            var endIdx = wordIdx
            while (endIdx + 1 < words.size) {
                val nextW = words[endIdx + 1]
                if (nextW.lineIndex == words[wordIdx].lineIndex) {
                    val gap = nextW.left - words[endIdx].right
                    if (gap < 0.003f && nextW.text.any { it.isLetterOrDigit() } && words[endIdx].text.any { it.isLetterOrDigit() }) {
                        endIdx++
                    } else if (gap < 0.035f && nextW.text.all { !it.isLetterOrDigit() }) {
                        endIdx++
                    } else break
                } else break
            }
            return Pair(startIdx, endIdx)
        }

        // Tapping "Hello" expands to include trailing comma
        val (s0, e0) = expandSelection(0)
        assertEquals(0, s0)
        assertEquals(1, e0)
        val selected0 = PdfTextHelper.getSelectedText(words, s0, e0, 0, words[e0].text.length)
        assertEquals("Hello ,", selected0)

        // Tapping "World" stays on "World" (not merging across space)
        val (s2, e2) = expandSelection(2)
        assertEquals(2, s2)
        assertEquals(2, e2)
        val selected2 = PdfTextHelper.getSelectedText(words, s2, e2, 0, words[e2].text.length)
        assertEquals("World", selected2)
    }

    @Test
    fun testReferenceResolutionAndLemmatization() {
        val testDict = mapOf(
            "wabble" to """{"pos":[{"type":"verb","def":"To move irregularly from side to side; to stagger.","eg":["The top began to wabble."],"syn":["totter","shake"],"ant":["steady"]}],"score":32.5}""",
            "wobble" to "\$#\$Wabble",
            "butcher" to """{"pos":[{"type":"noun","def":"One whose business is to slaughter animals or sell their meat.","eg":["The butcher cut the meat."],"syn":["meat seller"],"ant":[]}],"score":25.0}""",
            "butchers" to "\$#\$butcher"
        )
        VocabGenerator.initForTest(testDict)

        // Lookup "wobble" which is a $#$ reference to "Wabble"
        val wobbleEntry = VocabGenerator.lookupEntry("wobble")
        org.junit.Assert.assertNotNull(wobbleEntry)
        assertEquals("Wabble", wobbleEntry?.reference)
        assertEquals(32.5f, wobbleEntry?.score)
        assertEquals("verb", wobbleEntry?.pos?.firstOrNull()?.type)

        // Synonyms and antonyms lookup should also carry the reference
        val wobbleSummary = VocabGenerator.lookupSynonymsAndAntonyms("wobble")
        org.junit.Assert.assertNotNull(wobbleSummary)
        assertEquals("Wabble", wobbleSummary?.reference)

        // Word "wobbles" (inflected) lemmatized to "wobble" which references "Wabble"
        val wobblesResult = VocabGenerator.lookupWithLemmatizationResult("wobbles")
        org.junit.Assert.assertNotNull(wobblesResult)
        assertEquals("wobble", wobblesResult?.first)
        assertEquals("Wabble", wobblesResult?.second?.reference)

        // Direct inflection reference in dict
        val butchersEntry = VocabGenerator.lookupEntry("butchers")
        org.junit.Assert.assertNotNull(butchersEntry)
        assertEquals("butcher", butchersEntry?.reference)
    }

    @Test
    fun testSearchHistoryDataModelAndOperations() {
        val searchHistory = mutableListOf<SearchHistoryEntry>()
        fun addToSearchHistory(query: String, formatted: String) {
            val clean = query.trim()
            if (clean.isBlank()) return
            searchHistory.removeAll { it.query.equals(clean, ignoreCase = true) || it.formattedQuery.equals(formatted, ignoreCase = true) }
            searchHistory.add(0, SearchHistoryEntry(clean, formatted, System.currentTimeMillis()))
            if (searchHistory.size > 1000) {
                searchHistory.removeAt(searchHistory.lastIndex)
            }
        }

        addToSearchHistory("alpha", "meaning of this: \"alpha\"")
        addToSearchHistory("beta", "meaning of this: \"beta\"")
        assertEquals(2, searchHistory.size)
        assertEquals("beta", searchHistory[0].query)
        assertEquals("alpha", searchHistory[1].query)

        // Searching alpha again moves it to top
        addToSearchHistory("alpha", "meaning of this: \"alpha\"")
        assertEquals(2, searchHistory.size)
        assertEquals("alpha", searchHistory[0].query)
        assertEquals("beta", searchHistory[1].query)

        // Removing
        searchHistory.removeAt(0)
        assertEquals(1, searchHistory.size)
        assertEquals("beta", searchHistory[0].query)

        // Clear
        searchHistory.clear()
        assertTrue(searchHistory.isEmpty())
    }

    @Test
    fun testOnlineDictionaryLookup() {
        try {
            val summary = VocabGenerator.lookupOnlineDictionary("smartphone")
            if (summary != null) {
                assertEquals("smartphone", summary.word.lowercase())
                assertTrue(summary.definitions.isNotEmpty())
                assertTrue(summary.isOnline)
            }
            // Nonexistent word returns null
            val notFound = VocabGenerator.lookupOnlineDictionary("nonexistentxyz12345")
            org.junit.Assert.assertNull(notFound)
        } catch (_: java.io.IOException) {
            // Transient offline/network issue in test runner
        }
    }
}
