# PdfHelperReader

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK: 26](https://img.shields.io/badge/Min%2520SDK-26-blue.svg)](https://android-arsenal.com/api?level=26)
[![Target SDK: 35](https://img.shields.io/badge/Target%2520SDK-35-orange.svg)](https://developer.android.com)

**PdfHelperReader** is a powerful, offline-first, dual-pane Android PDF reader designed specifically to assist users in reading foreign or complex literature. It automatically generates companion vocabulary sidecars, applies local on-device OCR to scanned documents, and provides a seamless dual-document reading experience with synchronized state persistence and immersive reading modes.

---

## 🌟 Key Features

### 📖 Dual-Pane & Adaptive Layouts
*   **Synchronized Reading**: View your book and its dictionary/vocabulary sidecar side-by-side or stacked vertically.
*   **Landscape Side-by-Side Mode**: Automatically arranges panes side-by-side with a vertical divider when rotated into landscape orientation, featuring a sleek, thinner top ribbon (`36.dp`) to maximize vertical real estate.
*   **Flexible Split Mode (`S` Button)**: Easily cycle through three viewing modes:
    *   *Both*: Book and Vocabulary side-by-side (landscape) or top-and-bottom (portrait).
    *   *Book Only*: Full-screen primary book reader.
    *   *Vocab Only*: Full-screen vocabulary sidecar.

### 📚 Smart Vocabulary Sidecar Generation
*   Automatically parses your book and filters out common words using a bundled frequency dictionary (`google-10000-english-no-swears.txt`).
*   Matches remaining complex or foreign words against a comprehensive bundled WordNet dictionary (`dictionary.json` containing 77,500+ rich NLTK definitions).
*   **Smart Existence Check**: When generating a vocab file, the app first checks if it already exists in `Downloads/PdfHelperReader_files/`. If present, it opens immediately without regenerating.
*   **Auto-Detection on Open**: Automatically detects and immediately opens existing vocabulary sidecars upon opening a book.

### 🔍 On-Device OCR
*   Renders image-based or scanned PDFs to high-resolution bitmaps and uses Google ML Kit Text Recognition (`play-services-mlkit-text-recognition`) locally on-device.
*   Injects invisible, selectable text layers via iTextG (`PdfStamper`) into a searchable PDF copy saved to `Downloads/PdfHelperReader_files/`.

### 🚀 High-Performance Rendering & Gestures
*   **GPU-Accelerated Pinch-to-Zoom**: Smooth, silky pinch-to-zoom (up to 5x) backed by hardware layers with instant settling and high-res dynamic re-rendering.
*   **LRU In-Memory Caching & Preloading**: Intelligent background preloading of adjacent pages (`page + 1` and `page - 1`) and an LRU bitmap cache for instantaneous page turning with zero lag.
*   **Double-Tap Navigation**: Independent per-split page turning—double-tap on the right side of any pane for the next page, or the left side for the previous page.

### 🧘 Zen Mode & Customization
*   **Zen Mode (`Z` Button)**: Hides all UI chrome and toolbars for a pure, distraction-free reading experience.
*   **Per-Pane Page Badges**: Displays only the current page number (e.g. `24`) in a clean, button-like badge in the top-right corner of each active document pane in Zen mode.
*   **Floating Controls**: Floating action buttons in Zen mode for instant bookmarking (`B`) and exiting Zen mode.
*   **Dark Mode (`T` Button)**: Inverts PDF colors dynamically using high-performance `ColorMatrix` color filters for comfortable night reading.
*   **Reading History & Bookmarks**: Automatically tracks the last 50 opened books and supports robust bookmarks per document.

### 🛡️ Bulletproof Persistence
*   Computes an **MD5 file fingerprint hash** for every opened PDF so your reading position, bookmarks, and settings persist securely even if files are renamed or relocated.
*   Backs up application state to `Downloads/PdfHelperReader_files/.PdfHelperReader_state.json`.

---

## 🛠️ Tech Stack & Dependencies
*   **Language**: 100% Kotlin
*   **UI Framework**: Jetpack Compose & Material 3
*   **PDF Processing**: `com.tomroush:pdfbox-android` & `com.itextpdf:itextg`
*   **OCR**: Google Play Services ML Kit Text Recognition
*   **Data & State**: Gson, SharedPreferences, Coroutines & Flow

---

## 🔮 Future Enhancements & Contributing Roadmap

We welcome contributions from the open-source community! If you're looking to help improve PdfHelperReader, here are some key areas and feature additions planned for future releases:

### 1. ⚡ Advanced Performance & Rendering
*   **Tile-Based Rendering**: Implement tile-based progressive loading for ultra-high-resolution zooming without large memory footprints.
*   **Predictive Pre-Caching**: Expand LRU caching to predict reading velocity and pre-render pages further ahead.

### 2. 🤹 Gesture & Navigation Refinements
*   **Advanced Horizontal Swiping**: Fine-tune gesture conflict resolution for fluid horizontal page-flipping physics animations.
*   **Custom Shortcuts**: Add keyboard shortcuts and hardware volume-button page navigation.

### 3. 🧠 Learning & Productivity Tools
*   **Full-Text PDF Search**: Implement keyword and phrase search across both book and vocabulary sidecar documents.
*   **Anki Flashcard Export**: Export generated vocabulary lists and definitions directly into Anki-compatible `.apkg` or CSV files.
*   **On-Device AI Integration**: Integrate on-device LLMs (such as LiteRT or ML Kit Prompt API) for contextual sentence translations and word explanations.
*   **Annotations & Highlights**: Support text highlighting, underlining, and margin notes with persistence.

---

## 🚀 Getting Started & Building

1.  **Clone the Repository**:
    ```bash
    git clone https://github.com/ohidsajol/PdfHelperReader.git
    cd PdfHelperReader
    ```
2.  **Open in Android Studio**:
    *   Open Android Studio (Koala / Ladybug or newer).
    *   Select **File > Open** and choose the `PdfHelperReader` folder.
3.  **Build & Run**:
    *   Connect an Android device (API 26+) or start an emulator (API 35 recommended).
    *   Click **Run** (`Shift + F10`) to build and launch the app in debug mode.

---

## 📦 Downloading & Installing the App
*   You can download pre-built APKs directly from the [GitHub Releases](https://github.com/ohidsajol/PdfHelperReader/releases) page.
*   Alternatively, build the debug APK directly via Gradle:
    ```bash
    ./gradlew assembleDebug
    ```
    The generated APK will be available at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License
This project is licensed under the [MIT License](LICENSE).
