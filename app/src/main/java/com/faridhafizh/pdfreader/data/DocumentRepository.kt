package com.faridhafizh.pdfreader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.faridhafizh.pdfreader.pdf.AnnotationWriter
import com.faridhafizh.pdfreader.pdf.PdfSession
import com.faridhafizh.pdfreader.pdf.ScanPdfBuilder
import com.faridhafizh.pdfreader.pdf.SignatureWriter
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class DocumentRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val core: PdfiumCore,
    private val writer: AnnotationWriter,
    private val sigWriter: SignatureWriter,
) {
    private val dir = File(context.filesDir, "pdfs").apply { mkdirs() }
    private val sigDir = File(context.filesDir, "signatures").apply { mkdirs() }
    private val syncMutex = Mutex()

    fun documents() = db.documentDao().observeAll()
    suspend fun getDocument(id: Long) = db.documentDao().get(id)
    fun fileOf(d: DocumentEntity) = File(dir, d.fileName)

    /** Copies the PDF into app storage so we can always write annotations into it. */
    suspend fun importPdf(uri: Uri): Long = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "document.pdf"
        val fileName = UUID.randomUUID().toString() + ".pdf"
        val target = File(dir, fileName)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read file")
            input.use { src -> target.outputStream().use { src.copyTo(it) } }
            val pages = PdfSession.open(core, target, allSizes = false).use { it.pageCount }
            val now = System.currentTimeMillis()
            db.documentDao().insert(
                DocumentEntity(
                    title = name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), ""),
                    fileName = fileName,
                    pageCount = pages,
                    lastOpened = now,
                    addedAt = now,
                ),
            )
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    suspend fun deleteDocument(d: DocumentEntity) = withContext(Dispatchers.IO) {
        db.documentDao().delete(d)
        fileOf(d).delete()
    }

    suspend fun updateProgress(id: Long, page: Int) =
        db.documentDao().updateProgress(id, page, System.currentTimeMillis())

    // ---- bookmarks ----
    fun observeBookmarks(docId: Long) = db.bookmarkDao().observe(docId)

    suspend fun toggleBookmark(docId: Long, page: Int) {
        val dao = db.bookmarkDao()
        if (dao.countAt(docId, page) > 0) dao.deleteAt(docId, page)
        else dao.insert(BookmarkEntity(documentId = docId, page = page, label = "Page ${page + 1}", createdAt = System.currentTimeMillis()))
    }

    suspend fun deleteBookmark(id: Long) = db.bookmarkDao().delete(id)

    // ---- annotations ----
    fun observeAnnotations(docId: Long) = db.annotationDao().observeVisible(docId)
    fun observePendingCount(docId: Long) = db.annotationDao().observePendingCount(docId)

    suspend fun addAnnotation(docId: Long, page: Int, type: String, color: Int, rects: List<NRect>) {
        db.annotationDao().insert(
            AnnotationEntity(
                id = UUID.randomUUID().toString(),
                documentId = docId,
                page = page,
                type = type,
                color = color,
                rectsData = rects.encodeRects(),
                state = AnnState.PENDING_ADD,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeAnnotation(a: AnnotationEntity) {
        if (a.state == AnnState.PENDING_ADD) db.annotationDao().delete(a.id)
        else db.annotationDao().setState(a.id, AnnState.PENDING_DELETE)
    }

    /** Writes pending annotation changes into the PDF file (temp file + verify + atomic replace). */
    suspend fun syncAnnotations(doc: DocumentEntity) {
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val dao = db.annotationDao()
                val pending = dao.pending(doc.id)
                if (pending.isNotEmpty()) {
                    val add = pending.filter { it.state == AnnState.PENDING_ADD }
                    val del = pending.filter { it.state == AnnState.PENDING_DELETE }
                    writer.apply(fileOf(doc), add, del.map { it.id }.toSet())
                    add.forEach { dao.setState(it.id, AnnState.SYNCED) }
                    del.forEach { dao.delete(it.id) }
                }
            }
        }
    }

    /** Saves annotations into the PDF, then copies the file to a location chosen by the user. */
    suspend fun exportCopy(doc: DocumentEntity, dest: Uri) {
        syncAnnotations(doc)
        syncSignatures(doc)
        withContext(Dispatchers.IO) {
            val out = context.contentResolver.openOutputStream(dest, "wt") ?: throw IOException("Cannot write destination")
            out.use { o -> fileOf(doc).inputStream().use { it.copyTo(o) } }
        }
    }

    /** Imports a PDF freshly built by ScanPdfBuilder from camera photos. */
    suspend fun importScannedPdf(images: List<File>, title: String): Long = withContext(Dispatchers.IO) {
        require(images.isNotEmpty()) { "No scan pages" }
        val fileName = UUID.randomUUID().toString() + ".pdf"
        val target = File(dir, fileName)
        try {
            val pages = ScanPdfBuilder.build(images, target)
            val now = System.currentTimeMillis()
            db.documentDao().insert(
                DocumentEntity(
                    title = title.ifBlank { "Scan ${formatDate(now)}" },
                    fileName = fileName,
                    pageCount = pages,
                    lastOpened = now,
                    addedAt = now,
                ),
            )
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** Imports JPEG pages (content URIs) by building a PDF via ScanPdfBuilder. */
    suspend fun importScannedPdfFromUris(uris: List<android.net.Uri>, title: String): Long = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "No scan pages" }
        val tmpDir = File(context.cacheDir, "mlkit-pages").apply { mkdirs() }
        val imgs = mutableListOf<File>()
        try {
            for ((i, uri) in uris.withIndex()) {
                val f = File(tmpDir, "${UUID.randomUUID()}_$i.jpg")
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read scan page")
                input.use { src -> f.outputStream().use { src.copyTo(it) } }
                imgs.add(f)
            }
            val fileName = UUID.randomUUID().toString() + ".pdf"
            val target = File(dir, fileName)
            try {
                val pages = ScanPdfBuilder.build(imgs, target)
                val now = System.currentTimeMillis()
                db.documentDao().insert(
                    DocumentEntity(
                        title = title.ifBlank { "Scan ${formatDate(now)}" },
                        fileName = fileName,
                        pageCount = pages,
                        lastOpened = now,
                        addedAt = now,
                    ),
                )
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        } finally {
            imgs.forEach { it.delete() }
        }
    }

    // ---- signatures ----
    fun observeSignatures() = db.signatureDao().observeAll()
    fun signatureFile(s: SignatureEntity) = File(sigDir, s.fileName)

    suspend fun saveSignature(png: java.io.InputStream, widthPx: Int, heightPx: Int): SignatureEntity =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val fileName = id + ".png"
            val f = File(sigDir, fileName)
            try {
                f.outputStream().use { png.copyTo(it) }
                val e = SignatureEntity(id = id, fileName = fileName, widthPx = widthPx, heightPx = heightPx, createdAt = System.currentTimeMillis())
                db.signatureDao().insert(e)
                e
            } catch (ex: Exception) {
                f.delete()
                throw ex
            }
        }

    suspend fun deleteSignature(s: SignatureEntity) = withContext(Dispatchers.IO) {
        db.signatureDao().delete(s.id)
        signatureFile(s).delete()
    }

    // ---- signature placements ----
    fun observePlacements(docId: Long) = db.signaturePlacementDao().observeVisible(docId)
    fun observePlacementPending(docId: Long) = db.signaturePlacementDao().observePendingCount(docId)

    /** Combined badge: annotation + signature pending changes. */
    fun observeCombinedPending(docId: Long): kotlinx.coroutines.flow.Flow<Int> =
        kotlinx.coroutines.flow.combine(observePendingCount(docId), observePlacementPending(docId)) { a, b -> a + b }

    suspend fun addPlacement(docId: Long, page: Int, signatureId: String) {
        db.signaturePlacementDao().insert(
            SignaturePlacementEntity(
                id = UUID.randomUUID().toString(),
                documentId = docId,
                signatureId = signatureId,
                page = page,
                centerX = 0.5f,
                centerY = 0.5f,
                widthFrac = 0.45f,
                rotationDeg = 0f,
                state = AnnState.PENDING_ADD,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun updatePlacement(id: String, cx: Float, cy: Float, wf: Float, rot: Float) =
        db.signaturePlacementDao().updateTransform(id, cx, cy, wf, rot)

    suspend fun removePlacement(p: SignaturePlacementEntity) {
        if (p.state == AnnState.PENDING_ADD) db.signaturePlacementDao().delete(p.id)
        else db.signaturePlacementDao().setState(p.id, AnnState.PENDING_DELETE)
    }

    /** Writes pending signature placements into the PDF file (temp file + verify + atomic replace). */
    suspend fun syncSignatures(doc: DocumentEntity) {
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
                val dao = db.signaturePlacementDao()
                val sigDao = db.signatureDao()
                val pending = dao.pending(doc.id)
                if (pending.isNotEmpty()) {
                    val files = mutableMapOf<String, File>()
                    for (p in pending) {
                        val sig = sigDao.get(p.signatureId) ?: continue
                        files[p.signatureId] = signatureFile(sig)
                    }
                    val add = pending.filter { it.state == AnnState.PENDING_ADD }
                        .mapNotNull { p -> sigDao.get(p.signatureId)?.let { p to it } }
                    val del = pending.filter { it.state == AnnState.PENDING_DELETE }
                    sigWriter.apply(fileOf(doc), add, del, files)
                    add.forEach { dao.setState(it.first.id, AnnState.SYNCED) }
                    del.forEach { dao.delete(it.id) }
                }
            }
        }
    }

    private fun formatDate(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.getDefault()).format(java.util.Date(millis))

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
}
