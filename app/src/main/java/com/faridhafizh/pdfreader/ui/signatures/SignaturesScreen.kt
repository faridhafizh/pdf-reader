package com.faridhafizh.pdfreader.ui.signatures

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faridhafizh.pdfreader.data.NRect
import com.faridhafizh.pdfreader.data.SignatureEntity
import com.faridhafizh.pdfreader.pdf.SignatureCleanup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignaturesScreen(vm: SignaturesViewModel, onBack: () -> Unit) {
    val sigs by vm.signatures.collectAsStateWithLifecycle(initialValue = emptyList())
    val saving by vm.saving.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var editor by remember { mutableStateOf<EditorMode?>(null) }
    var toDelete by remember { mutableStateOf<SignatureEntity?>(null) }

    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message!!)
            vm.clearMessage()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) editor = EditorMode.Crop(uri)
    }

    // Editor replaces the list.
    val ed = editor
    if (ed != null) {
        SignatureEditor(
            vm = vm,
            mode = ed,
            onDone = { editor = null },
            onCancel = { editor = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Signatures") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Crop from photo")
                }
                Button(onClick = { editor = EditorMode.Draw }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(6.dp)); Text("Draw")
                }
            }
            Spacer(Modifier.height(16.dp))
            if (sigs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text("No signatures yet.\nAdd one with Crop or Draw.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(sigs, key = { it.id }) { s ->
                        SignatureCard(vm = vm, sig = s, onDelete = { toDelete = s })
                    }
                }
            }
        }
    }

    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete signature?") },
            text = { Text("Placements already saved into PDFs keep their copy. Unsaved placements will be lost.") },
            confirmButton = { TextButton(onClick = { vm.delete(s); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

sealed interface EditorMode {
    data class Crop(val uri: Uri) : EditorMode
    data object Draw : EditorMode
}

@Composable
private fun SignatureCard(vm: SignaturesViewModel, sig: SignatureEntity, onDelete: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, sig.id) { value = vm.loadBitmap(sig) }
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.size(width = 160.dp, height = 120.dp)) {
        Box(Modifier.fillMaxSize().background(Color(0xFFEEEEEE)).padding(4.dp).clickable(onClick = onDelete)) {
            bmp?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), strokeWidth = 2.dp)
            Box(Modifier.align(Alignment.TopEnd).size(28.dp).background(Color.White, RoundedCornerShape(8.dp)), Alignment.Center) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignatureEditor(vm: SignaturesViewModel, mode: EditorMode, onDone: () -> Unit, onCancel: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode is EditorMode.Crop) "Crop signature" else "Draw signature") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Close") } },
            )
        },
    ) { padding ->
        when (mode) {
            is EditorMode.Crop -> CropFlow(vm, mode.uri, onDone)
            EditorMode.Draw -> DrawFlow(vm, onDone)
        }
    }
}

