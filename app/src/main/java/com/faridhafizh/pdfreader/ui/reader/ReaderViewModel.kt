package com.faridhafizh.pdfreader.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faridhafizh.pdfreader.AppContainer
import com.faridhafizh.pdfreader.data.AnnType
import com.faridhafizh.pdfreader.data.AnnotationEntity
import com.faridhafizh.pdfreader.data.DocumentEntity
import com.faridhafizh.pdfreader.data.NRect
import com.faridhafizh.pdfreader.data.SignatureEntity
import com.faridhafizh.pdfreader.data.SignaturePlacementEntity
import com.faridhafizh.pdfreader.pdf.PdfSession
import com.faridhafizh.pdfreader.pdf.PageText
import com.faridhafizh.pdfreader.pdf.SearchHit
import com.faridhafizh.pdfreader.pdf.TextIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val pageCount: Int = 0,
    val initialPage: Int = 0,
    val searchActive: Boolean = false,
    val query: String = "",
    val searching: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val hitIndex: Int = -1,
    val saving: Boolean = false,
)

/** Text selection on one page. anchor/head are indices into the page's TextChar list. */
data class Selection(val page: Int, val anchor: Int, val head: Int, val rects: List<NRect>, val text: String)

sealed interface ReaderEvent {
    data class ScrollTo(val page: Int) : ReaderEvent
    data class Message(val text: String) : ReaderEvent
}

class ReaderViewModel(private val c: AppContainer, private val docId: Long) : ViewModel() {
    private val repo = c.repository

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui = _ui.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection = _selection.asStateFlow()

