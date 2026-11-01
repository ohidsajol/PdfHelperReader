# PdfHelperReader (v2.2.0)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK: 26](https://img.shields.io/badge/Min%20SDK-26-blue.svg)](https://android-arsenal.com/api?level=26)
[![Target SDK: 35](https://img.shields.io/badge/Target%20SDK-35-orange.svg)](https://developer.android.com)

**PdfHelperReader** is a powerful, offline-first, dual-pane Android document reader designed specifically to assist users in reading foreign or complex literature. It reads both **PDF** and **EPUB** eBooks, automatically generates companion vocabulary sidecars, applies local on-device OCR to scanned documents, streams online classic books on-demand from GitHub, and provides a seamless dual-document reading experience with synchronized state persistence and immersive reading modes.

---

## 🌟 Key Features in v2.2.0

### 📗 Full EPUB eBook Support (`EpubParser`)
* **Seamless EPUB Reading**: Unzips and converts `.epub` eBooks on demand into clean, typeset vector documents with large, clear **18pt typography**.
* **100% Feature Parity**: All 20+ app features (Synced Reading `SR`, Auto Scroll `AS`, Vocabulary PDF Generation `LV`, Bookmarks `B`/`BL`, History `H`, Dark Mode `T`, ML Kit OCR `O`, and Google Web Search) work 100% identically for EPUB files!
* **0ms Instant Opening**: Converted EPUB PDFs are stored persistently in `Downloads/PdfHelperReader_files/`. Re-opening any EPUB skips conversion and loads instantly in 0 milliseconds!
* **Hidden Vocabulary Sidecars**: Vocabulary files are created with a leading dot (`.Book_vocab.pdf`) to keep them hidden from gallery/file managers.

### 💾 Smart 500 MB Storage Manager
* **Automatic Cache Management**: Monitors the size of `Downloads/PdfHelperReader_files/`. If total folder size exceeds **500 MB**, it automatically deletes oldest files step-by-step (Oldest Vocabulary files $\rightarrow$ Oldest Converted files $\rightarrow$ Oldest Downloaded files).
* **Active File Protection**: Always protects and preserves the **latest active file** in each group from deletion!

### 📚 Online Book Library (`LOB` Button)
* **35 Classic Unabridged Books**: Includes 35 classic books by Fyodor Dostoevsky (22), Friedrich Nietzsche (10), William Wordsworth (1), and Lord Byron (2).
* **On-Demand GitHub Raw CDN Streaming**: Streams books on demand directly from your GitHub raw CDN repository (`docs/online_books/<fileName>`), keeping APK size lightweight (**~28MB**) while guaranteeing 100% uptime with zero third-party blocks.

### 📖 Dual-Pane & Adaptive Layouts
* **Bidirectional Synced Reading (`SR` Button)**: 1:1 real-time scroll synchronization between the Book and Vocabulary panes in both directions.
* **Continuous Auto-Scroll (`AS` Button)**: Smooth frame-by-frame auto-scroll calibrated at 1 viewport height / 5 minutes that allows manual touch dragging without interrupting auto-scroll.
* **Exact Page Sync (`Sync` Button)**: Uses an exact `vocab_map` array (`vocab_map[bookPage] = vocabPage`) to jump the vocabulary pane to the exact definition page for your current book page.
* **Continuous Mode Clean PaneBar**: In Continuous Mode (`R:C`), redundant zoom and page flip buttons are hidden, displaying only the Page Jump Counter (e.g. `18 / 512`) and the horizontally scrollable Document Title.
* **Flexible Split Mode (`Both / Book / Vocab`)**: Easily cycle through three viewing modes:
    * *Both*: Book and Vocabulary side-by-side (landscape) or top-and-bottom (portrait).
    * *Book Only*: Full-screen primary book reader.
    * *Vocab Only*: Full-screen vocabulary sidecar.

### 💡 Vocabulary Generation & Enriched Dictionary
* **Full Vocabulary PDF Generation**: Creates a complete, styled vocabulary PDF sidecar with full-width separator lines between word entries and automatic page mapping.
* **Compact Multi-Source Dictionary**: 964,676 unique base root words and variant keys merged from Webster's English Dictionary, WordNet, and Project Gutenberg vocabulary corpus.
* **Smart Lemmatization & Variant Resolution**: Inflections (`split`, `splits`, `splitted`, `splitting`) resolve instantly to the exact same definition.

### 🌐 Embedded Google Web Search Browser
* **System Back Gesture Protection (`BackHandler`)**: System back gestures navigate backward within Google's search history instead of exiting the app.
* **Chrome-Style Web Navigation Bar**: Includes prominent `‹ Back`, `Forward ›`, `↻ Reload`, and page title indicators.
* **One-Tap Clipboard Search**: Selecting text on any PDF page presents a floating banner to search the selected phrase directly in Google.

### 🔍 On-Device OCR
* Renders image-based or scanned PDFs to high-resolution bitmaps using Google ML Kit Text Recognition locally on-device.

### 🧘 Zen Mode & Customization
* **Zen Mode (`Z` Button)**: Hides UI toolbars for a distraction-free reading experience, replacing them with a docked right-edge control strip.
* **Dark Mode (`T` Button)**: Inverts PDF colors dynamically using high-performance `ColorMatrix` color filters for comfortable night reading.
* **Enhanced History & Bookmarks**:
    * **History**: Records First Opened and Last Closed timestamps for every book, with capacity for 1,000 entries and automatic document closure on deletion.
    * **Bookmarks**: Stores creation timestamps and 25-word text snippet previews from the book page.

---

## 🛠️ Tech Stack & Dependencies
* **Language**: 100% Kotlin
* **UI Framework**: Jetpack Compose & Material 3
* **PDF Processing**: `com.tomroush:pdfbox-android` & `com.itextpdf:itextg`
* **OCR**: Google Play Services ML Kit Text Recognition
* **Data & State**: Gson, SharedPreferences, Coroutines & Flow

---

## 🚀 Getting Started & Building

1. **Clone the Repository**:
    ```bash
    git clone https://github.com/ohidsajol/PdfHelperReader.git
    cd PdfHelperReader
    ```
2. **Open in Android Studio**:
    * Open Android Studio (Koala / Ladybug or newer).
    * Select **File > Open** and choose the `PdfHelperReader` folder.
3. **Build & Run**:
    * Connect an Android device (API 26+) or start an emulator.
    * Click **Run** (`Shift + F10`) to launch the app.

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
