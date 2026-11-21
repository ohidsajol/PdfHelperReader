# PdfHelperReader

[![Latest Release](https://img.shields.io/github/v/release/ohidsajol/PdfHelperReader?color=blue&label=Latest%20Release)](https://github.com/ohidsajol/PdfHelperReader/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK: 26](https://img.shields.io/badge/Min%20SDK-26-blue.svg)](https://android-arsenal.com/api?level=26)
[![Target SDK: 35](https://img.shields.io/badge/Target%20SDK-35-orange.svg)](https://developer.android.com)

**PdfHelperReader** is a modern, offline-first, dual-pane Android document and eBook reader built with 100% Kotlin and Jetpack Compose. Tailored for deep reading, academic study, and language acquisition, it pairs your books (PDF & EPUB) with automated vocabulary companion generation, AI-powered contextual literary search, on-device OCR, and distraction-free Zen reading modes.

---

## 🚀 Key Features

### 📖 Synchronized Dual-Pane & Focused Single-Pane Reading
* **Side-by-Side Companion Viewing**: Compare original documents side-by-side with companion vocabulary sidecars, study notes, or translations.
* **Focused Single-Document View**: Opens by default in immersive single-pane reading mode (`ONLY_BOOK`) to maximize screen real estate.
* **Synchronized Scrolling (`SR`)**: Lock scroll positions between both panes with customizable page offsets.
* **Center-Anchored Two-Finger Pinch-to-Zoom**: Symmetrically zoom into documents centered on your fingers across both single-page (`PageView`) and continuous-scroll (`ContinuousPageView`) modes.

### 📚 Automated Vocabulary & All Words Companion Generation
* **Page-by-Page Vocabulary PDFs (`LV`)**: Automatically extracts challenging words, computes frequency and CEFR difficulty ratings, and compiles a paired companion sidecar.
* **All Words Document Engine**: Generates an exhaustive, alphabetically ordered master vocabulary companion compiling every unique word in the entire document.
* **Custom Content Inclusion Toggles**: Filter whether to include **Definitions**, **Synonyms**, and/or **Antonyms** before generating companion documents.
* **964,000+ Word Offline Dictionary**: Comprehensive on-device dictionary with instant definitions, pronunciation keys, inflections, and grammatical categories without requiring internet.
* **Online Dictionary Fallback**: Automatically queries free online dictionary definitions if a rare or archaic word is missing locally.

### 🔎 AI-Optimized Literary & Web Search
* **Length-Aware Search Optimization**: Dynamic query formatting automatically adapts to the selected word count:
  - **Short (< 15 words)**: `meaning of this: "<text>"`
  - **Medium (15 to 30 words)**: `meaning of this phrase by phrase: "<text>"`
  - **Long (> 30 words)**: `Explain the literary meaning of this excerpt in plain English, breaking it down phrase by phrase: "<phrase>"`
* **Docked Split Google Search**: Searching automatically docks a pre-warmed Google Web Search into the lower split pane, returning seamlessly to single-document mode on back navigation.
* **Text Selection Magnifier Loupe**: Floating 1.45× magnifying capsule provides precision text and sub-word handle selection.

### 👁️ Local On-Device ML Kit OCR (`O` Button)
* **Offline Scanned Document Recognition**: Recognizes text in image-only and scanned PDFs using Google Play Services ML Kit.
* **Force OCR Mode**: Re-scans documents with corrupted or low-quality text layers into clean, searchable, selectable text.

### 🧘 Distraction-Free Zen Modes
* **Immersive Reading**: Hide all UI toolbars and sidebars with a quick double-tap or triple-tap gesture.
* **Cross-Pane Synchronized Chrome**: Double-tapping either pane toggles chrome across both panes simultaneously, preserving layout across screen rotations.

### 📑 Unified Multi-Page Highlights (`HL` Button)
* **Continuous Highlight Spans**: Highlights extending across consecutive page boundaries are unified into a single navigable entry with multi-page badges (`Pages 31–34`).
* **One-Tap Jump & Deletion**: Jump straight to highlight origins or delete entire multi-page highlights in one tap.

### 📁 Built-In Storage File Browser (`Browse` Button)
* **Device Storage Scanner**: Discovers all `.pdf` and `.epub` documents across device storage, Downloads, Documents, and Books folders.
* **Sidebar Sorting**: Sort files instantly by modification date or alphabetically with persistent scroll state.
* **Lower-Pane Docking (`Min/Max`)**: Dock the file browser into the lower split pane to switch documents without losing your reading position.
* **System "Open With" Integration**: Open documents directly into PdfHelperReader from any Android file manager, browser, or messaging app.

### 📚 Project Gutenberg Integration (`LOB` Button)
* **70,000+ Free Classics**: Search and download free public-domain classics by title or author.
* **Smart Storage Manager**: Automatically trims cached books past 500 MB while preserving your active library.

### 🔄 3-Tier GitHub In-App Updates
* **Zero-Rate-Limit Checking**: Uses a 3-tier fallback strategy (API → Web Redirect → Atom Feed) to bypass GitHub API rate limits.
* **Background APK Downloader**: Streams updates directly to `Downloads/` with live progress bars and one-tap install prompts.

---

## 🛠️ Tech Stack & Architecture
* **Language**: 100% Kotlin
* **UI Framework**: Jetpack Compose & Material 3
* **PDF Engines**: Android `PdfRenderer`, `com.tomroush:pdfbox-android`, and `com.itextpdf:itextg`
* **Text Recognition**: Google Play Services ML Kit Text Recognition
* **Concurrency & State**: Kotlin Coroutines, StateFlow, SharedPreferences, and Gson
* **Security & Crypto**: SpongyCastle AES-128 / AES-256 decryption support

---

## 📦 Download & Installation

Pre-built signed release APKs are available on the [GitHub Releases](https://github.com/ohidsajol/PdfHelperReader/releases) page:
* **[Latest Release (v4.5.0)](https://github.com/ohidsajol/PdfHelperReader/releases/latest)**
* **[PdfHelperReader-v4.5.0.apk](https://github.com/ohidsajol/PdfHelperReader/releases/download/v4.5.0/PdfHelperReader-v4.5.0.apk)**

### Building From Source
```bash
git clone https://github.com/ohidsajol/PdfHelperReader.git
cd PdfHelperReader
./gradlew assembleRelease
```
The compiled APK will be located at `app/build/outputs/apk/release/app-release.apk`.

---

## 📋 Changelog & Release Notes

For a complete record of all version changes, see [CHANGELOG.md](CHANGELOG.md) or visit [GitHub Releases](https://github.com/ohidsajol/PdfHelperReader/releases).

---

## 📄 License

This project is open-source software licensed under the [MIT License](LICENSE).
