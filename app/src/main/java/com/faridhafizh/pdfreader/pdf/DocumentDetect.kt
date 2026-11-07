package com.faridhafizh.pdfreader.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.ArrayDeque

/**
 * Lightweight paper detection without external vision deps (offline, no API key).
 *
 * Pipeline (on a 480-640 px wide preview bitmap):
 *  1. Grayscale + 2-pass box blur (kills paper texture / text noise)
 *  2. Adaptive threshold (local mean - C) with an integral image
 *  3. Morph close+open with a 3x3 kernel to fill text holes
 *  4. Largest bright component (flood fill) - ignores desk clutter
 *  5. Quad extremes (TL=min x+y, TR=max x-y, BR=max x+y, BL=min x-y)
 *  6. Sub-pixel refinement: snap each corner to the strongest edge
 *     (Sobel gradient) inside a +/- 10px window with a distance penalty
 *  7. Sanity checks (coverage, edge lengths, convexity)
 * Temporal smoothing is available via [BoxSmoother] in the caller.
 *
 * Pure JVM-friendly on [Bitmap] inputs so it can be unit tested.
 */
object DocumentDetect {

    data class Quad(val points: List<PointF>) // TL, TR, BR, BL in bitmap coords

    /** Exponential moving average across frames (alpha 0.4) to kill jitter. */
    class BoxSmoother(val alpha: Float = 0.4f) {
        private var prev: Quad? = null

        fun update(next: Quad?): Quad? {
            if (next == null) return prev
            val p = prev
            if (p == null) {
                prev = next
                return next
            }
            val out = next.points.mapIndexed { i, pt ->
                val o = p.points[i]
                PointF(o.x + alpha * (pt.x - o.x), o.y + alpha * (pt.y - o.y))
            }
            val q = Quad(out)
            prev = q
            return q
        }

        /** Max corner drift (in bitmap px) between two quads. */
        fun drift(a: Quad?, b: Quad?): Float {
            if (a == null || b == null) return Float.MAX_VALUE
            return a.points.zip(b.points).maxOf { (p, q) ->
                abs(p.x - q.x) + abs(p.y - q.y)
            }
        }

        fun reset() { prev = null }
    }

    fun detect(source: Bitmap): Quad? {
        if (source.width < 64 || source.height < 64) return null
        val small = scaleToWidth(source, 480)
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = ((0.299f * r + 0.587f * g + 0.114f * b).roundToInt()).coerceIn(0, 255)
        }
        val blurred = boxBlur(lum, w, h, radius = 2)
        var mask = adaptiveThreshold(blurred, w, h, block = 31, c = 10)
        mask = morphClose(mask, w, h, kernel = 3)
        mask = morphOpen(mask, w, h, kernel = 3)
        val component = largestComponent(mask, w, h) ?: return null
        val coverage = component.count { it }.toFloat() / (w * h)
        if (coverage < 0.15f || coverage > 0.92f) return null

