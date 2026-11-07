# AGENTS.md

Instructions for AI coding agents (Opencode, Kilocode, or any AGENTS.md-compatible tool).
Read this file and `plan.md` before changing anything. Work one plan item at a time and tick it off in `plan.md`.

## Project

Android PDF reader in Kotlin. Features: open PDFs (file picker and in-app library), scroll/zoom/jump to page,
bookmarks, last-read position, text search, text selection, highlights/underlines written into the PDF file.

Package: `com.faridhafizh.pdfreader`. minSdk 31, compile/target SDK 36.

## Stack (as built)

| Area | Choice |
|---|---|
| UI | Jetpack Compose, Material 3 (dynamic color), Navigation Compose |
| Architecture | MVVM, `StateFlow`, repository. **Manual DI** via `AppContainer` (no Hilt) |
| Storage | Room (documents, bookmarks, annotations). PDFs are **copied into app storage** (`filesDir/pdfs`) on import |
| Rendering | PDFium binding (`com.shockwave.pdfium.PdfiumCore`, JitPack fork with 16 KB page-size support) |
| Text search/selection | PDFBox-Android (`PDFTextStripper` gives per-character positions), because PDFium wrappers do not expose text APIs |
| Annotation writing | PDFBox-Android (`PDAnnotationTextMarkup`, standard /Highlight and /Underline) |
| Zoom | Zoomable Compose (Telephoto `Modifier.zoomable`, per-page, 1x–4x anchored at focal point + 2D pan) |
| Build | Gradle Kotlin DSL, version catalog `gradle/libs.versions.toml`, KSP for Room |

Versions in the catalog were pinned from memory and have not been verified against current releases. Before adding or
bumping a dependency, check its latest stable version. Android Studio's AGP Upgrade Assistant can bump AGP/Gradle.

## Layout

```
app/src/main/java/com/faridhafizh/pdfreader/
  PdfReaderApp.kt, AppContainer.kt, MainActivity.kt   app entry, manual DI, navigation
  data/        Room entities/DAOs/database, DocumentRepository
  pdf/         PdfSession (PDFium), TextIndex (PDFBox text), AnnotationWriter (PDFBox), ThumbnailLoader
  ui/library/  Library screen + ViewModel
  ui/reader/   Reader screen + ViewModel (pages, zoom, search, selection, annotations, bookmarks)
  ui/theme/    Theme
```

## Key design decisions (do not change without asking)

1. **Annotations**: Room is the working copy. States: `PENDING_ADD` (db only), `SYNCED` (also in the PDF), `PENDING_DELETE`.
   `AnnotationWriter.apply` writes pending changes into the PDF: temp file, verify it opens, atomic move over the original.
   Each embedded annotation stores the app id in its `/NM` entry so it can be removed later.
2. **Overlay drawing**: PDFium renders page content only (annotation flag off). All annotations are drawn by the Compose
   overlay from Room data, so nothing is drawn twice. Annotations already present in imported PDFs are therefore not displayed (known limitation).
3. **Coordinates**: annotation rectangles are stored as fractions (0..1) of the displayed page, origin top-left.
   `AnnotationWriter.toUser` converts to PDF user space, including page rotation and crop box.
4. **Single source of page size**: PDFium page sizes drive layout (aspect ratio); PDFBox `TextPosition` fractions use the same displayed-page space.
5. **Memory**: pages render as RGB_565 bitmaps, width capped at 2400 px, bucketed to 128 px steps, kept in an `LruCache` (1/4 of heap).
   All PDFium calls are serialized by a mutex in `PdfSession`. Always close sessions and native documents.
6. **Import copies the file**, so writing annotations never needs SAF write permission. "Export copy…" saves the annotated file elsewhere.

## Commands

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lint
```
On Windows use `gradlew.bat`, or run from Android Studio.

## Conventions

- Kotlin official style. Immutable data classes, `val` by default. No blocking work on the main thread.
- Composables stateless where possible; state in ViewModels exposed as `StateFlow`.
- UI strings are currently inline for speed. Move them to `strings.xml` before release (plan Phase 7).
- One logical change per commit. State why in the message when adding a dependency.

## Boundaries

- Ask before: changing minSdk, swapping a PDF library, changing the Room schema (add a migration), adding permissions or network access.
- Never: commit keystores or secrets, disable lint to hide warnings, delete tests to get a green build.
- The app is offline-first: no network access, no analytics.

## Definition of done

Builds, lint clean, tests pass, no unclosed native handles, `plan.md` updated.
