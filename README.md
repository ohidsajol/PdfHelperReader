# PdfHelperReader (v2.9.0)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK: 26](https://img.shields.io/badge/Min%20SDK-26-blue.svg)](https://android-arsenal.com/api?level=26)
[![Target SDK: 35](https://img.shields.io/badge/Target%20SDK-35-orange.svg)](https://developer.android.com)

**PdfHelperReader** is a powerful, offline-first, dual-pane Android document reader designed specifically to assist users in reading foreign or complex literature. It reads both **PDF** and **EPUB** eBooks, automatically generates companion vocabulary sidecars, applies local on-device OCR to scanned documents, streams online classic books on-demand from GitHub, searches Project Gutenberg's 70,000+ catalog, includes seamless **in-app updates**, and provides a dual-document reading experience with synchronized state persistence and immersive reading modes.

---

## 🌟 Key Features in v2.9.0

### 🔄 Clean User-Controlled Update Flow
* **One-Tap Release Updates**: Rightmost `Update` button checks GitHub's latest release API (`/releases/latest`), compares installed version against GitHub latest release, and streams live download progress bytes (`Progress: 45% (13.2 MB / 29.0 MB)`).
* **Direct `Downloads/` Destination**: Downloads the release APK file directly to your phone's main **`Downloads/`** folder (`Downloads/PdfHelperReader-v2.9.0.apk`).
* **Clear Single-Button Instruction Dialog**: Displays an informative instruction dialog with a single **[ OK ]** button, explaining how to install the update by tapping the file in your Downloads folder while preserving 100% of your history and bookmarks.

### 🎨 Alternating `#00796B` Font Color Styling
* **Alternating Font Text Colors**: Upper action ribbon buttons keep their original clean button styling untouched while dynamically alternating button **font text colors** between `primary` and vibrant `#00796B` (Teal) for an elegant, high-contrast typography experience.

### ℹ️ App & Developer Info (`App Info` Button)
* Displays developer details (**Ohid Sajol**), GitHub repository URL (`github.com/ohidsajol/PdfHelperReader`), version info, and full application overview.

### 📚 Expanded Online Library & Gutenberg Search (`LOB` Button)
* **Dual-Tab Source Selection**:
    * **Online Books**: Access 35 hand-picked classic books by Dostoevsky, Nietzsche, Wordsworth, and Byron, streamed on-demand from GitHub raw CDN repository.
    * **Gutenberg Books**: Real-time browser and search engine for Project Gutenberg's 70,000+ catalog.
* **Official Gutenberg Search Engine**: Search any book title or author across Project Gutenberg with instant debounced results and popular books showcase (*Pride and Prejudice*, *Frankenstein*, *Moby Dick*, *Sherlock Holmes*, *Dracula*, etc.).
* **EPUB or PDF Download Options**: Each Gutenberg book features dedicated **[ EPUB ]** and **[ PDF ]** download buttons.
* **Persistent Search State**: Tapping outside or closing the dialog preserves your active tab, search query, and search results in memory so you don't have to re-type or re-do your search!

### 📗 Full EPUB eBook Support (`EpubParser`)
* **Seamless EPUB Reading**: Unzips and converts `.epub` eBooks on demand into clean, typeset vector documents with large, clear **18pt typography**.
* **100% Feature Parity**: All 20+ app features (Synced Reading `SR`, Auto Scroll `AS`, Vocabulary PDF Generation `LV`, Bookmarks `B`/`BL`, History `H`, Dark Mode `T`, ML Kit OCR `O`, and Google Web Search) work 100% identically for EPUB files!
* **0ms Instant Opening**: Converted EPUB PDFs are stored persistently in `Downloads/PdfHelperReader_files/`. Re-opening any EPUB skips conversion and loads instantly in 0 milliseconds!
* **Hidden Vocabulary Sidecars**: Vocabulary files are created with a leading dot (`.Book_vocab.pdf`) to keep them hidden from gallery/file managers.

### 💾 Smart 500 MB Storage Manager
* **Automatic Cache Management**: Monitors the size of `Downloads/PdfHelperReader_files/`. If total folder size exceeds **500 MB**, it automatically deletes oldest files step-by-step (Oldest Vocabulary files $\rightarrow$ Oldest Converted files $\rightarrow$ Oldest Downloaded files).
* **Active File Protection**: Always protects and preserves the **latest active file** in each group from deletion!

---

## 🛠️ Tech Stack & Dependencies
* **Language**: 100% Kotlin
* **UI Framework**: Jetpack Compose & Material 3
* **PDF Processing**: `com.tomroush:pdfbox-android` & `com.itextpdf:itextg`
* **OCR**: Google Play Services ML Kit Text Recognition
* **Data & State**: Gson, SharedPreferences, Coroutines & Flow

---

## 📦 Downloading & Installing the App
* Download pre-built APKs directly from the [GitHub Releases](https://github.com/ohidsajol/PdfHelperReader/releases) page.
* Or build the release APK via Gradle:
    ```bash
    ./gradlew assembleRelease
    ```
    The generated APK is saved at `app/build/outputs/apk/release/app-release.apk`.

---

## 📄 License
This project is licensed under the [MIT License](LICENSE).
