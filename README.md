# PdfHelperReader (v4.3.1)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK: 26](https://img.shields.io/badge/Min%20SDK-26-blue.svg)](https://android-arsenal.com/api?level=26)
[![Target SDK: 35](https://img.shields.io/badge/Target%20SDK-35-orange.svg)](https://developer.android.com)

**PdfHelperReader** is a powerful, offline-first, dual-pane Android document and eBook reader built for deep reading, academic research, and foreign language acquisition. It reads both **PDF** and **EPUB** formats, automatically generates companion vocabulary sidecars, applies local on-device ML Kit OCR to scanned documents, searches Project Gutenberg's 70,000+ catalog, offers two-finger pinch-to-zoom centered on each pane, unifies multi-page highlights, includes seamless **in-app updates**, and delivers a fully synchronized dual-document reading experience with immersive Zen modes.

---

## 🌟 What's New in v4.3.1

### 🔍 Floating Text Selection Magnifier Loupe
* **1.45× Magnification Capsule**: Pressing a word or dragging the Start/End selection handles reveals a crisp, high-contrast magnifying pill above your finger rendering the live page, highlight overlays, and vertical guide pin.
* **Smart Screen-Edge Positioning**: Clamps horizontally within screen bounds, pins vertically to the text line's center to prevent vertical jitter, and flips cleanly below your finger when selecting near the top edge.
* **Whole-Word Long-Press Preservation**: Long-pressing a word reliably selects the entire word cleanly without accidental slicing from minor touch jitter.

### 🔐 AES-Encrypted PDF Support & Crash Protection
* **Native AES-128 & AES-256 Decryption**: Packaged SpongyCastle crypto libraries (`scprov-jdk15on` & `scpkix-jdk15on`) alongside iTextG so permission-protected AES PDFs extract text, look up words, and generate vocabulary sidecars smoothly.
* **Hardened Linkage Error Fallback**: Safeguards text extraction, page reading, vocabulary generation, and OCR status checks against `LinkageError`s and decryption failures so protected or malformed PDFs never crash the app.

---

## 🌟 What's New in v4.3.0

### 📁 Built-in Storage File Browser & Lower-Pane Docking (`Browse` Button)
* **Comprehensive Storage Scanning**: Scans internal device storage, Downloads, Documents, and Books directories for all `.pdf` and `.epub` documents with live status and manual re-scan.
* **Right-Edge Sorting Sidebar**: Instantly sort by **Time** (most recently modified first) or **Name** (alphabetical A–Z). Active sorting mode and exact scroll positions are remembered permanently across app restarts.
* **Min/Max Split Docking**: Tap **Min/Max** at the bottom of the sidebar to dock the file manager into the lower split pane for quick browsing while keeping your book visible, or expand it back to full screen.
* **Theme-Locked Surface Background**: Min mode maintains a consistent, theme-protected surface background color that does not invert or flash when toggling the reader's dark theme (`T`).
* **Automatic Zen Mode Dismissal**: Entering Zen Mode automatically closes the docked lower pane browser, maintaining a completely distraction-free reading space.

### 📱 System "Open With" File Association
* **System Chooser Integration**: Registered system-wide intent filters allow opening any `.pdf` or `.epub` file directly from Android file managers, Downloads, messaging apps, and email clients directly into PdfHelperReader.
* **Seamless Launch & Running Handlers**: Automatically handles incoming document intents whether launching freshly or switching while already running in memory.

---

## 🌟 What's New in v4.2.0

### 📖 Vocabulary PDF Word References
* **Explicit Cross-References**: Companion vocabulary PDFs now explicitly show headword references for words and inflections that reference base forms (e.g. `wobble (Ref: Wabble) (32.5)`).
* **Reference Display in Quick Popup**: The instant definition popup also displays `(Ref: <word>)` in italics directly in the title row.

### 🕒 Dual-Tab History & Persistent State
* **Search History Tab**: The History dialog (`H` button) now features two tabs: **Reading History** and **Search History**. Past Google searches are stored with one-tap re-search, individual deletion, and a **Clear All** option.
* **Persistent Tab & Scroll Positions**: The active History tab and the scroll positions of both lists are permanently preserved across dialog dismissals and app restarts.
* **Ribbon Toolbar Position Preservation**: The horizontal scroll offset of the main ribbon toolbar is saved permanently and restored across Zen mode toggles and app restarts.

### 🌐 Free Online Dictionary API Fallback
* **Automatic Online Lookup**: When a selected word is missing from the 964k-word local dictionary, the app automatically queries the free online dictionary API in the background.
* **Comprehensive Details**: Renders parts of speech, definitions, synonyms, antonyms, and an **online** badge right inside the quick popup.

### 🎯 Precision Drag Selection & Smart Query Formatting
* **Sub-Word Dragging**: Selecting text long-presses to whole words cleanly, while handle dragging provides precision sub-word control without forced word snapping.
* **Smart Search Query Formatting**: Action bar Google searches automatically format queries: single words search `meaning of this: "word"`, and multi-word phrases search `meaning of this part by part: "phrase"`.
* **Lower-Pane Definition Popup**: Selecting a word in the upper pane consistently renders the definition popup in the lower pane, even when Google Search is open.

---

## 🌟 What's New in v4.1.0

### 🔤 1.5× Larger Typography for Vocabulary PDFs
* **Enlarged Fonts**: Generated vocabulary companion PDFs now render with 1.5× larger, high-clarity typography (24pt headers, 19.5pt headwords & scores, and 16.5pt definitions, examples, synonyms, and antonyms).
* **Balanced Spacing**: Proportional vertical spacing, line leading, and inter-entry margins provide an effortless, fatigue-free reading experience on phones and tablets.

### 🌐 Instant Flash-Free Google Web Search
* **Pre-warmed Chromium Engine**: Native WebView libraries are pre-initialized smoothly in the background, eliminating cold-start frame drops.
* **Preserved Split-Pane Layout**: The main book pane remains mounted without re-rendering or canvas flashing during split-mode transitions.
* **Auto-Dismissing Definition Popup**: Opening Google search from selected text automatically dismisses the definition popup so it doesn't obstruct the lower web pane.

### 🎚️ Vocabulary Word-Frequency Filtering Slider
* **Adjustable Density**: Filter dictionary vocabulary extraction density (1–100) on-demand before generating sidecar PDFs.

---

## 🌟 What's New in v4.0.1

### 🧘 Zen Mode Triple-Tap Gesture Shortcuts
* **Instant Distraction-Free Toggle**: Triple-tap anywhere on the reading pane in Normal mode to enter Zen Mode with the sidebar buttons hidden for a 100% clean view.
* **Direct Return**: Triple-tap anywhere in Zen mode to return directly to Normal mode without showing or flashing the sidebar buttons.
* **Responsive Double-Tap**: Double-tapping in Zen mode toggles the right-edge sidebar buttons with an optimized 200ms window and zero sluggishness.
* **Enlarged Tutorial Manual (2x Font)**: Completely refreshed `PdfHelperReader_Tutorial.pdf` manual with 2x larger typography (16pt body) and full v4.0.1 gesture documentation.

---

## 🌟 What's New in v4.0.0

### 📑 Unified Multi-Page Highlights (`HL` Button)
* **Single Entry for Multi-Page Spans**: Selecting and highlighting text that spans across multiple consecutive pages (e.g. Pages 31–34) now creates one unified highlight entry rather than fragmented records per page.
* **Smart Page Badges & Navigation**: Displays multi-page range indicators (`Pages 31–34`), the full concatenated text excerpt, and navigates straight to the highlight's starting page with a single tap.
* **Unified Deletion**: Tapping the trash icon on a grouped highlight removes all linked page segments across the entire document in one action.

### 🤏 Center-Anchored Two-Finger Pinch-to-Zoom
* **Even Multi-Directional Scaling**: Two-finger pinch-to-zoom expands and contracts symmetrically around the exact center point of each pane instead of drifting towards the top-left corner.
* **Calibrated Sensitivity**: Zoom speed is finely calibrated with a `0.085f` smoothing factor for silky, controlled magnification.
* **Full Reader Mode Parity**: Works seamlessly in both single-page mode (`PageView`) and vertical continuous scroll mode (`ContinuousPageView`).

### ℹ️ Interactive App & Developer Info
* **Clickable Repository Link**: Direct, interactive link to [`github.com/ohidsajol/PdfHelperReader ↗`](https://github.com/ohidsajol/PdfHelperReader) that opens directly in your default browser.
* **Comprehensive Feature Breakdown**: View all reader capabilities, storage management rules, and developer credits in an elegant scrollable dialog.

---

## 🚀 Core Features

### 📖 Synchronized Dual-Pane Reading
* **Compare Documents Side-by-Side**: Read original texts alongside companion translations, notes, or vocabulary sidecars.
* **Synced Reading Mode (`SR`)**: Lock scrolling between both panes with customizable page offsets.
* **Independent Controls**: Switch modes, jump pages, or adjust font/zoom independently or in sync.

### 🧘 Immersive Zen Mode
* **Distraction-Free Reading**: Hide all top bars and side strips with a simple double-tap gesture.
* **Cross-Pane Synchronization**: Double-tapping on either pane toggles chrome visibility across both panes simultaneously.
* **Orientation State Preservation**: Hidden controls remain hidden when rotating between portrait and landscape.

### 🔤 Vocabulary PDF Generator & 964k-Word Offline Dictionary
* **Automated Sidecar Generation (`LV`)**: Extracts difficult words, sorts by frequency and CEFR proficiency levels, and creates a companion PDF sidecar with contextual definitions.
* **Instant Offline Lookup**: 964,000+ English word entries for definitions, pronunciations, inflections, and grammatical categories without requiring internet.

### 👁️ Local On-Device ML Kit OCR (`O` Button)
* **Offline Text Recognition**: Powered by Google Play Services ML Kit to recognize text in scanned PDFs and document images.
* **Force OCR Mode**: Re-scans documents with existing low-quality or corrupt text layers to produce clean, selectable text.

### 📚 Project Gutenberg Search & Online Classics (`LOB` Button)
* **70,000+ Gutenberg Books**: Real-time debounced title and author search with direct EPUB and PDF downloads.
* **Curated Classics**: Instant access to hand-picked works by Dostoevsky, Nietzsche, Wordsworth, and Byron.
* **Smart 500 MB Storage Manager**: Automatically trims older downloaded books and cached files while protecting your active reading material.

### 🔄 3-Tier GitHub In-App Updates
* **Zero Rate-Limit Update Checker**: Checks GitHub releases using a 3-tier fallback (API $\rightarrow$ Web Redirect $\rightarrow$ Atom Feed) to bypass `HTTP 403` restrictions.
* **Direct Background Download**: Streams update APKs straight to `Downloads/` with live progress bars and clean installation prompts.

---

## 🛠️ Tech Stack & Architecture
* **Language**: 100% Kotlin
* **UI Framework**: Jetpack Compose & Material 3
* **PDF Engine**: Android `PdfRenderer`, `com.tomroush:pdfbox-android`, and `com.itextpdf:itextg`
* **Text Recognition**: Google Play Services ML Kit Text Recognition
* **Data & Persistence**: Kotlin Coroutines, StateFlow, SharedPreferences, and Gson

---

## 📦 Download & Installation
Pre-built signed release APKs are available on the [GitHub Releases](https://github.com/ohidsajol/PdfHelperReader/releases) page:
* **[PdfHelperReader-v4.3.1.apk](https://github.com/ohidsajol/PdfHelperReader/releases/download/v4.3.1/PdfHelperReader-v4.3.1.apk)**

To build from source:
```bash
git clone https://github.com/ohidsajol/PdfHelperReader.git
cd PdfHelperReader
./gradlew assembleRelease
```
The compiled APK will be located at `app/build/outputs/apk/release/app-release.apk`.

---

## 📄 License
This project is open-source software licensed under the [MIT License](LICENSE).
