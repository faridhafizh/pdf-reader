# PDF Reader

Android PDF reader — buka PDF, baca dengan zoom fokus, cari teks, highlight/underline, scan dokumen lewat ML Kit, dan tempel tanda tangan yang bisa disimpan permanen ke PDF. Offline-first, tanpa analitik.

- **Package:** `com.faridhafizh.pdfreader`
- **minSdk 31 · target/compile SDK 36**

## Fitur

- **Library:** file picker (SAF) + *Open with* intent; thumbnail, progress baca, hapus. Posisi baca terakhir disimpan (debounced) dan dilanjutkan saat dibuka kembali.
- **Reader:** scroll vertikal lazy, pinch zoom 1×–4× *anchored di titik fokus pinjam* + pan 2D (Telephoto) + double-tap toggle, lompat ke halaman, Page indicator.
- **Search:** incremental full-document search, next/prev, highlight hasil.
- **Text selection:** long-press lalu drag memilih teks, Copy.
- **Highlights & underlines (4 warna):** tap anotasi untuk hapus, Save badge pending, auto-save saat keluar, Export copy ke lokasi pilihan.
- **Scan to PDF (ML Kit Document Scanner):** auto-capture + edge detection + rotate + enhance/filter/clean bawaan Google Play Services; tanpa izin kamera di app; multi-page (maks 5) + gallery import; hasil langsung masuk library.
- **Signatures (lokal):**
  - Ekstraksi **crop manual** dari foto **atau gambar manual** di kanvas 220dp portrait; coretan dinormalisasi fraksi 0..1 (anti gepeng), stroke tipis default 2.5% + slider 1–6%.
  - **Cleanup threshold kaku:** putih kertas → alpha 0 (100% transparan), tinta solid hitam + slider intensitas fallback.
  - Library grid + hapus.
  - **Placement editor di reader:** drag / resize / rotate, target *halaman ini / 6 berikutnya / semua halaman*.
  - **Hybrid save:** overlay di aplikasi dari Room + tombol Save embed gambar sebagai XObject ke file PDF (temp → verify → atomic replace); export copy ikut ter-embed.
- **Bookmarks:** toggle, list, lompat, hapus.

## Stack

| Area | Pilihan (stabil terverifikasi) |
|---|---|
| UI | Jetpack Compose, Material 3 (dynamic color), Navigation Compose |
| Arsitektur | MVVM, `StateFlow`, repository, **Manual DI** via `AppContainer` (no Hilt) |
| Penyimpanan | Room (documents, bookmarks, annotations, signatures, signature_placements). PDF **dicopy ke app storage** (`filesDir/pdfs`) saat import |
| Rendering | PDFium (`com.shockwave.pdfium.PdfiumCore`, fork JitPack 16KB) |
| Teks/seleksi | PDFBox-Android (`PDFTextStripper` per-character) |
| Tulis anotasi | PDFBox-Android (`PDAnnotationTextMarkup`: `/Highlight`, `/Underline`) |
| Zoom | Zoomable Compose Telephoto `me.saket.telephoto:zoomable:0.19.0` |
| Scan | **ML Kit Document Scanner via Play Services** `play-services-mlkit-document-scanner:16.0.0` (on-device, Play Services download sekali, lalu offline; butuh ≥1.7GB RAM). CameraX tetap sebagai dependency fallback/manual shutter lama. |
| Build | Gradle Kotlin DSL (8.14.2 via wrapper), version catalog `gradle/libs.versions.toml`, KSP Room |

## Struktur

```
app/src/main/java/com/faridhafizh/pdfreader/
  PdfReaderApp.kt, AppContainer.kt, MainActivity.kt   entry, manual DI, navigasi
  data/        Room entities/DAOs/database (v2), DocumentRepository
  pdf/         PdfSession (PDFium), TextIndex (PDFBox), AnnotationWriter, SignatureWriter,
                SignatureCleanup, ScanPdfBuilder, DocumentDetect, DocumentWarp, ThumbnailLoader
  ui/library/  Library screen + ViewModel (+ menu Tambah: Add PDF / Scan / Signatures)
  ui/reader/   Reader screen + ViewModel (pages, zoom, search, selection, annotations, signatures, bookmarks)
  ui/scan/     Scan screen + ViewModel (ML Kit launcher)
  ui/signatures/ Signatures screen + ViewModel (crop/draw/cleanup)
  ui/theme/    Theme
```

## Keputusan desain penting

