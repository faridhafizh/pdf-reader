package com.faridhafizh.pdfreader.pdf

import android.graphics.Bitmap
import android.util.LruCache
import com.shockwave.pdfium.PdfiumCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ThumbnailLoader(private val core: PdfiumCore) {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(file: File, key: String): Bitmap? {
        cache.get(key)?.let { return it }
        val bmp = withContext(Dispatchers.Default) {
            try {
                PdfSession.open(core, file, allSizes = false).use { it.render(0, 240) }
            } catch (e: Exception) {
                null
            }
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }
}
