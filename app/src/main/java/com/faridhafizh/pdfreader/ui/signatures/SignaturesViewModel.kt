package com.faridhafizh.pdfreader.ui.signatures

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.faridhafizh.pdfreader.AppContainer
import com.faridhafizh.pdfreader.data.SignatureEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

class SignaturesViewModel(private val c: AppContainer) : ViewModel() {
    val signatures = c.repository.observeSignatures()

    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun clearMessage() { _message.value = null }

    fun savePng(png: ByteArray, w: Int, h: Int, onDone: () -> Unit) {
        viewModelScope.launch {
            _saving.value = true
            try {
                c.repository.saveSignature(ByteArrayInputStream(png), w, h)
                onDone()
            } catch (e: Exception) {
                _message.value = "Save failed: ${e.message ?: "unknown error"}"
            } finally {
                _saving.value = false
            }
        }
    }

    fun delete(s: SignatureEntity) {
        viewModelScope.launch { c.repository.deleteSignature(s) }
    }

    suspend fun signatureFile(s: SignatureEntity): File = c.repository.signatureFile(s)

    suspend fun loadBitmap(s: SignatureEntity): Bitmap? = withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(c.repository.signatureFile(s).absolutePath)
    }
}
