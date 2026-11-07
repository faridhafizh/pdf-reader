package com.faridhafizh.pdfreader.ui.scan

import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * Scan via ML Kit Document Scanner (Google Play Services, on-device).
 * - No camera permission needed (Play Services owns camera).
 * - Auto capture + edge detection + rotate handled by Google.
 * - Returns PDF (+ optional JPEG pages) for import into the library.
 * Requires Play Services + 1.7GB RAM; first launch downloads models (then offline).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    vm: ScanViewModel,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity
    val snackbar = remember { SnackbarHostState() }
    var scanning by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ui.message) {
        if (ui.message != null) snackbar.showSnackbar(ui.message!!)
    }
    LaunchedEffect(errorText) {
        if (errorText != null) {
            snackbar.showSnackbar(errorText!!)
            errorText = null
        }
    }

    val options = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(5)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember { GmsDocumentScanning.getClient(options) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scanning = false
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val parsed = try {
                GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            } catch (e: Exception) {
                errorText = "Could not read scan result: ${e.message}"
                return@rememberLauncherForActivityResult
            }
            val pdfUri = try { parsed?.pdf?.uri } catch (_: Exception) { null }
            if (pdfUri != null) {
                vm.importScannedUri(pdfUri, onSaved)
            } else {
                val uris = try { parsed?.pages?.map { it.imageUri } ?: emptyList() } catch (_: Exception) { emptyList() }
                if (uris.isNotEmpty()) {
                    vm.importScannedUris(uris, onSaved)
                } else {
                    errorText = "Scan finished but no pages found."
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan to PDF") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(Icons.Filled.Camera, contentDescription = null, modifier = Modifier.padding(8.dp))
            Text(
                "Scan with ML Kit",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Documents are auto-detected, straightened, and enhanced on-device via Google Play Services. " +
                    "First scan downloads models once (needs internet), then works offline. " +
                    "No camera permission needed from this app.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (scanning) return@Button
                    if (activity == null) {
                        errorText = "No activity context."
                        return@Button
                    }
                    scanning = true
                    scanner.getStartScanIntent(activity)
                        .addOnSuccessListener { sender ->
                            launcher.launch(IntentSenderRequest.Builder(sender).build())
                        }
                        .addOnFailureListener { e ->
                            scanning = false
                            errorText = if (e.message?.contains("UNSUPPORTED", ignoreCase = true) == true || e.message?.contains("1.7", ignoreCase = true) == true) {
                                "Device does not support document scanning (needs 1.7GB RAM + Play Services)."
                            } else {
                                "Could not start scanner: ${e.message}"
                            }
                        }
                },
                enabled = !scanning && !ui.saving,
            ) {
                if (scanning || ui.saving) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                }
                Text("Start scan")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Tip: allow gallery import inside the scanner to use a photo instead of the camera.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
