package com.faridhafizh.pdfreader.pdf

import com.faridhafizh.pdfreader.data.NRect
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One character with its box as fractions (0..1) of the displayed page, origin top-left. */
class TextChar(val s: String, val l: Float, val t: Float, val r: Float, val b: Float, val synthetic: Boolean = false)

class PageText(val chars: List<TextChar>) {
    val text: String
    private val charIndexAt: IntArray

    init {
        val sb = StringBuilder()
        val idx = ArrayList<Int>()
        chars.forEachIndexed { i, c -> for (ch in c.s) { sb.append(ch); idx.add(i) } }
        text = sb.toString()
        charIndexAt = idx.toIntArray()
    }

    /** Maps a [start, endExclusive) range of [text] to a range of indices in [chars]. */
    fun charRange(start: Int, endExclusive: Int): IntRange = charIndexAt[start]..charIndexAt[endExclusive - 1]

    fun nearestChar(fx: Float, fy: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in chars.indices) {
            val c = chars[i]
            if (c.synthetic) continue
            val dx = if (fx < c.l) c.l - fx else if (fx > c.r) fx - c.r else 0f
            val dy = if (fy < c.t) c.t - fy else if (fy > c.b) fy - c.b else 0f
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Merges characters of a range into one rectangle per text line. */
    fun rectsFor(range: IntRange): List<NRect> {
        val out = ArrayList<NRect>()
        var cur: NRect? = null
        for (i in range) {
            val c = chars[i]
            if (c.synthetic) continue
            val c0 = cur
            if (c0 == null) {
                cur = NRect(c.l, c.t, c.r, c.b)
            } else {
                val sameLine = abs((c.t + c.b) / 2 - (c0.t + c0.b) / 2) < (c0.b - c0.t) * 0.6f
                if (sameLine) {
                    cur = NRect(min(c0.l, c.l), min(c0.t, c.t), max(c0.r, c.r), max(c0.b, c.b))
                } else {
                    out += c0
                    cur = NRect(c.l, c.t, c.r, c.b)
                }
            }
        }
        cur?.let { out += it }
        return out
    }
}

data class SearchHit(val page: Int, val rects: List<NRect>, val snippet: String)

private class CollectingStripper : PDFTextStripper() {
    val out = ArrayList<TextChar>()

    init {
        sortByPosition = true
    }

    override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
        if (textPositions == null) return
        for (tp in textPositions) {
            val u = tp.unicode ?: continue
            val pw = tp.pageWidth
            val ph = tp.pageHeight
            if (pw <= 0f || ph <= 0f) continue
            val l = tp.xDirAdj / pw
            val r = (tp.xDirAdj + tp.widthDirAdj) / pw
            val b = tp.yDirAdj / ph
            val t = (tp.yDirAdj - tp.heightDir) / ph
            out += TextChar(u, l, t, r, b)
        }
    }

    override fun writeWordSeparator() {
        out += TextChar(" ", 0f, 0f, 0f, 0f, synthetic = true)
    }

    override fun writeLineSeparator() {
        out += TextChar("\n", 0f, 0f, 0f, 0f, synthetic = true)
    }
}

/**
 * Text positions via PDFBox (PDFium wrappers on Android do not expose text search/selection).
 * Pages are extracted lazily and cached. NOTE: PDFBox loads the whole file; very large PDFs use a lot of memory.
 */
class TextIndex(private val file: File) : AutoCloseable {
    private val mutex = Mutex()
    private var doc: PDDocument? = null
    private val cache = ConcurrentHashMap<Int, PageText>()

    fun peek(page: Int): PageText? = cache[page]

    suspend fun pageText(page: Int): PageText {
        cache[page]?.let { return it }
        return mutex.withLock {
            cache[page] ?: withContext(Dispatchers.Default) {
                val d = doc ?: PDDocument.load(file).also { doc = it }
                val s = CollectingStripper()
                s.startPage = page + 1
                s.endPage = page + 1
                s.getText(d)
                PageText(s.out)
            }.also { cache[page] = it }
        }
    }

    fun search(query: String, pageCount: Int): Flow<SearchHit> = flow {
        for (p in 0 until pageCount) {
            val pt = pageText(p)
            var from = 0
            while (true) {
                val i = pt.text.indexOf(query, from, ignoreCase = true)
                if (i < 0) break
                val end = i + query.length
                emit(SearchHit(p, pt.rectsFor(pt.charRange(i, end)), snippet(pt.text, i, end)))
                from = end
            }
        }
    }

    private fun snippet(t: String, s: Int, e: Int): String {
        val a = (s - 25).coerceAtLeast(0)
        val b = (e + 25).coerceAtMost(t.length)
        return t.substring(a, b).replace('\n', ' ').trim()
    }

    override fun close() {
        runCatching { doc?.close() }
        doc = null
    }
}
