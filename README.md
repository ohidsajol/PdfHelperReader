# PdfHelperReader (v4.0.1)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK: 26](https://img.shields.io/badge/Min%20SDK-26-blue.svg)](https://android-arsenal.com/api?level=26)
[![Target SDK: 35](https://img.shields.io/badge/Target%20SDK-35-orange.svg)](https://developer.android.com)

**PdfHelperReader** is a powerful, offline-first, dual-pane Android document and eBook reader built for deep reading, academic research, and foreign language acquisition. It reads both **PDF** and **EPUB** formats, automatically generates companion vocabulary sidecars, applies local on-device ML Kit OCR to scanned documents, searches Project Gutenberg's 70,000+ catalog, offers two-finger pinch-to-zoom centered on each pane, unifies multi-page highlights, includes seamless **in-app updates**, and delivers a fully synchronized dual-document reading experience with immersive Zen modes.

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
* **[PdfHelperReader-v4.0.1.apk](https://github.com/ohidsajol/PdfHelperReader/releases/download/v4.0.1/PdfHelperReader-v4.0.1.apk)**

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
