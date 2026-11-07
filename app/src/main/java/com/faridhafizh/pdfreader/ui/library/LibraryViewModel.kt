package com.faridhafizh.pdfreader.ui.library

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faridhafizh.pdfreader.AppContainer
import com.faridhafizh.pdfreader.data.DocumentEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val documents = c.repository.documents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing = _importing.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    fun import(uri: Uri, onDone: (Long) -> Unit) {
        viewModelScope.launch {
            _importing.value = true
            try {
                onDone(c.repository.importPdf(uri))
            } catch (e: Exception) {
                _messages.tryEmit("Could not open PDF: ${e.message ?: "unknown error"}")
            } finally {
                _importing.value = false
            }
        }
    }

    fun delete(d: DocumentEntity) {
        viewModelScope.launch { c.repository.deleteDocument(d) }
    }

    suspend fun thumbnail(d: DocumentEntity): Bitmap? =
        c.thumbnails.load(c.repository.fileOf(d), d.id.toString())
}
