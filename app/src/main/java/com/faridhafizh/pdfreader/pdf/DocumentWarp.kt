package com.faridhafizh.pdfreader.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Warps a photo of a paper sheet into an axis-aligned rectangle (perspective correction).
 * Pure homography resampling, no OpenCV/MLKit needed.
 */
object DocumentWarp {

    /**
     * Writes a warped JPEG to [dst] and returns true when a reasonable quad was found;
     * otherwise returns false and does not write [dst].
     */
    fun processFile(src: File, dst: File, maxW: Int = 1600): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0) return false
        // Detect on a small preview, warp the full image.
        val thumb = decodeScaled(src, 640) ?: return false
        val quad = DocumentDetect.detect(thumb) ?: return false
        if (thumb.width <= 0) return false
        val scale = bounds.outWidth.toFloat() / thumb.width
        val fullQuad = quad.points.map { PointF(it.x * scale, it.y * scale) }
        val full = decodeScaled(src, maxOf(bounds.outWidth, maxW)) ?: return false
        // Scale quad into full bitmap coords (decodeScaled may differ slightly).
        val k = full.width.toFloat() / bounds.outWidth
        val quadFull = fullQuad.map { PointF(it.x * k, it.y * k) }
        val warped = warp(full, quadFull, maxW) ?: return false
        FileOutputStream(dst).use { out ->
            if (!warped.compress(Bitmap.CompressFormat.JPEG, 85, out)) throw IOException("JPEG encode failed")
        }
        return true
    }

    private fun decodeScaled(file: File, maxW: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        var w = bounds.outWidth
        while (w / 2 >= maxW.coerceAtLeast(1) && w / 2 >= maxW / 2) {
            sample *= 2
            w /= 2
            if (sample > 16) break
        }
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Warps [src] so [quad] (TL, TR, BR, BL) maps to an axis-aligned rectangle.
     * Returns null when the quad is degenerate.
     */
    fun warp(src: Bitmap, quad: List<PointF>, maxW: Int = 1600): Bitmap? {
        if (quad.size != 4) return null
        val (tl, tr, br, bl) = quad
        val topLen = hypot(tr.x - tl.x, tr.y - tl.y)
        val bottomLen = hypot(br.x - bl.x, br.y - bl.y)
        val leftLen = hypot(bl.x - tl.x, bl.y - tl.y)
        val rightLen = hypot(br.x - tr.x, br.y - tr.y)
        val wLen = max(topLen, bottomLen).roundToInt().coerceAtLeast(8)
        val hLen = max(leftLen, rightLen).roundToInt().coerceAtLeast(8)
        val dstW = wLen.coerceAtMost(maxW)
        val dstH = (hLen * dstW.toFloat() / wLen).roundToInt().coerceAtLeast(8)
        val dst = listOf(PointF(0f, 0f), PointF((dstW - 1).toFloat(), 0f), PointF((dstW - 1).toFloat(), (dstH - 1).toFloat()), PointF(0f, (dstH - 1).toFloat()))
        val h2d = homography(quad, dst) ?: return null // src -> dst
        val inv = invert3(h2d) ?: return null
        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        val srcPixels = IntArray(src.width * src.height)
        src.getPixels(srcPixels, 0, src.width, 0, 0, src.width, src.height)
        val dstPixels = IntArray(dstW * dstH)
        for (dy in 0 until dstH) {
            for (dx in 0 until dstW) {
                val p = apply(inv, dx.toFloat(), dy.toFloat()) ?: continue
                val sx = p.x; val sy = p.y
                if (sx < 0f || sy < 0f || sx >= src.width - 1 || sy >= src.height - 1) {
                    dstPixels[dy * dstW + dx] = 0
                    continue
                }
                val x0 = sx.toInt(); val y0 = sy.toInt()
                val fx = sx - x0; val fy = sy - y0
                val i00 = y0 * src.width + x0
                val c00 = srcPixels[i00]
                val c10 = srcPixels[i00 + 1]
                val c01 = srcPixels[i00 + src.width]
                val c11 = srcPixels[i00 + src.width + 1]
                dstPixels[dy * dstW + dx] = bilinear(c00, c10, c01, c11, fx, fy)
            }
        }
        out.setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
        return out
    }

    private fun bilinear(c00: Int, c10: Int, c01: Int, c11: Int, fx: Float, fy: Float): Int {
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
        val a = (ch(c00, 24) * (1 - fx) * (1 - fy) + ch(c10, 24) * fx * (1 - fy) + ch(c01, 24) * (1 - fx) * fy + ch(c11, 24) * fx * fy).roundToInt()
        val r = (ch(c00, 16) * (1 - fx) * (1 - fy) + ch(c10, 16) * fx * (1 - fy) + ch(c01, 16) * (1 - fx) * fy + ch(c11, 16) * fx * fy).roundToInt()
        val g = (ch(c00, 8) * (1 - fx) * (1 - fy) + ch(c10, 8) * fx * (1 - fy) + ch(c01, 8) * (1 - fx) * fy + ch(c11, 8) * fx * fy).roundToInt()
        val b = (ch(c00, 0) * (1 - fx) * (1 - fy) + ch(c10, 0) * fx * (1 - fy) + ch(c01, 0) * (1 - fx) * fy + ch(c11, 0) * fx * fy).roundToInt()
        return (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun apply(h: FloatArray, x: Float, y: Float): PointF? {
        val denom = h[6] * x + h[7] * y + 1f
        if (abs(denom) < 1e-9f) return null
        return PointF((h[0] * x + h[1] * y + h[2]) / denom, (h[3] * x + h[4] * y + h[5]) / denom)
    }

    /** Solves src->dst homography (h33 = 1) via Gaussian elimination on an 8x8 system. */
    internal fun homography(src: List<PointF>, dst: List<PointF>): FloatArray? {
        val n = 8
        val a = Array(n) { FloatArray(n + 1) }
        for (i in 0 until 4) {
            val s = src[i]; val d = dst[i]
            val row1 = 2 * i
            val row2 = 2 * i + 1
            a[row1][0] = s.x; a[row1][1] = s.y; a[row1][2] = 1f
            a[row1][3] = 0f; a[row1][4] = 0f; a[row1][5] = 0f
            a[row1][6] = -d.x * s.x; a[row1][7] = -d.x * s.y
            a[row1][8] = d.x
            a[row2][0] = 0f; a[row2][1] = 0f; a[row2][2] = 0f
            a[row2][3] = s.x; a[row2][4] = s.y; a[row2][5] = 1f
            a[row2][6] = -d.y * s.x; a[row2][7] = -d.y * s.y
            a[row2][8] = d.y
        }
        return solve(a)
    }

    private fun solve(a: Array<FloatArray>): FloatArray? {
        val n = a.size
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-9f) return null
            if (pivot != col) { val t = a[col]; a[col] = a[pivot]; a[pivot] = t }
            for (r in col + 1 until n) {
                val factor = a[r][col] / a[col][col]
                if (factor == 0f) continue
                for (c in col..n) a[r][c] -= factor * a[col][c]
            }
        }
        val x = FloatArray(n)
        for (r in n - 1 downTo 0) {
            var sum = a[r][n]
            for (c in r + 1 until n) sum -= a[r][c] * x[c]
            x[r] = sum / a[r][r]
        }
        return floatArrayOf(x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], 1f)
    }

    private fun invert3(h: FloatArray): FloatArray? {
        val a = h[0]; val b = h[1]; val c = h[2]
        val d = h[3]; val e = h[4]; val f = h[5]
        val g = h[6]; val hh = h[7]; val i = h[8]
        val A = e * i - f * hh
        val B = -(d * i - f * g)
        val C = d * hh - e * g
        val det = a * A + b * B + c * C
        if (abs(det) < 1e-9f) return null
        val A2 = e * i - f * hh
        val B2 = -(d * i - f * g)
        val C2 = d * hh - e * g
        val D2 = -(b * i - c * hh)
        val E2 = a * i - c * g
        val F2 = -(a * hh - b * g)
        val G2 = b * f - c * e
        val H2 = -(a * f - c * d)
        val I2 = a * e - b * d
        return floatArrayOf(A2 / det, D2 / det, G2 / det, B2 / det, E2 / det, H2 / det, C2 / det, F2 / det, I2 / det)
    }
}
