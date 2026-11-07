# plan.md

Status legend: [x] implemented in code (not yet run on a device), [ ] not started.
Everything marked [x] was written without being compiled or run. Expect to fix compile errors and verify behavior on a device first.

## Decisions

- Kotlin, Jetpack Compose, minSdk 31, MVVM + Room, manual DI
- PDFium (rendering) + PDFBox-Android (text positions, annotation writing)
- Sources: file picker and in-app library. Picker imports (copies) the PDF into app storage
- Highlights/underlines are embedded in the PDF file itself
- Package `com.faridhafizh.pdfreader`, launcher icon from `logo.png`
- Zoom via Zoomable Compose (per-page, anchored at pinch focal point, double-tap zoom)
- Scan via CameraX (multi-page, PDFBox build), signatures local-only (PNG RGBA, threshold-kaku cleanup), hybrid overlay + embed via SignatureWriter
- Room v2: `signatures` + `signature_placements` (migration 1→2), placements reuse annotation state machine
- Scan: auto-shutter (stability ≥1.3s, coverage ≥45%) + warp persegi panjang (homography murni), manual shutter tetap
- Signatures draw: kanvas 220dp (2:1 tersebar lebar), coretan dinormalisasi fraksi 0..1, stroke 6% lebar
- Menus dirampingkan: Reader pinned Search + Save kondisional; Library satu "+ Tambah" menu

## Phase 0: Verify the build (do this first)

- [ ] Open the project in Android Studio, let Gradle sync, fix any version/compile errors
- [ ] Confirm the PDFium JitPack dependency resolves (fallback: `com.github.barteksc:pdfium-android:1.9.0`)
- [ ] Run on a device/emulator with a text PDF, a scanned PDF and a 300+ page PDF

## Phase 1: Project setup
- [x] Gradle KTS, version catalog, Compose, Room (KSP), Navigation, theme
- [ ] Add lint/ktlint or detekt

## Phase 2: Open and display
- [x] File picker import + "Open with" intent filter
- [x] Reader with lazy page rendering, bitmap cache, page indicator
- [x] Pinch zoom (1x to 4x) with 2D pan, jump to page dialog
- [ ] Tiled rendering for sharp output above 2400 px width
- [x] Zoom around the pinch focus point
- [ ] Password-protected PDFs (prompt for password)

## Phase 3: Library and last-read position
- [x] Library list with thumbnail, progress, delete
- [x] Resume at last page, saved (debounced) while reading
- [ ] Sort/search in library, rename

## Phase 4: Bookmarks
- [x] Toggle bookmark on current page, bookmark list sheet, jump, delete
- [ ] Optional bookmark labels

## Phase 5: Text search and selection
- [x] Full-document search with incremental results, next/previous, highlighted hits
- [x] Long-press and drag to select text on a page; copy
- [ ] Selection across pages
- [ ] Faster text engine for large PDFs (PDFBox loads the whole file)

## Phase 6: Highlights and annotations
- [x] Highlight (4 colors) and underline from a selection
- [x] Tap an annotation to delete it
- [x] Embed into the PDF (temp file, verify, atomic replace); save button with pending badge; auto-save when leaving
- [x] Export copy via system file picker
- [ ] Verify annotations appear in other viewers (Adobe, Chrome, Drive) on rotated and cropped pages
- [ ] Sticky-note annotations, undo/redo
- [ ] Show annotations that already exist in imported PDFs

## Phase 7: Polish
- [ ] Move UI strings to strings.xml, tablet layout (app icon done)
- [ ] Dark reading mode (invert page colors)
- [ ] Accessibility pass, tests (coordinate conversion, repository, annotation round-trip)
- [ ] Enable R8 and test the release build

## Phase 8: Scan & signatures
- [x] Scan to PDF (CameraX multi-page, thumbnail strip delete/reorder, Save to library)
- [x] Scan auto-shutter + warp persegi panjang (toggle Auto/Manual, tombol manual tetap)
- [x] Signature extraction (crop manual + draw manual + threshold-kaku cleanup, white -> alpha 0)
- [x] Signature library (local PNG, grid, delete)
- [x] Full editor overlay (drag / resize / rotate, target this page / next 6 / all pages)
- [x] Hybrid save (overlay Room + embed XObject into PDF via SignatureWriter, pending badge)
- [x] Menus dirampingkan (Reader Search+Save kondisional, Library satu "+ Tambah" menu)

## Known limitations
- Page content is white in dark theme (no page inversion yet)
- Annotations in imported PDFs are not displayed (only the app's own)
- Large PDFs: PDFBox memory use; very high zoom is rendered at capped resolution