1. **Annotations & signature placements** memakai mesin status yang sama: `PENDING_ADD` (db saja), `SYNCED` (juga di PDF), `PENDING_DELETE`. Writer menulis ke file temp → verifikasi → *atomic move*. Anotasi menyimpan id di `/NM`; placements menanam XObject gambar (rotasi dibakar ke bitmap agar tampil konsisten di viewer lain) dan cover putih saat dihapus.
2. **Overlay:** PDFium render konten saja (flag anotasi mati). Semua anotasi & tanda tangan digambar Compose dari data Room/Mathbox + Bitmap loader → tidak digambar dua kali. Keterbatasan: anotasi yang sudah ada di PDF import tidak tampil di overlay.
3. **Koordinat:** semua rect/placement disimpan sebagai **fraksi 0..1 halaman display, origin kiri-atas**. `AnnotationWriter.toUser`/`SignatureWriter` konversi ke PDF user space (rotasi + crop box).
4. **Satu sumber ukuran halaman:** PDFium `getPageSize` untuk layout aspect ratio; PDFBox `TextPosition` fraksinya ruang display yang sama.
5. **Memory:** bitmap RGB_565 lebar ≤ 2400px, bucket 128px, (`LruCache(1/4 heap)`). Setiap render PDFium diserialkan mutex. Halaman ditutup & `PdfDocument` ditutup.
6. **Import menyalin file** → tidak butuh izin SAF tulis. “Export copy…” salin file beranotasi ke tujuan pilihan.
7. **Scan ML Kit** tidak butuh izin kamera; hasil PDF ditulis ulang oleh Play Services. Mode `FULL` (filters + clean), `pageLimit 5`, `galleryImport true`, format PDF+JPEG; pref Rusia: PDF langsung diimpor ke library.

## Build & jalankan

```bash
# JDK yang dipakai: Temurin 17 (JAVA_HOME). SDK: C:\Users\...\AppData\Local\Android\Sdk
./gradlew assembleDebug                    # Windows: gradlew.bat assembleDebug
./gradlew testDebugUnitTest                # belum ada test (input kosong)
./gradlew lint
# Android Studio: Sync gradle, Run app.
```

**Pasang via USB debugging:**

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Pastikan USB debugging aktif dan HP mengetuk **Allow** pada dialog RSA saat pertama tersambung. Package terpasang sebagai `com.faridhafizh.pdfreader` v0.1.0.

**Build path penting:**
- `app/build.gradle.kts` (namespace/applicationId baru), `gradle/libs.versions.toml` (versions terpin dari versi stabil terakhir per dependency; cek ulang sebelum bump).
- Android Studio AGP Upgrade Assistant untuk bump AGP/Gradle bila perlu.

## Detail teknikal (untuk dev)

- **Database Room v2:** tabel `signatures(id, fileName, widthPx, heightPx, createdAt)` + `signature_placements(id, documentId FK CASCADE, signatureId, page, centerX, centerY, widthFrac, rotationDeg, state, createdAt)`. Migrasi `MIGRATION_1_2` membuat kedua tabel + index. `exportSchema=false`.
- **Repository:** `observeCombinedPending` gabungan anotasi+placements untuk badge Save. `importScannedPdf(images)` dan `importScannedPdfFromUris(uris)` (ML Kit fallback JPEG).
- **Scan:** `ScanScreen` launcher `StartIntentSenderForResult`; options `SCANNER_MODE_FULL`, `pageLimit 5`, format PDF+JPEG. `ScanViewModel.importScannedUri/importScannedUris`.
- **Detector lama (fallback):** `DocumentDetect` (adaptive threshold + Sobel edge-snap + EMA smooth + convex check) & `DocumentWarp` (homography murni) tetap disertakan untuk keperluan fallback/manual shutter; pipeline auto yang sekarang dikunci state machine sekali-jepret → editor tidak dipakai lagi bila ML Kit tersedia.
- **TTD Cleanup (`SignatureCleanup.clean`):** luminance `(0.299R+0.587G+0.114B)`; ≥ threshold (default 235, slider 200–250) → alpha 0; di bawahnya alpha = `(threshold-lum)/(threshold+1)*255*boost` (boost 1–3, default 2.2), warna tinta dipaksa hitam. `trimTransparent(pad 6)` + `scaleDown(maxW 1200)`.
- **Placement overlay (`SignaturePlacementView`):** posisi px dari fraksi × viewport, hit-test rotasi (`placementContains`), pan/zoom `EnabledZoomGestures` dimatikan saat seleksi/placement dipilih agar drag menggeser TTD (bukan halaman). Rotasi handle pakai heuristik horizontal saat endpoint auditor.
- **Upload bitmap TTD:** `Save → PNG (100)` → `saveSignature(ByteArrayInputStream, w, h)`.

## Permission & privasi

- `CAMERA` (warisan CameraX lama untuk fallback manual; ML Kit tidak membutuhkannya).
- App **offline-first**: tanpa network, tanpa analitik. ML Kit model diunduh **sekali** via Play Services saat pertama scan (butuh internet sekali), lalu offline penuh.
- Tanda tangan disimpan lokal (`filesDir/signatures`, PNG RGBA), tidak dibagikan ke server.

## Keterbatasan mapan

- Halaman PDF putih di dark theme (tanpa inversi halaman).
- Anotasi/TTD yang sudah ada di PDF import tidak tampil di overlay (hanya milik aplikasi).
- PDF besar: PDFBox memuat seluruh file untuk teks (memori tinggi); zoom sangat tinggi render di resolusi ter-cap 2400px.
- TTD yang sudah `SYNCED` lalu dihapus menyiapkan cover putih — bentuk XObject lama tidak benar-benar dihapus dari stream PDF.
- ML Kit Document Scanner butuh Play Services + RAM ≥1.7GB; HP lama tanpa Play Services akan mendapat `UNSUPPORTED`.
