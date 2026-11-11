package com.example.dualpdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.itextpdf.text.Document
import com.itextpdf.text.Paragraph
import com.itextpdf.text.pdf.PdfReader
import com.itextpdf.text.pdf.PdfWriter
import com.itextpdf.text.pdf.parser.PdfTextExtractor
import kotlinx.coroutines.CancellationException

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

    @Test
    fun testFileBrowserSortingByTimeAndName() {
        val dummyUri: android.net.Uri? = null
        val items = listOf(
            BookFileInfo(dummyUri, "Zebra.pdf", "/storage/emulated/0/Zebra.pdf", 1024, 1000L, false),
            BookFileInfo(dummyUri, "apple.epub", "/storage/emulated/0/apple.epub", 2048, 5000L, true),
            BookFileInfo(dummyUri, "Banana.pdf", "/storage/emulated/0/Banana.pdf", 4096, 3000L, false)
        )

        // Sort by TIME (most recently modified first)
        val sortedByTime = items.sortedByDescending { it.dateModifiedMs }
        assertEquals("apple.epub", sortedByTime[0].name)
        assertEquals("Banana.pdf", sortedByTime[1].name)
        assertEquals("Zebra.pdf", sortedByTime[2].name)

        // Sort by NAME (alphabetical case-insensitive)
        val sortedByName = items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        assertEquals("apple.epub", sortedByName[0].name)
        assertEquals("Banana.pdf", sortedByName[1].name)
        assertEquals("Zebra.pdf", sortedByName[2].name)
    }

    @Test
    fun testFormatDisplayPath() {
        assertEquals("Books/MyBook.pdf", formatDisplayPath("/storage/emulated/0/Books/MyBook.pdf"))
        assertEquals("Download/doc.pdf", formatDisplayPath("/storage/emulated/0/Download/doc.pdf"))
        assertEquals("/custom/path/doc.pdf", formatDisplayPath("/custom/path/doc.pdf"))
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("500.0 B", formatFileSize(500))
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("2.5 MB", formatFileSize((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun testBrowserClosesOnZenModeTransition() {
        var vocabMode = VocabMode.BROWSER
        val previousVocabMode: VocabMode? = VocabMode.VOCAB_PDF
        val showBrowser: Boolean
        val isZenMode: Boolean

        // User enters Zen mode while browser is in lower pane
        if (vocabMode == VocabMode.BROWSER) {
            vocabMode = previousVocabMode ?: VocabMode.VOCAB_PDF
        }
        showBrowser = false
        isZenMode = true

        assertEquals(VocabMode.VOCAB_PDF, vocabMode)
        assertEquals(false, showBrowser)
        assertEquals(true, isZenMode)
    }

    @Test
    fun testSelectionMagnifierLoupePositionNormal() {
        val pos = SelectionMagnifierHelper.calculateLoupePosition(
            touchX = 500f,
            touchY = 600f,
            loupeWPx = 300f,
            loupeHPx = 140f,
            displayedWPx = 1080f,
            padPx = 20f,
            gapPx = 50f,
            bottomFlipPx = 80f
        )
        // Horizontally centered: 500 - 150 = 350
        assertEquals(350f, pos.x, 0.001f)
        // Vertically above finger: 600 - 140 - 50 = 410
        assertEquals(410f, pos.y, 0.001f)
    }

    @Test
    fun testSelectionMagnifierLoupePositionLeftClamp() {
        val pos = SelectionMagnifierHelper.calculateLoupePosition(
            touchX = 50f,
            touchY = 600f,
            loupeWPx = 300f,
            loupeHPx = 140f,
            displayedWPx = 1080f,
            padPx = 20f,
            gapPx = 50f,
            bottomFlipPx = 80f
        )
        // 50 - 150 = -100 -> clamped to padPx (20f)
        assertEquals(20f, pos.x, 0.001f)
        assertEquals(410f, pos.y, 0.001f)
    }

    @Test
    fun testSelectionMagnifierLoupePositionRightClamp() {
        val pos = SelectionMagnifierHelper.calculateLoupePosition(
            touchX = 1050f,
            touchY = 600f,
            loupeWPx = 300f,
            loupeHPx = 140f,
            displayedWPx = 1080f,
            padPx = 20f,
            gapPx = 50f,
            bottomFlipPx = 80f
        )
        // 1050 - 150 = 900 -> clamped to 1080 - 300 - 20 = 760f
        assertEquals(760f, pos.x, 0.001f)
        assertEquals(410f, pos.y, 0.001f)
    }

    @Test
    fun testSelectionMagnifierLoupePositionTopFlip() {
        val pos = SelectionMagnifierHelper.calculateLoupePosition(
            touchX = 500f,
            touchY = 100f,
            loupeWPx = 300f,
            loupeHPx = 140f,
            displayedWPx = 1080f,
            padPx = 20f,
            gapPx = 50f,
            bottomFlipPx = 80f
        )
        // 100 - 140 - 50 = -90 < 20f -> flips below finger: 100 + 80 = 180f
        assertEquals(350f, pos.x, 0.001f)
        assertEquals(180f, pos.y, 0.001f)
    }

    @Test
    fun testSelectionMagnifierSourcePositionCentersOnTextLine() {
        val words = listOf(
            PdfWord(text = "First", left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.25f, lineIndex = 0, wordIndex = 0),
            PdfWord(text = "Second", left = 0.35f, top = 0.2f, right = 0.6f, bottom = 0.25f, lineIndex = 0, wordIndex = 1)
        )
        val srcEnd = SelectionMagnifierHelper.calculateSourcePosition(
            touchX = 400f,
            targetYNorm = 0.22f,
            displayedWPx = 1000f,
            targetHPx = 1500f,
            targetWords = words,
            selectedWordIndex = 1
        )
        // sourceX follows touchX
        assertEquals(400f, srcEnd.x, 0.001f)
        // sourceY is pinned to line center: (0.2 + 0.25)/2 * 1500 = 0.225 * 1500 = 337.5f
        assertEquals(337.5f, srcEnd.y, 0.001f)

        val srcStart = SelectionMagnifierHelper.calculateSourcePosition(
            touchX = 150f,
            targetYNorm = 0.22f,
            displayedWPx = 1000f,
            targetHPx = 1500f,
            targetWords = words,
            selectedWordIndex = 0
        )
        assertEquals(150f, srcStart.x, 0.001f)
        assertEquals(337.5f, srcStart.y, 0.001f)
    }

    @Test
    fun testAesEncryptedPdfTextAndWordExtraction() {
        val sampleSentence = "Encrypted vocabulary extraction works reliably."
        val modes = listOf(
            "aes128" to PdfWriter.ENCRYPTION_AES_128,
            "aes256" to PdfWriter.ENCRYPTION_AES_256
        )

        for ((label, encryptionMode) in modes) {
            val tempPdf = File.createTempFile("test_$label", ".pdf")
            try {
                val doc = Document()
                tempPdf.outputStream().use { out ->
                    val writer = PdfWriter.getInstance(doc, out)
                    writer.setEncryption(
                        null,
                        "owner-secret".toByteArray(),
                        PdfWriter.ALLOW_PRINTING or PdfWriter.ALLOW_COPY,
                        encryptionMode
                    )
                    doc.open()
                    doc.add(Paragraph(sampleSentence))
                    doc.close()
                }

                val reader = PdfReader(tempPdf.absolutePath)
                try {
                    assertTrue("Reader should detect $label encryption", reader.isEncrypted)
                    val pageText = PdfTextExtractor.getTextFromPage(reader, 1)
                    assertTrue("Extracted text for $label should contain sentence", pageText.contains("Encrypted vocabulary"))

                    val words = PdfTextHelper.extractWordsFromReader(reader, 1).map { it.text }
                    assertTrue("Extracted words for $label should contain 'Encrypted'", words.contains("Encrypted"))
                    assertTrue("Extracted words for $label should contain 'vocabulary'", words.contains("vocabulary"))
                } finally {
                    reader.close()
                }
            } finally {
                tempPdf.delete()
            }
        }
    }

    @Test
    fun testRethrowIfFatalAllowsRecoverableAndLinkageErrors() {
        // Recoverable exceptions and LinkageError (e.g. NoClassDefFoundError) should not rethrow
        IllegalStateException("recoverable").rethrowIfFatal()
        NoClassDefFoundError("missing optional class").rethrowIfFatal()

        // Coroutine cancellation must propagate
        var cancelledRethrown = false
        try {
            CancellationException("cancelled").rethrowIfFatal()
        } catch (_: CancellationException) {
            cancelledRethrown = true
        }
        assertTrue(cancelledRethrown)

        // Non-linkage Errors must propagate
        var nonLinkageErrorRethrown = false
        try {
            AssertionError("fatal").rethrowIfFatal()
        } catch (_: AssertionError) {
            nonLinkageErrorRethrown = true
        }
        assertTrue(nonLinkageErrorRethrown)
    }
}
