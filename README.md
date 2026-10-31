# PdfHelperReader (v2.0.0)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK: 26](https://img.shields.io/badge/Min%20SDK-26-blue.svg)](https://android-arsenal.com/api?level=26)
[![Target SDK: 35](https://img.shields.io/badge/Target%20SDK-35-orange.svg)](https://developer.android.com)

**PdfHelperReader** is a powerful, offline-first, dual-pane Android PDF reader designed specifically to assist users in reading foreign or complex literature. It automatically generates companion vocabulary sidecars, applies local on-device OCR to scanned documents, and provides a seamless dual-document reading experience with synchronized state persistence and immersive reading modes.

---

## 🌟 Key Features in v2.0.0

### 📖 Dual-Pane & Adaptive Layouts
* **Bidirectional Synced Reading (`SR` Button)**: 1:1 real-time scroll synchronization between the Book and Vocabulary panes in both directions.
* **Continuous Auto-Scroll (`AS` Button)**: Smooth frame-by-frame auto-scroll calibrated at 1 viewport height / 5 minutes that allows manual touch dragging without interrupting the auto-scroll.
* **Exact Page Sync (`Sync` Button)**: Uses an exact `vocab_map` array (`vocab_map[bookPage] = vocabPage`) to jump the vocabulary pane to the exact definition page for your current book page.
* **Flexible Split Mode (`Both / Book / Vocab`)**: Easily cycle through three viewing modes:
    * *Both*: Book and Vocabulary side-by-side (landscape) or top-and-bottom (portrait).
    * *Book Only*: Full-screen primary book reader.
    * *Vocab Only*: Full-screen vocabulary sidecar.

### 📚 Instant Vocabulary Generation & Enriched Dictionary
* **Instant 2-Page Temporary Vocab Preview**: When generating a vocabulary PDF, the app creates an instant 2-page preview for your current reading page (~0.1s) while building the full document in the background.
* **Auto-Jump & Page Mapping**: Stores a `vocab_map` array mapping book pages to vocabulary pages. Auto-jumps when opening books and when background generation completes.
* **Compact Multi-Source Dictionary**: 133,273 base root words and 1.83 million variant keys merged from Webster's English Dictionary and WordNet, using a compact `$#$` reference encoding with 3 short, simple meanings per word.
* **Smart Lemmatization & Variant Resolution**: Inflections (`split`, `splits`, `splitted`, `splitting`) resolve instantly to the exact same 3-meaning definition.

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
    * **History**: Records First Opened and Last Closed timestamps for every book, and includes a `Del vocab` button to delete associated vocabulary files.
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
