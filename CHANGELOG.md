# Changelog

All notable changes to **PdfHelperReader** are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Release packages and signed APK downloads are available on [GitHub Releases](https://github.com/ohidsajol/PdfHelperReader/releases).

---

## [v4.5.0] - 2026-11-21

### Added
- **All Words Document Engine**: Generates an exhaustive companion PDF compiling every unique word across the entire document, sorted alphabetically with clean section banners (`— A —`, `— B —`, etc.).
- **Automatic Split-View Docking**: Automatically opens the source book in the upper pane and docks the newly created All Words document into the lower pane for synchronized reference.
- **Custom Content Inclusion Toggles**: Checkable options in the generation dialog to include or exclude Definitions, Synonyms, and Antonyms on a per-document basis.
- **Space-Saving Horizontal Checkbox Row**: Compact single-row layout for content options with full vertical scroll protection in the threshold dialog.
- **Tiered AI & Google Search Prompts**: Dynamic prompt formatting adapting to selection word count:
  - Short (< 15 words): `meaning of this: "<text>"`
  - Medium (15 to 30 words): `meaning of this phrase by phrase: "<text>"`
  - Long (> 30 words): `Explain the literary meaning of this excerpt in plain English, breaking it down phrase by phrase: "<phrase>"`
- **4-Row Reading History Layout**: Un-truncated full book title (Row 1), First Opened timestamp (Row 2), Last Closed timestamp (Row 3), and generously spaced action buttons (`all words`, `del words`, `del vocab`, `del`) (Row 4).
- **Disk Cleanup (`del words`)**: Dedicated button to delete generated All Words companion files directly from disk and refresh the Android media scanner.
- **Tutorial Manual Update**: Fully updated `PdfHelperReader_Tutorial.pdf` manual featuring v4.5.0 documentation.

---

## [v4.4.0] - 2026-11-19

### Added
- **AI-Optimized Literary & Philosophical Search Prompts**: Tailored multi-word search queries unpacking metaphors and archaic phrasing into plain English.
- **Focused Single-Document Default Mode**: App opens by default in focused single-document book view (`ONLY_BOOK`).
- **Seamless Split-Pane Google Back Navigation**: Opening search auto-docks Google Web Search into the lower split pane, with back gesture smoothly returning directly to single-document mode.
- **Flicker-Free WebView Rendering**: Optimized Chromium background rendering in Compose to eliminate canvas flashing.

---

## [v4.3.1] - 2026-11-12

### Added
- **Floating Text Selection Magnifier Loupe**: 1.45× magnifying capsule positioned above selection handles with screen-edge clamping and top-edge flipping.
- **Native AES-128 & AES-256 PDF Support**: SpongyCastle crypto integration allowing encrypted/permission-protected PDFs to decrypt and generate vocabulary seamlessly.
- **Hardened Linkage Error Protection**: Safeguards text extraction against `LinkageError` and decryption anomalies.

---

## [v4.3.0] - 2026-11-08

### Added
- **Built-in Storage File Browser (`Browse` Button)**: Storage scanner with right-edge sorting sidebar (Time/Name) and lower split-pane docking (`Min/Max`).
- **System "Open With" File Association**: System-wide intent filters to open `.pdf` and `.epub` documents directly from external file managers and apps.
- **Theme-Locked Surface Backgrounds**: Preserves background contrast across dark/light mode transitions.

---

## [v4.2.0] - 2026-11-08

### Added
- **Vocabulary PDF Word Cross-References**: Explicit headword references for inflected forms (e.g. `wobble (Ref: Wabble)`).
- **Dual-Tab History Dialog (`H` Button)**: Separate tabs for Reading History and Search History with persistent tab selection and scroll offsets.
- **Free Online Dictionary API Fallback**: Queries online dictionary automatically when words are absent from the 964k local dictionary.
- **Precision Sub-Word Drag Selection**: Long-press whole-word selection with precision sub-word handle dragging.

---

## [v4.1.0] - 2026-11-07

### Added
- **1.5× Larger Vocabulary PDF Typography**: Enhanced readability with 24pt headers, 19.5pt headwords, and 16.5pt definitions.
- **Instant Pre-warmed Google Web Search**: Background WebView initialization eliminating frame drops.
- **Adjustable Frequency Filtering Slider**: Dynamic word-frequency density slider (1–100) before generating sidecar PDFs.

---

## [v4.0.1] - 2026-11-06

### Added
- **Zen Mode Triple-Tap Shortcuts**: Triple-tap gesture to instantly enter/exit distraction-free mode.
- **Double-Tap Control Strips**: Rapid 200ms double-tap toggle for side action bars in Zen mode.

---

## [v4.0.0] - 2026-11-06

### Added
- **Unified Multi-Page Highlights (`HL` Button)**: Consolidates text highlights spanning across consecutive pages into single navigable entries.
- **Center-Anchored Two-Finger Pinch-to-Zoom**: Smooth, symmetric zooming anchored to the center of each pane in both single-page and continuous-scroll modes.
- **Interactive App & Developer Info**: In-app dialog with live repository links and architecture overview.

---

## [Earlier Releases]
For earlier versions (v1.0.0 through v3.2.1), see the complete release archive on [GitHub Releases](https://github.com/ohidsajol/PdfHelperReader/releases).