    private val _events = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    val bookmarks = repo.observeBookmarks(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val annotations = repo.observeAnnotations(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val placements = repo.observePlacements(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val signatures = repo.observeSignatures()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pendingCount = repo.observeCombinedPending(docId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    @Volatile
    var session: PdfSession? = null
        private set
    private var textIndex: TextIndex? = null
    private var doc: DocumentEntity? = null
    private var searchJob: Job? = null

    private val bitmaps = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        viewModelScope.launch {
            try {
                val d = repo.getDocument(docId) ?: throw IllegalStateException("Document not found")
                doc = d
                val file = repo.fileOf(d)
                val s = withContext(Dispatchers.Default) { PdfSession.open(c.pdfiumCore, file) }
                if (s.pageCount == 0) {
                    s.close()
                    throw IllegalStateException("PDF has no pages")
                }
                session = s
                textIndex = TextIndex(file)
                _ui.update {
                    it.copy(
                        loading = false,
                        title = d.title,
                        pageCount = s.pageCount,
                        initialPage = d.lastPage.coerceIn(0, s.pageCount - 1),
                    )
                }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = "Cannot open this PDF (corrupt or password-protected?)\n${e.message ?: ""}") }
            }
        }
    }

    // ---- pages ----
    suspend fun pageBitmap(page: Int, widthPx: Int): Bitmap {
        val key = "$page@$widthPx"
        bitmaps.get(key)?.let { return it }
        val s = session ?: throw IllegalStateException("closed")
        val b = s.render(page, widthPx)
        bitmaps.put(key, b)
        return b
    }

    fun onPageChanged(page: Int) {
        viewModelScope.launch { repo.updateProgress(docId, page) }
    }

    fun goTo(page: Int) {
        val last = (session?.pageCount ?: 1) - 1
        _events.tryEmit(ReaderEvent.ScrollTo(page.coerceIn(0, last)))
    }

    // ---- bookmarks ----
    fun toggleBookmark(page: Int) {
        viewModelScope.launch { repo.toggleBookmark(docId, page) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repo.deleteBookmark(id) }
    }

    // ---- search ----
    fun openSearch() = _ui.update { it.copy(searchActive = true) }

    fun closeSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(searchActive = false, query = "", hits = emptyList(), hitIndex = -1, searching = false) }
    }

    fun startSearch(q: String) {
        searchJob?.cancel()
        val ti = textIndex ?: return
        val s = session ?: return
        if (q.isBlank()) return
        _ui.update { it.copy(query = q, hits = emptyList(), hitIndex = -1, searching = true) }
        searchJob = viewModelScope.launch {
            try {
                ti.search(q, s.pageCount).take(500).collect { hit ->
                    var first = false
                    _ui.update {
                        first = it.hits.isEmpty()
                        it.copy(hits = it.hits + hit, hitIndex = if (first) 0 else it.hitIndex)
                    }
                    if (first) _events.tryEmit(ReaderEvent.ScrollTo(hit.page))
                }
                if (_ui.value.hits.isEmpty()) _events.tryEmit(ReaderEvent.Message("No results for “$q”"))
            } finally {
                _ui.update { it.copy(searching = false) }
            }
        }
    }

    fun nextHit() = stepHit(+1)
    fun prevHit() = stepHit(-1)

    private fun stepHit(delta: Int) {
        val st = _ui.value
        if (st.hits.isEmpty()) return
        val i = (st.hitIndex + delta + st.hits.size) % st.hits.size
        _ui.update { it.copy(hitIndex = i) }
        _events.tryEmit(ReaderEvent.ScrollTo(st.hits[i].page))
    }

    // ---- selection ----
    fun startSelection(page: Int, fx: Float, fy: Float) {
        val ti = textIndex ?: return
        viewModelScope.launch {
            val pt = ti.pageText(page)
            val i = pt.nearestChar(fx, fy)
            if (i >= 0) setSelection(pt, page, i, i)
        }
    }

    fun extendSelection(page: Int, fx: Float, fy: Float) {
        val s = _selection.value ?: return
        if (s.page != page) return
        val pt = textIndex?.peek(page) ?: return
        val i = pt.nearestChar(fx, fy)
        if (i >= 0) setSelection(pt, page, s.anchor, i)
    }

    private fun setSelection(pt: PageText, page: Int, anchor: Int, head: Int) {
        val lo = min(anchor, head)
        val hi = max(anchor, head)
        val text = pt.chars.subList(lo, hi + 1).joinToString("") { it.s }.trim()
        _selection.value = Selection(page, anchor, head, pt.rectsFor(lo..hi), text)
    }

    fun clearSelection() {
        _selection.value = null
    }

    fun annotateSelection(type: String, color: Int) {
        val s = _selection.value ?: return
        _selection.value = null
        viewModelScope.launch { repo.addAnnotation(docId, s.page, type, color, s.rects) }
    }

    fun deleteAnnotation(a: AnnotationEntity) {
        viewModelScope.launch { repo.removeAnnotation(a) }
    }

    // ---- signatures ----
    suspend fun loadSignatureBitmap(s: SignatureEntity): Bitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(repo.signatureFile(s).absolutePath)
    }

    fun addSignatureToPage(page: Int, signatureId: String) {
        viewModelScope.launch { repo.addPlacement(docId, page, signatureId) }
    }

    fun addSignatureToAllPages(signatureId: String) {
        viewModelScope.launch {
            val n = session?.pageCount ?: return@launch
            for (p in 0 until n) repo.addPlacement(docId, p, signatureId)
        }
    }

    fun addSignatureToPageRange(page: Int, signatureId: String) {
        viewModelScope.launch {
            val n = session?.pageCount ?: return@launch
            val last = minOf(n - 1, page + 5)
            for (p in page..last) repo.addPlacement(docId, p, signatureId)
        }
    }

    fun updatePlacement(id: String, cx: Float, cy: Float, wf: Float, rot: Float) {
        viewModelScope.launch { repo.updatePlacement(id, cx, cy, wf, rot) }
    }

    fun deletePlacement(p: SignaturePlacementEntity) {
        viewModelScope.launch { repo.removePlacement(p) }
    }

    // ---- save / export ----
    fun save() {
        val d = doc ?: return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true) }
            try {
                repo.syncAnnotations(d)
                repo.syncSignatures(d)
                _events.tryEmit(ReaderEvent.Message("Annotations & signatures saved into the PDF"))
            } catch (e: Exception) {
                _events.tryEmit(ReaderEvent.Message("Could not save: ${e.message ?: "unknown error"}"))
            } finally {
                _ui.update { it.copy(saving = false) }
            }
        }
    }

    fun export(dest: Uri) {
        val d = doc ?: return
        viewModelScope.launch {
            try {
                repo.exportCopy(d, dest)
                _events.tryEmit(ReaderEvent.Message("Copy exported"))
            } catch (e: Exception) {
                _events.tryEmit(ReaderEvent.Message("Export failed: ${e.message ?: "unknown error"}"))
            }
        }
    }

    override fun onCleared() {
        session?.close()
        session = null
        textIndex?.close()
        // Write pending annotations/signatures into the PDF even though this screen is gone.
        doc?.let { d -> c.appScope.launch { runCatching { repo.syncAnnotations(d); repo.syncSignatures(d) } } }
    }
}

val HIGHLIGHT_COLORS = listOf(0xFFFFEB3B.toInt(), 0xFF8BC34A.toInt(), 0xFF64B5F6.toInt(), 0xFFF48FB1.toInt())
val UNDERLINE_COLOR = 0xFFE53935.toInt()
val ANN_UNDERLINE = AnnType.UNDERLINE
val ANN_HIGHLIGHT = AnnType.HIGHLIGHT
