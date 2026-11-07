package com.faridhafizh.pdfreader.ui.scan

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faridhafizh.pdfreader.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ScanUiState(
    val pages: List<File> = emptyList(),
    val saving: Boolean = false,
    val message: String? = null,
)

class ScanViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(ScanUiState())
    val ui = _ui.asStateFlow()

    fun addPage(file: File) {
        _ui.update { it.copy(pages = it.pages + file) }
    }

    fun removePage(index: Int) {
        _ui.update { state ->
            val f = state.pages.getOrNull(index)
            f?.delete()
            state.copy(pages = state.pages.filterIndexed { i, _ -> i != index })
        }
    }

    /** Warps a raw capture into a rectangle (fallback: raw) and adds it as the next page. */
    fun processAndAdd(raw: File) {
        viewModelScope.launch {
            val warped = File(raw.parentFile, raw.nameWithoutExtension + "_w.jpg")
            val ok = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.faridhafizh.pdfreader.pdf.DocumentWarp.processFile(raw, warped)
                }
            }.getOrDefault(false)
            val finalFile = if (ok && warped.exists()) warped else raw
            raw.delete()
            if (ok && finalFile == raw) warped.delete()
            _ui.update { it.copy(pages = it.pages + finalFile) }
        }
    }

    /** Warps a raw capture and hands the ready file to the editor (does not add it to pages yet). */
    fun processToEditor(raw: File, onReady: (File) -> Unit) {
        viewModelScope.launch {
            val warped = File(raw.parentFile, raw.nameWithoutExtension + "_w.jpg")
            val ok = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    com.faridhafizh.pdfreader.pdf.DocumentWarp.processFile(raw, warped)
                }
            }.getOrDefault(false)
            val finalFile = if (ok && warped.exists()) warped else raw
            raw.delete()
            if (ok && finalFile == raw) warped.delete()
            onReady(finalFile)
        }
    }

    fun movePage(from: Int, to: Int) {
        _ui.update { state ->
            if (from !in state.pages.indices || to !in state.pages.indices) return@update state
            val list = state.pages.toMutableList()
            list.add(to, list.removeAt(from))
            state.copy(pages = list)
        }
    }

    fun save(onDone: (Long) -> Unit) {
        val pages = _ui.value.pages
        if (pages.isEmpty() || _ui.value.saving) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, message = null) }
            try {
                val id = c.repository.importScannedPdf(pages, "Scan ${System.currentTimeMillis()}")
                pages.forEach { it.delete() }
                onDone(id)
            } catch (e: Exception) {
                _ui.update { it.copy(message = "Save failed: ${e.message ?: "unknown error"}") }
            } finally {
                _ui.update { it.copy(saving = false) }
            }
        }
    }

    /** Imports a PDF returned by ML Kit Document Scanner. */
    fun importScannedUri(pdfUri: Uri, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, message = null) }
            try {
                onDone(c.repository.importPdf(pdfUri))
            } catch (e: Exception) {
                _ui.update { it.copy(message = "Import failed: ${e.message ?: "unknown error"}") }
            } finally {
                _ui.update { it.copy(saving = false) }
            }
        }
    }

    /** Builds a PDF from JPEG pages returned by ML Kit (when PDF is unavailable). */
    fun importScannedUris(uris: List<Uri>, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, message = null) }
            try {
                val id = c.repository.importScannedPdfFromUris(uris, "Scan ${System.currentTimeMillis()}")
                onDone(id)
            } catch (e: Exception) {
                _ui.update { it.copy(message = "Import failed: ${e.message ?: "unknown error"}") }
            } finally {
                _ui.update { it.copy(saving = false) }
            }
        }
    }

    companion object {
        fun scanDir(context: Context): File =
            File(context.cacheDir, "scans").apply { mkdirs() }
    }
}