        // Quad extremes of the largest component.
        var tlIdx = 0; var trIdx = 0; var brIdx = 0; var blIdx = 0
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE
        var maxDiff = Int.MIN_VALUE; var minDiff = Int.MAX_VALUE
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!component[y * w + x]) continue
                val sum = x + y
                val diff = x - y
                if (sum < minSum) { minSum = sum; tlIdx = y * w + x }
                if (sum > maxSum) { maxSum = sum; brIdx = y * w + x }
                if (diff > maxDiff) { maxDiff = diff; trIdx = y * w + x }
                if (diff < minDiff) { minDiff = diff; blIdx = y * w + x }
            }
        }
        fun pt(idx: Int) = PointF((idx % w).toFloat(), (idx / w).toFloat())
        val raw = listOf(pt(tlIdx), pt(trIdx), pt(brIdx), pt(blIdx))
        if (polygonArea(raw) / (w * h) < 0.12f) return null

        // Sub-pixel refinement against Sobel edges (on the blurred grayscale).
        val grad = sobelMagnitude(blurred, w, h)
        val refined = raw.map { refineCorner(grad, w, h, it) }
        val quad = Quad(refined)
        if (polygonArea(quad.points) / (w * h) < 0.12f) return null
        val minEdge = min(w, h) * 0.18f
        for (i in quad.points.indices) {
            val a = quad.points[i]
            val b = quad.points[(i + 1) % 4]
            val len = abs(a.x - b.x) + abs(a.y - b.y)
            if (len < minEdge) return null
        }
        if (!isConvex(quad.points)) return null
        val scale = source.width.toFloat() / w
        val scaled = quad.points.map { PointF(it.x * scale, it.y * scale) }
        return Quad(scaled)
    }

    /** Fraction of the frame covered by the quad (approx via polygon area). */
    fun coverage(quad: Quad?, w: Int, h: Int): Float {
        if (quad == null) return 0f
        return (polygonArea(quad.points) / (w * h).toFloat()).coerceIn(0f, 1f)
    }

    private fun refineCorner(grad: IntArray, w: Int, h: Int, c: PointF): PointF {
        val cx = c.x.roundToInt()
        val cy = c.y.roundToInt()
        val r = 10
        var bestX = cx; var bestY = cy; var bestScore = -1f
        for (dy in -r..r) {
            for (dx in -r..r) {
                val x = (cx + dx).coerceIn(0, w - 1)
                val y = (cy + dy).coerceIn(0, h - 1)
                val g = grad[y * w + x].toFloat()
                // Distance penalty keeps the snap local (avoid jumping to text edges).
                val dist = abs(dx) + abs(dy)
                val score = g - dist * 2f
                if (score > bestScore) { bestScore = score; bestX = x; bestY = y }
            }
        }
        return PointF(bestX.toFloat(), bestY.toFloat())
    }

    private fun sobelMagnitude(img: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val gx = -img[(y - 1) * w + x - 1] + img[(y - 1) * w + x + 1]
                    - 2 * img[y * w + x - 1] + 2 * img[y * w + x + 1]
                    - img[(y + 1) * w + x - 1] + img[(y + 1) * w + x + 1]
                val gy = -img[(y - 1) * w + x - 1] - 2 * img[(y - 1) * w + x] - img[(y - 1) * w + x + 1]
                    + img[(y + 1) * w + x - 1] + 2 * img[(y + 1) * w + x] + img[(y + 1) * w + x + 1]
                out[y * w + x] = (abs(gx) + abs(gy)) / 4
            }
        }
        return out
    }

    private fun adaptiveThreshold(lum: IntArray, w: Int, h: Int, block: Int = 31, c: Int = 10): BooleanArray {
        // Integral image for local means.
        val integral = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                rowSum += lum[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + rowSum
            }
        }
        val half = block / 2
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - half); val y1 = min(h - 1, y + half)
            for (x in 0 until w) {
                val x0 = max(0, x - half); val x1 = min(w - 1, x + half)
                val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                val sum = integral[(y1 + 1) * (w + 1) + x1 + 1] - integral[y0 * (w + 1) + x1 + 1] -
                    integral[(y1 + 1) * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = sum / count
                out[y * w + x] = lum[y * w + x] > mean - c
            }
        }
        return out
    }

    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        // Horizontal pass.
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0; var count = 0
                for (dx in -radius..radius) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    sum += src[y * w + xx]; count++
                }
                tmp[y * w + x] = sum / count
            }
        }
        // Vertical pass.
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0; var count = 0
                for (dy in -radius..radius) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    sum += tmp[yy * w + x]; count++
                }
                out[y * w + x] = sum / count
            }
        }
        return out
    }

    private fun morphClose(mask: BooleanArray, w: Int, h: Int, kernel: Int): BooleanArray =
        morphOpen(mask, w, h, kernel).let { morphCloseInner(it, w, h, kernel) }

    private fun morphCloseInner(mask: BooleanArray, w: Int, h: Int, kernel: Int): BooleanArray {
        // Dilate then erode (close fills holes/noise inside paper).
        val dilated = dilate(mask, w, h, kernel)
        return erode(dilated, w, h, kernel)
    }

    private fun morphOpen(mask: BooleanArray, w: Int, h: Int, kernel: Int): BooleanArray {
        val eroded = erode(mask, w, h, kernel)
        return dilate(eroded, w, h, kernel)
    }

    private fun dilate(mask: BooleanArray, w: Int, h: Int, kernel: Int): BooleanArray {
        val half = kernel / 2
        val out = BooleanArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var found = false
            for (dy in -half..half) for (dx in -half..half) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h && mask[yy * w + xx]) { found = true; break }
            }
            out[y * w + x] = found
        }
        return out
    }

    private fun erode(mask: BooleanArray, w: Int, h: Int, kernel: Int): BooleanArray {
        val half = kernel / 2
        val out = BooleanArray(mask.size)
        for (y in 0 until h) for (x in 0 until w) {
            var all = true
            for (dy in -half..half) for (dx in -half..half) {
                val xx = x + dx; val yy = y + dy
                if (xx !in 0 until w || yy !in 0 until h || !mask[yy * w + xx]) { all = false; break }
            }
            out[y * w + x] = all
        }
        return out
    }

    private fun largestComponent(mask: BooleanArray, w: Int, h: Int): BooleanArray? {
        val visited = BooleanArray(mask.size)
        var best: Pair<Int, List<Int>>? = null // size, indices
        val dirs = intArrayOf(-1, 0, 1, 0, -1, w, 1, w)
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            val q = ArrayDeque<Int>()
            q.add(start)
            visited[start] = true
            val comps = mutableListOf<Int>()
            while (q.isNotEmpty()) {
                val idx = q.removeFirst()
                comps.add(idx)
                val x = idx % w; val y = idx / w
                for (d in 0 until 4) {
                    val nx = x + dirs[d * 2]; val ny = y + dirs[d * 2 + 1]
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val nIdx = ny * w + nx
                    if (mask[nIdx] && !visited[nIdx]) {
                        visited[nIdx] = true
                        q.add(nIdx)
                    }
                }
            }
            if (best == null || comps.size > best!!.first) best = comps.size to comps
        }
        val b = best ?: return null
        if (b.first < 120) return null
        val out = BooleanArray(mask.size)
        for (idx in b.second) out[idx] = true
        return out
    }

    private fun polygonArea(pts: List<PointF>): Float {
        var s = 0f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    private fun isConvex(pts: List<PointF>): Boolean {
        if (pts.size != 4) return false
        var sign = 0f
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % 4]; val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross == 0f) return false
            val s = if (cross > 0f) 1f else -1f
            if (sign == 0f) sign = s else if (sign != s) return false
        }
        return true
    }

    private fun scaleToWidth(src: Bitmap, targetW: Int): Bitmap {
        if (src.width <= targetW) return src
        val ratio = targetW.toFloat() / src.width
        val h = (src.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, h, true)
    }
}