@Composable
private fun CropFlow(vm: SignaturesViewModel, uri: Uri, onDone: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val scale = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 1200)
            val o2 = BitmapFactory.Options().apply { inSampleSize = scale }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
        }
    }
    val bmp = bitmap
    if (bmp == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var crop by remember(bmp) { mutableStateOf(NRect(0.2f, 0.3f, 0.8f, 0.7f)) }
    var stage by remember { mutableStateOf(0) } // 0 crop, 1 cleanup
    if (stage == 1) {
        val cropped = remember(bmp, crop) { cropBitmap(bmp, crop) }
        CleanupStep(vm = vm, raw = cropped, onDone = onDone)
        return
    }
    Column(Modifier.padding(16.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).background(Color.White),
        ) {
            val density = LocalDensity.current
            val contWpx = with(density) { maxWidth.roundToPx() }.toFloat()
            val contHpx = with(density) { maxHeight.roundToPx() }.toFloat()
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            // Dim outside crop
            CropDim(crop, Modifier.fillMaxSize())
            // Crop box (move)
            Box(
                Modifier.offset {
                    IntOffset(
                        (crop.l * contWpx).roundToInt(),
                        (crop.t * contHpx).roundToInt(),
                    )
                }.size(
                    width = with(density) { (maxWidth * (crop.r - crop.l)).coerceIn(80.dp, maxWidth) },
                    height = with(density) { (maxHeight * (crop.b - crop.t)).coerceIn(40.dp, maxHeight) },
                ).border(2.dp, MaterialTheme.colorScheme.primary)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dW = dragAmount.x / contWpx
                            val dH = dragAmount.y / contHpx
                            val w = crop.r - crop.l
                            val h = crop.b - crop.t
                            val nl = (crop.l + dW).coerceIn(0f, 1f - w)
                            val nt = (crop.t + dH).coerceIn(0f, 1f - h)
                            crop = NRect(nl, nt, nl + w, nt + h)
                        }
                    },
            ) {
                // Resize handle bottom-right
                Box(
                    Modifier.align(Alignment.BottomEnd).size(36.dp).background(MaterialTheme.colorScheme.primary)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val dW = dragAmount.x / contWpx
                                val dH = dragAmount.y / contHpx
                                crop = crop.copy(
                                    r = (crop.r + dW).coerceIn(crop.l + 0.1f, 1f),
                                    b = (crop.b + dH).coerceIn(crop.t + 0.05f, 1f),
                                )
                            }
                        },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { stage = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Next — clean background") }
        Spacer(Modifier.height(8.dp))
        Text("Drag the box to move it, drag the blue handle to resize.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CropDim(crop: NRect, modifier: Modifier = Modifier) {
    Box(modifier) {
        // top
        Box(Modifier.fillMaxWidth().height((1f * 0f).dp).background(Color.Transparent))
        Box(Modifier.matchParentSize()) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, 0f), size = size.copy(height = crop.t * size.height))
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, crop.b * size.height), size = size.copy(height = size.height - crop.b * size.height))
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(0f, crop.t * size.height), size = size.copy(width = crop.l * size.width, height = (crop.b - crop.t) * size.height))
                drawRect(Color.Black.copy(alpha = 0.45f), topLeft = Offset(crop.r * size.width, crop.t * size.height), size = size.copy(width = size.width - crop.r * size.width, height = (crop.b - crop.t) * size.height))
            }
        }
    }
}

private fun cropBitmap(bmp: Bitmap, r: NRect): Bitmap {
    val x = (r.l * bmp.width).toInt().coerceIn(0, bmp.width - 1)
    val y = (r.t * bmp.height).toInt().coerceIn(0, bmp.height - 1)
    val x2 = (r.r * bmp.width).toInt().coerceIn(x + 1, bmp.width)
    val y2 = (r.b * bmp.height).toInt().coerceIn(y + 1, bmp.height)
    return Bitmap.createBitmap(bmp, x, y, x2 - x, y2 - y)
}

