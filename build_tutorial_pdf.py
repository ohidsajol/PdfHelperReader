from reportlab.lib.pagesizes import letter
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib import colors
from reportlab.pdfgen import canvas
import sys

class NumberedCanvas(canvas.Canvas):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._saved_page_states = []

    def showPage(self):
        self._saved_page_states.append(dict(self.__dict__))
        self._startPage()

    def save(self):
        num_pages = len(self._saved_page_states)
        for state in self._saved_page_states:
            self.__dict__.update(state)
            self.draw_page_number(num_pages)
            canvas.Canvas.showPage(self)
        canvas.Canvas.save(self)

    def draw_page_number(self, page_count):
        self.saveState()
        self.setFont("Helvetica", 9.5)
        self.setFillColor(colors.HexColor('#718096'))
        page_text = f"PdfHelperReader v4.3.1 Manual  •  Page {self._pageNumber} of {page_count}"
        self.drawRightString(612 - 36, 20, page_text)
        self.restoreState()

def build_pdf(filename):
    doc = SimpleDocTemplate(
        filename,
        pagesize=letter,
        leftMargin=36,
        rightMargin=36,
        topMargin=36,
        bottomMargin=36
    )

    primary_color = colors.HexColor('#1A365D')    # Dark blue
    secondary_color = colors.HexColor('#2B6CB0')  # Medium blue
    body_color = colors.HexColor('#2D3748')       # Charcoal
    table_header_bg = colors.HexColor('#EDF2F7')  # Light gray-blue

    styles = getSampleStyleSheet()

    title_style = ParagraphStyle(
        'DocTitle',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=28,
        leading=34,
        textColor=primary_color,
        spaceAfter=6
    )

    subtitle_style = ParagraphStyle(
        'DocSubtitle',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=15,
        leading=20,
        textColor=secondary_color,
        spaceAfter=14
    )

    h1_style = ParagraphStyle(
        'SectionH1',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=19,
        leading=23,
        textColor=primary_color,
        spaceBefore=11,
        spaceAfter=6,
        keepWithNext=False
    )

    body_style = ParagraphStyle(
        'BodyDark',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=15,
        leading=20.5,
        textColor=body_color,
        spaceAfter=8
    )

    table_btn_style = ParagraphStyle(
        'TableBtn',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=15,
        leading=19,
        textColor=secondary_color
    )

    table_header_style = ParagraphStyle(
        'TableHeader',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=16,
        leading=20,
        textColor=primary_color
    )

    table_desc_style = ParagraphStyle(
        'TableDesc',
        parent=styles['Normal'],
        fontName='Helvetica',
        fontSize=15.5,
        leading=21.5,
        textColor=body_color
    )

    story = []

    # Title & Subtitle
    story.append(Paragraph("PdfHelperReader – Comprehensive User Manual", title_style))
    story.append(Paragraph("(v4.3.1) • High-Performance Dual-Pane Reading, Refined Vocabulary Engine & Integrated Research Environment", subtitle_style))

    # Section 1
    story.append(Paragraph("1. Welcome & Introduction", h1_style))
    story.append(Paragraph(
        "PdfHelperReader is a specialized Android document reading environment designed for language learning, academic research, and "
        "deep study. It reads both PDF and EPUB documents, pairing your main book with a fine-tuned Refined Vocabulary sidecar PDF or "
        "an embedded Google Web Search browser in real-time.",
        body_style
    ))

    # Section 2 Table
    story.append(Spacer(1, 10))
    story.append(Paragraph("2. Upper Main Ribbon Controls (Top Action Bar)", h1_style))

    table_data = [
        [
            Paragraph("Button", table_header_style),
            Paragraph("Function & Detailed How-To-Use Guide", table_header_style)
        ],
        [
            Paragraph("B", table_btn_style),
            Paragraph("<b>Add Bookmark</b>: Tap to bookmark your current reading position. Captures creation timestamp and extracts the first 25 words from the book page.", table_desc_style)
        ],
        [
            Paragraph("BL", table_btn_style),
            Paragraph("<b>Bookmark List</b>: Opens saved bookmarks dialog. Shows creation date/time and 25-word book page snippet previews for the current book.", table_desc_style)
        ],
        [
            Paragraph("HL", table_btn_style),
            Paragraph("<b>Highlights List</b>: Opens interactive highlights dialog. Displays unified multi-page highlights (Pages X-Y) and single-page highlights with full joined excerpt, jump-to-page navigation, and single-tap deletion.", table_desc_style)
        ],
        [
            Paragraph("Both / Book / Vocab", table_btn_style),
            Paragraph("<b>Split Mode Toggle</b>: Cycles screen layout between Both (dual-pane split view), Book (full-screen main document), or Vocab (full-screen vocabulary pane).", table_desc_style)
        ],
        [
            Paragraph("Z", table_btn_style),
            Paragraph("<b>Zen Mode & Gestures</b>: Hides toolbars and system bars for 100% edge-to-edge reading. <b>Triple-tap</b> anywhere in normal mode to enter Zen Mode with hidden buttons; <b>triple-tap</b> again to return to normal mode. In Zen Mode, <b>double-tap</b> to hide/unhide the right-edge sidebar controls.", table_desc_style)
        ],
        [
            Paragraph("SR", table_btn_style),
            Paragraph("<b>Synced Reading</b>: Toggles 1:1 real-time bidirectional scroll synchronization. When scrolling either the Book or Vocab pane, the other pane scrolls automatically in lockstep.", table_desc_style)
        ],
        [
            Paragraph("AS", table_btn_style),
            Paragraph("<b>Auto Scroll</b>: Gentle continuous frame-by-frame auto-scroll calibrated at 1 viewport height / 5 minutes. Touch dragging is supported without stopping auto-scroll.", table_desc_style)
        ],
        [
            Paragraph("Sync", table_btn_style),
            Paragraph("<b>Page Sync</b>: Instantly jumps the Vocabulary pane to match your current Book page using the exact vocab_map array.", table_desc_style)
        ],
        [
            Paragraph("G:Vocab / G:Google / G:Files", table_btn_style),
            Paragraph("<b>Vocab Mode & Preserved Search/Browser State</b>: Cycles lower pane between Vocabulary PDF sidecar, embedded Google Search, and the docked File Browser. Automatically switches to Dual-Pane Split View with persistent layout and pre-warmed Chromium engine. Preserves browsing history and state when switching back and forth, and performs new searches on-demand.", table_desc_style)
        ],
        [
            Paragraph("T", table_btn_style),
            Paragraph("<b>Theme Toggle</b>: Switches Dark/Light mode with high-contrast ColorMatrix PDF page color inversion for comfortable night reading.", table_desc_style)
        ],
        [
            Paragraph("R:C / R:P", table_btn_style),
            Paragraph("<b>Reading Mode</b>: Toggles Continuous vertical scrolling (R:C) or fit-to-width Page-by-Page mode (R:P).", table_desc_style)
        ],
        [
            Paragraph("V:PDF / V:TXT", table_btn_style),
            Paragraph("<b>View Mode</b>: Toggles vector PDF page rendering with transparent OCR text selection overlay (V:PDF) or plain text extraction (V:TXT).", table_desc_style)
        ],
        [
            Paragraph("H", table_btn_style),
            Paragraph("<b>History (Dual-Tab)</b>: Opens history dialog with two tabs: <b>Reading History</b> (tracks First Opened & Last Closed timestamps for up to 1,000 documents with Del vocab) and <b>Search History</b> (logs all past Google searches with one-tap re-search, item deletion, and Clear All). Tab selection and scroll positions are permanently preserved.", table_desc_style)
        ],
        [
            Paragraph("O", table_btn_style),
            Paragraph("<b>OCR Generator</b>: Applies Google ML Kit OCR to image-based or scanned PDFs, adding a searchable text layer saved to Downloads/PdfHelperReader_files/, with Force OCR mode to override existing text.", table_desc_style)
        ],
        [
            Paragraph("LOB", table_btn_style),
            Paragraph("<b>Load Online Book</b>: Opens dual-tab online library (35 GitHub CDN classics + official Project Gutenberg search engine for 70,000+ books with EPUB and PDF download options).", table_desc_style)
        ],
        [
            Paragraph("Browse", table_btn_style),
            Paragraph("<b>Built-in File Browser & Docking</b>: Scans all PDF and EPUB files across device storage. Features a right-edge sidebar with <b>Time</b> (most recently modified first) and <b>Name</b> (alphabetical A–Z) sorting, instant search filter, live scan status, and state persistence. Tap <b>Min/Max</b> to dock the browser seamlessly into the lower pane or restore to full screen; the docked view automatically closes when entering Zen Mode.", table_desc_style)
        ],
        [
            Paragraph("OB / OV / LV", table_btn_style),
            Paragraph("<b>Open Book / Open Vocab / Load Vocab</b>: Open PDF or EPUB book/vocab files via system file picker, or generate vocabulary PDF automatically.", table_desc_style)
        ],
        [
            Paragraph("Update", table_btn_style),
            Paragraph("<b>In-App Update</b>: Checks GitHub latest release API with 3-tier fallback, streams live download progress, and launches Android Package Installer seamlessly.", table_desc_style)
        ],
        [
            Paragraph("App Info", table_btn_style),
            Paragraph("<b>Developer Info</b>: Displays developer profile (Ohid Sajol), direct clickable GitHub repository link (github.com/ohidsajol/PdfHelperReader), and full application overview.", table_desc_style)
        ]
    ]

    col_widths = [140, 400]  # Sum = 540
    t = Table(table_data, colWidths=col_widths, repeatRows=1)
    t.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), table_header_bg),
        ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
        ('VALIGN', (0, 0), (-1, -1), 'TOP'),
        ('TOPPADDING', (0, 0), (-1, -1), 6),
        ('BOTTOMPADDING', (0, 0), (-1, -1), 6),
        ('LEFTPADDING', (0, 0), (-1, -1), 6),
        ('RIGHTPADDING', (0, 0), (-1, -1), 6),
        ('LINEBELOW', (0, 0), (-1, 0), 1.5, secondary_color),
        ('LINEBELOW', (0, 1), (-1, -1), 0.5, colors.HexColor('#CBD5E0')),
    ]))

    story.append(t)

    # Detailed Feature Sections
    story.append(Spacer(1, 14))
    story.append(Paragraph("3. Refined Vocabulary Engine & Difficulty Scores", h1_style))
    story.append(Paragraph(
        "PdfHelperReader features a fine-tuned Refined Vocabulary database containing over <b>964,000 entries</b>.<br/>"
        "• <b>1.5× Enlarged Typography</b>: Generated vocabulary PDFs render with 1.5× enlarged typography "
        "(24pt headers, 19.5pt headwords & scores, and 16.5pt definitions, examples, synonyms, and antonyms) with generous "
        "proportional line and block spacing for effortless study on mobile screens.<br/>"
        "• <b>Headword References</b>: Companion vocabulary PDFs explicitly display cross-references and inflected headword targets (e.g. <b>wobble (Ref: Wabble) (32.5)</b>) in high-contrast italic slate typography.<br/>"
        "• <b>Difficulty Scores</b>: Word rarity scores (1–100) are displayed right beside headwords in slate gray (e.g. <b>anopheles (29.25)</b>).<br/>"
        "• <b>Word Frequency Filtering</b>: An interactive slider dialog lets you filter vocabulary density (1 to 100) before generating the sidecar PDF.<br/>"
        "• <b>Instant Definition & Synonyms Popup</b>: Selecting any recognized word in the reader opens a lower-pane quick popup displaying part of speech, definition, synonyms, and antonyms. The popup always renders in the lower pane during split view (even when Google is open) and auto-dismisses smoothly when opening Google search.<br/>"
        "• <b>Free Online Dictionary Fallback</b>: If a selected word is missing from the local offline dictionary, the reader queries the free online dictionary API in the background, displaying definitions, synonyms, antonyms, and an <b>online</b> badge.",
        body_style
    ))

    story.append(Paragraph("4. Immersive Fullscreen Zen Mode & Gestures (Double & Triple Tap)", h1_style))
    story.append(Paragraph(
        "Activating Zen Mode hides all toolbars as well as the system status bar and navigation bar for 100% immersive edge-to-edge reading.<br/>"
        "• <b>Triple-Tap in Normal Mode</b>: Instantly enters Zen Mode with sidebar buttons hidden for a totally clean distraction-free view.<br/>"
        "• <b>Triple-Tap in Zen Mode</b>: Instantly exits Zen Mode directly back to Normal Mode without showing the sidebar buttons.<br/>"
        "• <b>Double-Tap in Zen Mode</b>: Toggles (hides/unhides) the right-edge sidebar controls on demand.<br/>"
        "Control strip visibility state is preserved across screen orientation changes.",
        body_style
    ))

    story.append(Paragraph("5. Unified Multi-Page Highlights & Selection Management", h1_style))
    story.append(Paragraph(
        "When selecting and highlighting text that spans across multiple consecutive pages (e.g. Pages 31-34), PdfHelperReader automatically "
        "unifies them into a single coherent entry in the Highlights dialog (<b>HL</b>). The entry displays the full joined text, a multi-page range badge, "
        "jumps directly to the starting page when tapped, and deletes all linked page segments across the entire document in one action.",
        body_style
    ))

    story.append(Paragraph("6. Smooth Center-Anchored Two-Finger Pinch-to-Zoom", h1_style))
    story.append(Paragraph(
        "Fluid two-finger pinch-to-zoom allows seamless magnification on both single-page (<b>PageView</b>) and continuous vertical scroll (<b>ContinuousPageView</b>) "
        "modes. The zoom gesture anchors precisely around the center point of each pane, spreading outward symmetrically in all directions "
        "with a silky <b>0.085f</b> smoothing factor.",
        body_style
    ))

    story.append(Paragraph("7. Instant Copy-to-Search Popup Banner", h1_style))
    story.append(Paragraph(
        "Whenever you select and copy text anywhere in the app or system, a floating popup banner appears allowing you to search the "
        "copied string in Google with a single tap. The banner responds reliably to every copy action, even when re-copying the same "
        "selection.",
        body_style
    ))

    story.append(Paragraph("8. In-App Updates & Interactive Developer Info", h1_style))
    story.append(Paragraph(
        "Tapping <b>Update</b> checks GitHub for new release versions with a 3-tier fallback (API, Web Redirect, Feed), downloads the APK in the background "
        "with a live progress dialog, and launches the Package Installer. Tapping <b>App Info</b> displays developer details and an interactive clickable "
        "link taking you directly to the GitHub repository.",
        body_style
    ))

    story.append(Paragraph("9. Gutenberg Books Search Engine (LOB Tab 2)", h1_style))
    story.append(Paragraph(
        "Tapping <b>LOB</b> opens the dual-tab Online Library. The Gutenberg Books tab features live search across 70,000+ books with direct "
        "<b>[ EPUB ]</b> and <b>[ PDF ]</b> download options.",
        body_style
    ))

    story.append(Paragraph("10. Full EPUB eBook Support", h1_style))
    story.append(Paragraph(
        "PdfHelperReader unzips and converts eBooks on-demand into clean, typeset vector documents with large 18pt typography. "
        "Converted files are cached permanently in <i>Downloads/PdfHelperReader_files/</i> so re-opening an EPUB loads instantly in 0ms.",
        body_style
    ))

    story.append(Paragraph("11. 500 MB Storage Manager", h1_style))
    story.append(Paragraph(
        "An automatic 500 MB Storage Manager monitors folder size, deleting older files step-by-step while protecting the latest active file in "
        "each category.",
        body_style
    ))

    story.append(Paragraph("12. Dual-Tab History & Persistent App State", h1_style))
    story.append(Paragraph(
        "The History dialog (<b>H</b>) features two independent tabs: <b>Reading History</b> and <b>Search History</b>.<br/>"
        "• <b>Search History</b>: Tracks all past Google searches. Tapping any item re-executes the search instantly, with single-tap deletion and a <b>Clear All</b> option.<br/>"
        "• <b>Tab State & Scroll Persistence</b>: Tab selection and exact scroll positions are preserved across dialog dismissals and app restarts.<br/>"
        "• <b>Ribbon Toolbar Position Preservation</b>: The horizontal scroll offset of the upper ribbon toolbar is saved permanently and restored smoothly across Zen mode toggles and app restarts.",
        body_style
    ))

    story.append(Paragraph("13. Precision Text Selection, Magnifier Loupe & Smart Search", h1_style))
    story.append(Paragraph(
        "• <b>Floating Selection Magnifier Loupe</b>: Pressing down on a word or dragging the Start/End selection handles displays a <b>1.45×</b> magnified capsule loupe above your finger showing the text, highlights, and vertical handle guide pin. The loupe clamps cleanly to screen edges and automatically flips below your finger near the top of a page.<br/>"
        "• <b>Precision Drag Selection</b>: Long-pressing on any word reliably selects the entire word cleanly without truncation, while dragging the selection handles allows fine-grained, sub-word precision without rigid word snapping.<br/>"
        "• <b>Smart Search Query Formatting</b>: Initiating a Google search from selected text automatically crafts the search query: single words search <i>meaning of this: \"word\"</i>, while multi-word phrases search <i>meaning of this part by part: \"phrase\"</i>.",
        body_style
    ))

    story.append(Paragraph("14. Built-in File Browser & System File Association", h1_style))
    story.append(Paragraph(
        "• <b>System \"Open With\" Chooser Integration</b>: Registered system intent filters allow tapping any PDF or EPUB file in external file managers, Downloads, messaging apps, and email attachments to open directly in PdfHelperReader.<br/>"
        "• <b>Device Storage Scanner</b>: Tapping the <b>Browse</b> button in the ribbon scans device storage for PDF and EPUB files with real-time status and manual refresh.<br/>"
        "• <b>Sidebar Sorting & State Memory</b>: The right sidebar lets you switch instantly between <b>Time</b> (most recently modified first) and <b>Name</b> (A–Z). Sorting preferences and exact list scroll positions are permanently remembered across app launches.<br/>"
        "• <b>Min/Max Split Docking</b>: Tapping <b>Min/Max</b> at the bottom of the sidebar docks the browser into the lower split pane so you can browse books while reading in the upper pane. Min mode displays only the clean book list and right-edge sidebar with theme-protected surface colors.<br/>"
        "• <b>Automatic Zen Mode Dismissal</b>: Entering Zen Mode automatically closes any open or docked browser, ensuring an undisturbed distraction-free reading experience.",
        body_style
    ))

    story.append(Paragraph("15. Encrypted PDF Support & Resilient Extraction", h1_style))
    story.append(Paragraph(
        "• <b>AES-128 & AES-256 PDF Compatibility</b>: Built-in SpongyCastle cryptography enables smooth native text extraction, vocabulary generation, and word selection on permission-protected AES-encrypted PDFs.<br/>"
        "• <b>Graceful Fallback Protection</b>: Non-fatal linkage and decryption errors are safely caught so protected or unusual PDFs gracefully fall back to on-device ML Kit OCR without crashing.",
        body_style
    ))

    doc.build(story, canvasmaker=NumberedCanvas)
    print("Built large font PDF:", filename)

if __name__ == '__main__':
    target = sys.argv[1] if len(sys.argv) > 1 else 'tutorial.pdf'
    build_pdf(target)
