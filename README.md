# PDF Reader

An offline-first Android PDF reader built with Kotlin and Jetpack Compose. Read with focus-anchored zoom, search full documents, annotate, scan paper documents with ML Kit, and place signatures that are permanently embedded into the PDF file. No accounts, no analytics.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-31-blue)
![targetSdk](https://img.shields.io/badge/target%20%2F%20compile%20SDK-36-blue)
![Version](https://img.shields.io/badge/version-0.1.0-lightgrey)
![License](https://img.shields.io/badge/license-MIT-green)

<!-- Add screenshots here, e.g.:
<p align="center">
  <img src="docs/screenshots/library.png" width="24%" />
  <img src="docs/screenshots/reader.png" width="24%" />
  <img src="docs/screenshots/scan.png" width="24%" />
  <img src="docs/screenshots/signature.png" width="24%" />
</p>
-->

**Package:** `com.faridhafizh.pdfreader`

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Getting Started](#getting-started)
- [Technical Notes](#technical-notes)
- [Permissions & Privacy](#permissions--privacy)
- [Known Limitations](#known-limitations)
- [License](#license)

## Features

### Library
- Import PDFs via the system file picker (SAF) or the *Open with* intent
- Thumbnails, reading progress, and delete
- Last reading position is saved (debounced) and restored on reopen

### Reader
- Lazy vertical scrolling
- Pinch zoom from 1× to 4×, anchored at the focal point, with 2D pan and double-tap to toggle zoom
- Jump to page and page indicator
- Bookmarks: toggle, list, jump, delete

### Search & Selection
- Incremental full-document search with next/previous navigation and highlighted matches
- Long-press and drag to select text, then copy

### Annotations
- Highlight and underline in four colors
- Tap an annotation to remove it
- Pending-save badge, auto-save on exit, and *Export copy* to a location of your choice

### Scan to PDF
- Powered by the ML Kit Document Scanner: auto-capture, edge detection, rotation, and enhancement filters
- Multi-page scans (up to 5 pages) and gallery import
- Scanned PDFs are added to the library automatically
- No in-app camera permission required

### Signatures (local only)
- **Create** a signature by manually cropping a photo, or by drawing on a 220 dp portrait canvas
  - Strokes are normalized to 0..1 fractions to prevent distortion; default stroke width is 2.5% (adjustable 1–6%)
- **Clean up** automatically: paper-white becomes fully transparent and ink becomes solid black, with an intensity slider as fallback
- **Manage** signatures in a library grid
- **Place** signatures in the reader: drag, resize, and rotate, applied to *this page*, *the next 6 pages*, or *all pages*
- **Hybrid save:** signatures are shown instantly as an in-app overlay (from Room), and the Save button embeds them into the PDF as image XObjects (temp file → verify → atomic replace). Exported copies include the embedded signatures

## Tech Stack

| Area | Choice |
|---|---|
| UI | Jetpack Compose, Material 3 (dynamic color), Navigation Compose |
| Architecture | MVVM, `StateFlow`, repository pattern, manual DI via `AppContainer` (no Hilt) |
| Storage | Room (documents, bookmarks, annotations, signatures, signature placements); imported PDFs are copied to `filesDir/pdfs` |
| Rendering | PDFium via `com.shockwave.pdfium.PdfiumCore` (JitPack fork with 16 KB page-size support) |
| Text & selection | PDFBox-Android (`PDFTextStripper`, per-character positions) |
| Annotation writing | PDFBox-Android (`PDAnnotationTextMarkup`: `/Highlight`, `/Underline`) |
| Zoom | [Telephoto](https://github.com/saket/telephoto) `me.saket.telephoto:zoomable:0.19.0` |
| Scanning | ML Kit Document Scanner via Play Services (`play-services-mlkit-document-scanner:16.0.0`); on-device, requires ≥ 1.7 GB RAM |
| Build | Gradle Kotlin DSL (8.14.2 via wrapper), version catalog (`gradle/libs.versions.toml`), KSP for Room |

CameraX remains as a dependency for the legacy manual-shutter fallback.

## Architecture

```
app/src/main/java/com/faridhafizh/pdfreader/
├── PdfReaderApp.kt, AppContainer.kt, MainActivity.kt   # entry point, manual DI, navigation
├── data/          # Room entities/DAOs/database (v2), DocumentRepository
├── pdf/           # PdfSession (PDFium), TextIndex (PDFBox), AnnotationWriter, SignatureWriter,
│                  # SignatureCleanup, ScanPdfBuilder, DocumentDetect, DocumentWarp, ThumbnailLoader
└── ui/
    ├── library/     # Library screen + ViewModel (Add menu: Add PDF / Scan / Signatures)
    ├── reader/      # Reader screen + ViewModel (pages, zoom, search, selection,
    │                #   annotations, signatures, bookmarks)
    ├── scan/        # Scan screen + ViewModel (ML Kit launcher)
    ├── signatures/  # Signatures screen + ViewModel (crop / draw / cleanup)
    └── theme/       # Theme
```

### Key Design Decisions

1. **Shared sync state machine.** Annotations and signature placements both use `PENDING_ADD` (database only) → `SYNCED` (also in the PDF) → `PENDING_DELETE`. Writers output to a temp file, verify it, then atomically move it into place. Annotations store their id in `/NM`; placements embed an image XObject (rotation is baked into the bitmap so it looks identical in other viewers) and draw a white cover when deleted.
2. **Compose overlay.** PDFium renders page content only (annotation flag off). All annotations and signatures are drawn by Compose from Room data, so nothing is drawn twice.
3. **Normalized coordinates.** Every rect and placement is stored as a 0..1 fraction of the displayed page with a top-left origin. `AnnotationWriter` and `SignatureWriter` convert to PDF user space, accounting for rotation and crop box.
4. **Single source of page size.** PDFium `getPageSize` drives layout aspect ratio, and PDFBox `TextPosition` values are expressed in the same display space.
5. **Memory management.** RGB_565 bitmaps with width ≤ 2400 px, 128 px size buckets, and an `LruCache` of 1/4 heap. PDFium renders are serialized with a mutex, and pages and `PdfDocument` instances are closed after use.
6. **Copy on import.** Files are copied into app storage, so no SAF write permission is needed. *Export copy…* writes the annotated file to a user-chosen destination.
7. **ML Kit scanning.** Mode `FULL` (filters + clean), `pageLimit 5`, gallery import enabled, PDF + JPEG output. The resulting PDF is imported into the library directly.

## Getting Started

### Prerequisites

- JDK 17 (Temurin recommended)
- Android SDK with API 36 platform
- Android Studio (latest stable) or the Gradle wrapper

### Build

```bash
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
./gradlew testDebugUnitTest      # no unit tests yet
./gradlew lint
```

Or open the project in Android Studio, sync Gradle, and run the `app` configuration.

### Install on a device via USB

1. Enable **USB debugging** and accept the RSA prompt on the device the first time it connects.
2. Install the debug build:

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Build files of interest

- `app/build.gradle.kts`: namespace and `applicationId`
- `gradle/libs.versions.toml`: pinned dependency versions (re-check for newer stable releases before bumping)
- Use the Android Studio AGP Upgrade Assistant when updating AGP or Gradle

## Technical Notes

**Database (Room v2).** Tables `signatures(id, fileName, widthPx, heightPx, createdAt)` and `signature_placements(id, documentId FK CASCADE, signatureId, page, centerX, centerY, widthFrac, rotationDeg, state, createdAt)`. `MIGRATION_1_2` creates both tables and their indexes. `exportSchema = false`.

**Repository.** `observeCombinedPending` merges pending annotations and placements to drive the Save badge. `importScannedPdf(images)` and `importScannedPdfFromUris(uris)` handle scan results (the latter covers the ML Kit JPEG fallback).

**Scanning.** `ScanScreen` launches the scanner with `StartIntentSenderForResult` (`SCANNER_MODE_FULL`, `pageLimit 5`, PDF + JPEG). `ScanViewModel` exposes `importScannedUri` / `importScannedUris`.

**Legacy detector (fallback).** `DocumentDetect` (adaptive threshold, Sobel edge snapping, EMA smoothing, convexity check) and `DocumentWarp` (pure homography) remain for the manual-shutter fallback. When ML Kit is available, the older single-shot state-machine pipeline and editor are not used.

**Signature cleanup (`SignatureCleanup.clean`).**
- Luminance is `0.299R + 0.587G + 0.114B`
- Pixels at or above the threshold (default 235, slider range 200–250) become alpha 0
- Below the threshold, alpha = `(threshold − lum) / (threshold + 1) × 255 × boost` (boost 1–3, default 2.2), with ink color forced to black
- Followed by `trimTransparent(pad = 6)` and `scaleDown(maxW = 1200)`

**Placement overlay (`SignaturePlacementView`).** Pixel positions are computed from fractions × viewport size, with rotation-aware hit testing (`placementContains`). Pan/zoom gestures are disabled while a selection or placement is active so dragging moves the signature rather than the page.

**Saving a signature.** The bitmap is encoded to PNG (quality 100) and stored via `saveSignature(ByteArrayInputStream, w, h)`.

## Permissions & Privacy

- **Offline-first:** no network access and no analytics in the app itself.
- **ML Kit model download:** the scanner model is downloaded once through Google Play Services on first scan (internet required once), then works fully offline.
- **Local signature storage:** signatures are stored as RGBA PNGs in `filesDir/signatures` and are never uploaded.
- **`CAMERA` permission:** declared only for the legacy CameraX manual-shutter fallback. The ML Kit scanner does not need it.

## Known Limitations

- PDF pages stay white in dark theme (no page inversion).
- Annotations and signatures that already exist in an imported PDF are not shown in the overlay; only the app's own are.
- Large PDFs: PDFBox loads the whole file for text extraction (high memory use), and very high zoom is capped at 2400 px render width.
- Deleting a `SYNCED` signature adds a white cover; the original XObject is not removed from the PDF stream.
- ML Kit Document Scanner needs Google Play Services and at least 1.7 GB RAM. Devices without Play Services return `UNSUPPORTED`.

## License

Released under the [MIT License](LICENSE).