@Composable
private fun DrawFlow(vm: SignaturesViewModel, onDone: () -> Unit) {
    // Strokes stored as normalized fractions (0..1 of canvas) so sizing is resolution independent.
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var brushFrac by remember { androidx.compose.runtime.mutableFloatStateOf(0.025f) }
    var stage by remember { mutableStateOf(0) }
    if (stage == 1) {
        val raw = remember(strokes, brushFrac) { strokesToBitmap(strokes, brushFrac) }
        CleanupStep(vm = vm, raw = raw, onDone = onDone)
        return
    }
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        Text("Draw your signature with your finger.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(220.dp).background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o ->
                            val cw = size.width.toFloat().coerceAtLeast(1f)
                            val ch = size.height.toFloat().coerceAtLeast(1f)
                            current = listOf(Offset(o.x / cw, o.y / ch))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val cw = size.width.toFloat().coerceAtLeast(1f)
                            val ch = size.height.toFloat().coerceAtLeast(1f)
                            current = current + Offset(change.position.x / cw, change.position.y / ch)
                        },
                        onDragEnd = {
                            if (current.size >= 2) strokes = strokes + listOf(current)
                            current = emptyList()
                        },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val all: List<List<Offset>> = if (current.isNotEmpty()) strokes + listOf(current) else strokes
                val strokeW = size.width * brushFrac
                for (s in all) {
                    if (s.size < 2) continue
                    for (i in 1 until s.size) {
                        drawLine(
                            Color.Black,
                            Offset(s[i - 1].x * size.width, s[i - 1].y * size.height),
                            Offset(s[i].x * size.width, s[i].y * size.height),
                            strokeWidth = strokeW,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Brush size: ${(brushFrac * 100).roundToInt()}% of width")
        Slider(value = brushFrac, onValueChange = { brushFrac = it }, valueRange = 0.01f..0.06f)
        Row {
            TextButton(onClick = { strokes = emptyList(); current = emptyList() }) { Text("Clear") }
            TextButton(onClick = { strokes = strokes.dropLast(1) }) { Text("Undo") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { stage = 1 }, enabled = strokes.isNotEmpty()) { Text("Next — clean") }
        }
        Spacer(Modifier.height(4.dp))
        Text("Canvas is 220dp tall — brush is thin by default for handwriting; drag the slider to thicken.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun strokesToBitmap(strokes: List<List<Offset>>, brushFrac: Float = 0.025f): Bitmap {
    // Strokes are normalized fractions (0..1): scale directly to bitmap coords.
    val w = 1200
    val h = 600
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)
    if (strokes.isEmpty()) return bmp
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        strokeWidth = w * brushFrac.coerceIn(0.01f, 0.06f)
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
        style = android.graphics.Paint.Style.STROKE
        isAntiAlias = true
    }
    for (pts in strokes) {
        if (pts.size < 2) continue
        val path = android.graphics.Path()
        pts.forEachIndexed { i, p ->
            val x = (p.x * w).coerceIn(0f, w.toFloat())
            val y = (p.y * h).coerceIn(0f, h.toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, paint)
    }
    return bmp
}

@Composable
private fun CleanupStep(vm: SignaturesViewModel, raw: Bitmap, onDone: () -> Unit) {
    var threshold by remember { mutableFloatStateOf(235f) }
    var boost by remember { mutableFloatStateOf(2.2f) }
    val saving by vm.saving.collectAsStateWithLifecycle()
    val cleaned by produceState<Bitmap?>(null, raw, threshold, boost) {
        value = withContext(Dispatchers.Default) {
            val scaled = SignatureCleanup.scaleDown(raw, 1200)
            SignatureCleanup.trimTransparent(SignatureCleanup.clean(scaled, threshold.roundToInt(), boost), pad = 6)
        }
    }
    Column(Modifier.padding(16.dp)) {
        Text("Preview (checkered = transparent)", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        // Checkered background
        Box(
            Modifier.fillMaxWidth().height(160.dp)
                .background(checkerPainterBg())
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            Alignment.Center,
        ) {
            cleaned?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            } ?: CircularProgressIndicator()
        }
        Spacer(Modifier.height(12.dp))
        Text("White threshold: ${threshold.roundToInt()} (higher keeps more grey)")
        Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 200f..250f)
        Text("Ink strength: ${"%.1f".format(boost)} (higher thickens faint strokes)")
        Slider(value = boost, onValueChange = { boost = it }, valueRange = 1f..3f)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val b = cleaned ?: return@Button
                val bytes = ByteArrayOutputStream().use { out ->
                    b.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
                vm.savePng(bytes, b.width, b.height, onDone)
            },
            enabled = cleaned != null && !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp)); Text("Save signature")
        }
        Spacer(Modifier.height(8.dp))
        Text("White paper becomes 100% transparent; ink stays solid black.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun checkerPainterBg(): Color = Color(0xFFDDDDDD)
