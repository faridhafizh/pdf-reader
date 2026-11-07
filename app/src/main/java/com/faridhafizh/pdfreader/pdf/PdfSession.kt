package com.faridhafizh.pdfreader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** One opened PDF rendered by PDFium. All native calls are serialized with a mutex. */
class PdfSession private constructor(
    private val core: PdfiumCore,
    private val doc: PdfDocument,
    private val pfd: ParcelFileDescriptor,
    val pageCount: Int,
    private val sizes: List<Pair<Float, Float>>,
) : AutoCloseable {

    private val mutex = Mutex()
    private var closed = false

    /** width / height of the page as displayed (rotation applied). */
    fun aspect(page: Int): Float = sizes[page].let { it.first / it.second }

    suspend fun render(page: Int, widthPx: Int): Bitmap = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(!closed) { "Session closed" }
            val (w, h) = sizes[page]
            val heightPx = (widthPx * h / w).roundToInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
            bmp.eraseColor(Color.WHITE)
            core.openPage(doc, page)
            // Annotations are drawn by the app overlay, so PDFium renders the page content only.
            core.renderPageBitmap(doc, bmp, page, 0, 0, widthPx, heightPx)
            bmp
        }
    }

    override fun close() {
        runBlocking {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    runCatching { core.closeDocument(doc) }
                    runCatching { pfd.close() }
                }
            }
        }
    }

    companion object {
        /** Blocking. Call from a background dispatcher. */
        fun open(core: PdfiumCore, file: File, allSizes: Boolean = true): PdfSession {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val doc = core.newDocument(pfd)
                val count = core.getPageCount(doc)
                fun sizeOf(i: Int): Pair<Float, Float> {
                    val s = core.getPageSize(doc, i)
                    return s.width.toFloat() to s.height.toFloat()
                }
                val sizes = when {
                    count == 0 -> emptyList()
                    allSizes -> List(count) { sizeOf(it) }
                    else -> listOf(sizeOf(0))
                }
                return PdfSession(core, doc, pfd, count, sizes)
            } catch (e: Exception) {
                runCatching { pfd.close() }
                throw e
            }
        }
    }
}
