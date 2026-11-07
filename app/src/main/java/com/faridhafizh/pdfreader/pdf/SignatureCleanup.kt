package com.faridhafizh.pdfreader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a photo/drawing of a signature into a fully transparent PNG (white paper -> alpha 0).
 *
 * Pixels brighter than [whiteThreshold] become fully transparent. Darker pixels keep their
 * color with alpha mapped from (threshold - luminance) so dark strokes stay at alpha 255 and
 * thin pencil strokes stay partly visible while paper texture disappears completely.
 * [boost] (0..3) multiplies that mapping to strengthen faint "pencil" strokes.
 */
object SignatureCleanup {

    fun clean(source: Bitmap, whiteThreshold: Int = 235, boost: Float = 2.2f): Bitmap {
        val w = source.width
        val h = source.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val thr = whiteThreshold.coerceIn(200, 250)
        val b = boost.coerceIn(1f, 3f)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val bl = Color.blue(p)
            val lum = (0.299f * r + 0.587f * g + 0.114f * bl)
            val a = if (lum >= thr) 0 else (((thr - lum) / (thr.toFloat() + 1f)) * 255f * b).toInt().coerceIn(0, 255)
            if (a == 0) {
                pixels[i] = 0
            } else {
                // Force ink color to black for cleanest look while keeping alpha gradient.
                pixels[i] = Color.argb(a, 0, 0, 0)
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Shrinks the bitmap to the bounding box of non-transparent pixels (with [pad] px margin). */
    fun trimTransparent(source: Bitmap, pad: Int = 8): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (Color.alpha(pixels[y * w + x]) > 8) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val x0 = max(0, minX - pad)
        val y0 = max(0, minY - pad)
        val x1 = min(w - 1, maxX + pad)
        val y1 = min(h - 1, maxY + pad)
        if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) return source
        return Bitmap.createBitmap(source, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /** Downscales keeping aspect so width <= [maxW]; returns same instance if already smaller. */
    fun scaleDown(source: Bitmap, maxW: Int = 1200): Bitmap {
        if (source.width <= maxW) return source
        val ratio = maxW.toFloat() / source.width
        val h = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxW, h, true)
    }
}
