package com.faridhafizh.pdfreader.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Badge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.faridhafizh.pdfreader.data.AnnType
import com.faridhafizh.pdfreader.data.AnnotationEntity
import com.faridhafizh.pdfreader.data.SignatureEntity
import com.faridhafizh.pdfreader.data.SignaturePlacementEntity
import com.faridhafizh.pdfreader.pdf.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import androidx.compose.animation.core.SnapSpec
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(vm: ReaderViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val annotations by vm.annotations.collectAsStateWithLifecycle()
    val placements by vm.placements.collectAsStateWithLifecycle()
    val signatures by vm.signatures.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var zoomResetSignal by remember { mutableStateOf(0) }
    var initialScrollDone by rememberSaveable { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<AnnotationEntity?>(null) }
    var showSigPicker by remember { mutableStateOf(false) }
    var sigTarget by remember { mutableStateOf(SIG_TARGET_PAGE) }
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri -> if (uri != null) vm.export(uri) }

    LaunchedEffect(ui.loading) {
        if (!ui.loading && ui.error == null && !initialScrollDone) {
            listState.scrollToItem(ui.initialPage)
            initialScrollDone = true
        }
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ReaderEvent.ScrollTo -> listState.animateScrollToItem(e.page)
                is ReaderEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.debounce(400).collect { vm.onPageChanged(it) }
    }
    BackHandler(enabled = selection != null || ui.searchActive) {
        if (selection != null) vm.clearSelection() else vm.closeSearch()
    }

    Scaffold(
        topBar = {
            if (ui.searchActive) {
                SearchTopBar(ui, vm)
            } else {
                TopAppBar(
                    title = { Text(ui.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (!ui.loading && ui.error == null) {
                            IconButton(onClick = { vm.openSearch() }) { Icon(Icons.Filled.Search, "Search") }
                            if (pending > 0) {
                                IconButton(onClick = { vm.save() }, enabled = !ui.saving) {
                                    BadgedBox(badge = { Badge { Text("$pending") } }) {
                                        Icon(Icons.Filled.Save, "Save annotations & signatures into PDF")
                                    }
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    val marked = bookmarks.any { it.page == currentPage }
                                    DropdownMenuItem(
                                        text = { Text(if (marked) "Remove bookmark (page ${currentPage + 1})" else "Bookmark this page") },
                                        onClick = { menuOpen = false; vm.toggleBookmark(currentPage) },
                                    )
                                    DropdownMenuItem(text = { Text("Bookmarks") }, onClick = { menuOpen = false; showBookmarks = true })
                                    DropdownMenuItem(text = { Text("Add signature") }, onClick = { menuOpen = false; showSigPicker = true })
                                    DropdownMenuItem(text = { Text("Go to page…") }, onClick = { menuOpen = false; showJump = true })
                                    DropdownMenuItem(text = { Text("Reset zoom") }, onClick = { menuOpen = false; zoomResetSignal++ })
                                    DropdownMenuItem(
                                        text = { Text("Export copy…") },
                                        onClick = { menuOpen = false; exportLauncher.launch("${ui.title}.pdf") },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            selection?.let { sel ->
                SelectionBar(
                    onCopy = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("PDF text", sel.text))
                        vm.clearSelection()
                    },
                    onHighlight = { vm.annotateSelection(AnnType.HIGHLIGHT, it) },
                    onUnderline = { vm.annotateSelection(AnnType.UNDERLINE, UNDERLINE_COLOR) },
                    onClose = { vm.clearSelection() },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.error != null -> Text(
                    ui.error ?: "",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> {
                    PdfPages(
                        vm = vm,
                        ui = ui,
                        annotations = annotations,
                        placements = placements,
                        signatures = signatures,
                        selection = selection,
                        zoomResetSignal = zoomResetSignal,
                        listState = listState,
                        onTap = { page, fx, fy ->
                            val hit = annotations.firstOrNull { a ->
                                a.page == page && a.rects().any { fx in it.l..it.r && fy in it.t..it.b }
                            }
                            if (hit != null) toDelete = hit else vm.clearSelection()
                        },
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).clickable { showJump = true },
                    ) {
                        Text("${currentPage + 1} / ${ui.pageCount}", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }

    if (showJump) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJump = false },
            title = { Text("Go to page (1–${ui.pageCount})") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        text.toIntOrNull()?.let { vm.goTo(it - 1) }
                        showJump = false
                    }),
                )
            },
            confirmButton = {
                TextButton(onClick = { text.toIntOrNull()?.let { vm.goTo(it - 1) }; showJump = false }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showJump = false }) { Text("Cancel") } },
        )
    }

    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            Text("Bookmarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (bookmarks.isEmpty()) {
                Text("No bookmarks yet. Tap the bookmark icon to add one.", Modifier.padding(24.dp))
            }
            bookmarks.forEach { b ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.goTo(b.page); showBookmarks = false }.padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(b.label, Modifier.weight(1f))
                    IconButton(onClick = { vm.deleteBookmark(b.id) }) { Icon(Icons.Filled.Delete, "Delete bookmark") }
                }
            }
            Box(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
        }
    }

    toDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(if (a.type == AnnType.UNDERLINE) "Delete underline?" else "Delete highlight?") },
            text = { Text("Page ${a.page + 1}. It will also be removed from the PDF file on the next save.") },
            confirmButton = { TextButton(onClick = { vm.deleteAnnotation(a); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }

    if (showSigPicker) {
        ModalBottomSheet(onDismissRequest = { showSigPicker = false }) {
            Text("Add signature", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("This page" to SIG_TARGET_PAGE, "Next 6" to SIG_TARGET_RANGE, "All pages" to SIG_TARGET_ALL).forEach { (label, v) ->
                    TextButton(onClick = { sigTarget = v }, modifier = Modifier.weight(1f)) {
                        Text(if (sigTarget == v) "✓ $label" else label)
                    }
                }
            }
            if (signatures.isEmpty()) {
                Text("No signatures yet. Create one from the library (pencil icon).", Modifier.padding(24.dp))
            } else {
                signatures.forEach { s ->
                    val bmp = remember(s.id) { kotlinx.coroutines.runBlocking { vm.loadSignatureBitmap(s) } }
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            when (sigTarget) {
                                SIG_TARGET_ALL -> vm.addSignatureToAllPages(s.id)
                                SIG_TARGET_RANGE -> vm.addSignatureToPageRange(currentPage, s.id)
                                else -> vm.addSignatureToPage(currentPage, s.id)
                            }
                            showSigPicker = false
                        }.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (bmp != null) {
                            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 120.dp, height = 48.dp))
                        }
                        Text("Signature", Modifier.weight(1f).padding(horizontal = 12.dp))
                    }
                }
            }
            Box(Modifier.navigationBarsPadding().padding(bottom = 16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(ui: ReaderUiState, vm: ReaderViewModel) {
    var text by rememberSaveable { mutableStateOf(ui.query) }
    TopAppBar(
        title = {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Search in document") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.startSearch(text) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
        },
        navigationIcon = { IconButton(onClick = { vm.closeSearch() }) { Icon(Icons.Filled.Close, "Close search") } },
        actions = {
            if (ui.searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            if (ui.hits.isNotEmpty()) {
                Text("${ui.hitIndex + 1}/${ui.hits.size}${if (ui.searching) "+" else ""}", Modifier.padding(horizontal = 4.dp))
                IconButton(onClick = { vm.prevHit() }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous result") }
                IconButton(onClick = { vm.nextHit() }) { Icon(Icons.Filled.KeyboardArrowDown, "Next result") }
            }
        },
    )
}

@Composable
private fun SelectionBar(onCopy: () -> Unit, onHighlight: (Int) -> Unit, onUnderline: () -> Unit, onClose: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, "Copy") }
            HIGHLIGHT_COLORS.forEach { c ->
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c)).clickable { onHighlight(c) })
            }
            IconButton(onClick = onUnderline) { Icon(Icons.Filled.FormatUnderlined, "Underline") }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel selection") }
        }
    }
}

@Composable
private fun PdfPages(
    vm: ReaderViewModel,
    ui: ReaderUiState,
    annotations: List<AnnotationEntity>,
    placements: List<SignaturePlacementEntity>,
    signatures: List<SignatureEntity>,
    selection: Selection?,
    zoomResetSignal: Int,
    listState: LazyListState,
    onTap: (Int, Float, Float) -> Unit,
) {
    val session = vm.session ?: return
    val currentHit = ui.hits.getOrNull(ui.hitIndex)
    val sigById = signatures.associateBy { it.id }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(count = session.pageCount, key = { it }) { page ->
            val pageAnnotations = remember(annotations, page) { annotations.filter { it.page == page } }
            val pagePlacements = remember(placements, page) { placements.filter { it.page == page } }
            val pageHits = remember(ui.hits, page) { ui.hits.filter { it.page == page } }
            PageItem(
                vm = vm,
                page = page,
                aspect = session.aspect(page),
                annotations = pageAnnotations,
                placements = pagePlacements,
                sigById = sigById,
                hits = pageHits,
                currentHit = currentHit,
                selection = selection?.takeIf { it.page == page },
                zoomResetSignal = zoomResetSignal,
                listState = listState,
                onTap = onTap,
            )
        }
    }
}

private const val SIG_TARGET_PAGE = 0
private const val SIG_TARGET_RANGE = 1
private const val SIG_TARGET_ALL = 2

@Composable
private fun PageItem(
    vm: ReaderViewModel,
    page: Int,
    aspect: Float,
    annotations: List<AnnotationEntity>,
    placements: List<SignaturePlacementEntity>,
    sigById: Map<String, SignatureEntity>,
    hits: List<SearchHit>,
    currentHit: SearchHit?,
    selection: Selection?,
    zoomResetSignal: Int,
    listState: LazyListState,
    onTap: (Int, Float, Float) -> Unit,
) {
    var bitmap by remember(page) { mutableStateOf<Bitmap?>(null) }
    val currentOnTap by rememberUpdatedState(onTap)
    val density = LocalDensity.current
    val zoomState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 4f))
    // Set when the current touch turned into a long-press selection drag, so the
    // trailing click from Zoomable does not clear/delete right after selecting.
    var selectionDragSeen by remember(page) { mutableStateOf(false) }
    var selectedPlacementId by remember(page) { mutableStateOf<String?>(null) }

    // Returning to a page shows it unzoomed (same guidance as Telephoto for lazy layouts).
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == page } }
            .collect { visible -> if (!visible) zoomState.resetZoom(animationSpec = SnapSpec()) }
    }
    LaunchedEffect(zoomResetSignal) {
        if (zoomResetSignal > 0) zoomState.resetZoom()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
            if (page < first - 1 || page > first + 3) selectedPlacementId = null
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(aspect).background(Color.White),
    ) {
        val viewportWpx = with(density) { maxWidth.roundToPx() }
        val viewportHpx = with(density) { maxHeight.roundToPx() }
        val transformation = zoomState.contentTransformation
        val scaleX = if (transformation.isSpecified) transformation.scale.scaleX else 1f
        // Render width is capped (memory) and bucketed so tiny zoom changes do not re-render every frame.
        val bucket = (((viewportWpx * scaleX).roundToInt().coerceAtMost(2400) + 127) / 128) * 128

        // Keeps showing the previous bitmap while a new (sharper) one renders.
        LaunchedEffect(page, bucket) {
            try {
                bitmap = vm.pageBitmap(page, bucket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // leave the page blank; it will retry when it scrolls back into view
            }
        }

        // Modifier.zoomable captures the click lambda once, so latest values are read
        // through updated-state holders instead of closed-over locals.
        val latestTransformation by rememberUpdatedState(transformation)
        val latestViewportW by rememberUpdatedState(viewportWpx)
        val latestViewportH by rememberUpdatedState(viewportHpx)
        // Pan/zoom is suspended while selecting text so a selection drag does not pan the page,
        // or while a signature placement is selected (drag should edit the signature).
        val gestures = if (selection != null || selectedPlacementId != null) EnabledZoomGestures(zoom = false, pan = false) else EnabledZoomGestures.ZoomAndPan

        Box(
            Modifier.fillMaxSize().zoomable(
                state = zoomState,
                gestures = gestures,
                onClick = { viewportOffset ->
                    if (selectionDragSeen) {
                        selectionDragSeen = false
                        return@zoomable
                    }
                    val t = latestTransformation
                    if (!t.isSpecified) return@zoomable
                    val sx = t.scale.scaleX
                    val sy = t.scale.scaleY
                    if (sx == 0f || sy == 0f) return@zoomable
                    val w = latestViewportW.toFloat()
                    val h = latestViewportH.toFloat()
                    if (w <= 0f || h <= 0f) return@zoomable
                    // graphicsLayer uses a top-left origin, so viewport -> content is
                    // (offset - translation) / scale; content and viewport coincide here.
                    val fx = (viewportOffset.x - t.offset.x) / (sx * w)
                    val fy = (viewportOffset.y - t.offset.y) / (sy * h)
                    // Tap on a signature selects it (editor); otherwise normal tap (annotation delete / clear).
                    val hitPlacement = placements.firstOrNull { p ->
                        placementContains(p, sigById[p.signatureId], fx, fy, aspect)
                    }
                    if (hitPlacement != null) {
                        selectedPlacementId = hitPlacement.id
                    } else {
                        selectedPlacementId = null
                        currentOnTap(page, fx, fy)
                    }
                },
            ),
        ) {
            Box(
                Modifier.fillMaxSize().pointerInput(page) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o ->
                            selectionDragSeen = true
                            vm.startSelection(page, o.x / size.width, o.y / size.height)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            vm.extendSelection(page, change.position.x / size.width, change.position.y / size.height)
                        },
                    )
                },
            ) {
                bitmap?.let {
                    Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
                }
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    fun fill(r: com.faridhafizh.pdfreader.data.NRect, color: Color) = drawRect(
                        color = color,
                        topLeft = Offset(r.l * w, r.t * h),
                        size = Size((r.r - r.l) * w, (r.b - r.t) * h),
                    )
                    annotations.forEach { a ->
                        val color = Color(a.color)
                        a.rects().forEach { r ->
                            if (a.type == AnnType.UNDERLINE) {
                                drawLine(color, Offset(r.l * w, r.b * h), Offset(r.r * w, r.b * h), strokeWidth = 2.dp.toPx())
                            } else {
                                fill(r, color.copy(alpha = 0.4f))
                            }
                        }
                    }
                    hits.forEach { hit ->
                        val c = if (hit === currentHit) Color(0x99FF9800) else Color(0x55FFC107)
                        hit.rects.forEach { fill(it, c) }
                    }
                    selection?.rects?.forEach { fill(it, Color(0x552196F3)) }
                }
            }
            // Signature placements overlay (drag / resize / rotate editor)
            placements.forEach { p ->
                val sig = sigById[p.signatureId] ?: return@forEach
                val sigBmp = remember(sig.id) { kotlinx.coroutines.runBlocking { vm.loadSignatureBitmap(sig) } }
                SignaturePlacementView(
                    placement = p,
                    bitmap = sigBmp,
                    aspect = aspect,
                    selected = selectedPlacementId == p.id,
                    onSelected = { selectedPlacementId = p.id },
                    onTransform = { cx, cy, wf, rot -> vm.updatePlacement(p.id, cx, cy, wf, rot) },
                    onDelete = { vm.deletePlacement(p); selectedPlacementId = null },
                )
            }
        }
    }
}

@Composable
private fun SignaturePlacementView(
    placement: SignaturePlacementEntity,
    bitmap: Bitmap?,
    aspect: Float,
    selected: Boolean,
    onSelected: () -> Unit,
    onTransform: (cx: Float, cy: Float, wf: Float, rot: Float) -> Unit,
    onDelete: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val pageWdp = maxWidth
        val pageHdp = maxHeight
        if (pageWdp == 0.dp || pageHdp == 0.dp) return@BoxWithConstraints
        val sigRatio = if ((bitmap?.height ?: 0) > 0 && (bitmap?.width ?: 0) > 0)
            bitmap!!.height.toFloat() / bitmap.width.toFloat()
        else 0.25f
        var cx by remember(placement.id) { mutableFloatStateOf(placement.centerX) }
        var cy by remember(placement.id) { mutableFloatStateOf(placement.centerY) }
        var wf by remember(placement.id) { mutableFloatStateOf(placement.widthFrac) }
        var rot by remember(placement.id) { mutableFloatStateOf(placement.rotationDeg) }
        LaunchedEffect(placement.centerX, placement.centerY, placement.widthFrac, placement.rotationDeg) {
            cx = placement.centerX; cy = placement.centerY; wf = placement.widthFrac; rot = placement.rotationDeg
        }
        val wDp = pageWdp * wf.coerceIn(0.05f, 0.95f)
        val hDp = wDp * sigRatio
        val density = LocalDensity.current
        val pageWpx = with(density) { pageWdp.roundToPx() }
        val pageHpx = with(density) { pageHdp.roundToPx() }
        val leftPx = ((cx - wf / 2f) * pageWpx).roundToInt()
        val topPx = ((cy - wf * aspect * sigRatio / 2f) * pageHpx).roundToInt()

        Box(
            Modifier
                .offset { IntOffset(leftPx, topPx) }
                .size(wDp, hDp)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
                .graphicsLayer(
                    rotationZ = rot,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f),
                )
                .pointerInput(placement.id, selected) {
                    detectDragGestures(
                        onDragStart = { onSelected() },
                        onDrag = { change, dragAmount ->
                            if (selected) {
                                change.consume()
                                val pageWpx = size.width.toFloat().coerceAtLeast(1f) / wf.coerceAtLeast(0.05f)
                                // dragAmount is in local (content) px; parent page px derived from wDp.
                                cx = (cx + dragAmount.x / pageWForDrag(pageWdp, density)).coerceIn(0f, 1f)
                                cy = (cy + dragAmount.y / pageHForDrag(pageHdp, density)).coerceIn(0f, 1f)
                            }
                        },
                        onDragEnd = { if (selected) onTransform(cx, cy, wf, rot) },
                        onDragCancel = { if (selected) onTransform(cx, cy, wf, rot) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            if (selected) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.align(Alignment.TopStart).size(36.dp).background(Color.White, CircleShape),
                ) { Icon(Icons.Filled.Delete, contentDescription = "Delete signature", modifier = Modifier.size(20.dp)) }
                Box(
                    Modifier.align(Alignment.TopEnd).size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                        .pointerInput(placement.id) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    // Rotate by horizontal drag (fallback; precise centroid unavailable here).
                                    rot = (rot + dragAmount.x * 0.5f) % 360f
                                },
                                onDragEnd = { onTransform(cx, cy, wf, rot) },
                                onDragCancel = { onTransform(cx, cy, wf, rot) },
                            )
                        },
                    Alignment.Center,
                ) { Icon(Icons.Filled.Refresh, contentDescription = "Rotate", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                        .pointerInput(placement.id) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val dw = (dragAmount.x + dragAmount.y) / pageWForDrag(pageWdp, density) * 0.5f
                                    wf = (wf + dw).coerceIn(0.1f, 0.9f)
                                },
                                onDragEnd = { onTransform(cx, cy, wf, rot) },
                                onDragCancel = { onTransform(cx, cy, wf, rot) },
                            )
                        },
                    Alignment.Center,
                ) { Icon(Icons.Filled.Edit, contentDescription = "Resize", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

private fun pageWForDrag(pageWdp: androidx.compose.ui.unit.Dp, density: androidx.compose.ui.unit.Density): Float =
    with(density) { pageWdp.roundToPx() }.toFloat().coerceAtLeast(1f)

private fun pageHForDrag(pageHdp: androidx.compose.ui.unit.Dp, density: androidx.compose.ui.unit.Density): Float =
    with(density) { pageHdp.roundToPx() }.toFloat().coerceAtLeast(1f)

private fun placementContains(
    p: SignaturePlacementEntity,
    sig: SignatureEntity?,
    fx: Float,
    fy: Float,
    pageAspect: Float,
): Boolean {
    val sigRatio = if (sig != null && sig.widthPx > 0) sig.heightPx.toFloat() / sig.widthPx else 0.25f
    val hFrac = p.widthFrac * pageAspect * sigRatio
    val dx = fx - p.centerX
    val dy = (fy - p.centerY) * (pageAspect)
    // Unrotate point around center (degrees clockwise in display space).
    val rad = Math.toRadians(p.rotationDeg.toDouble())
    val c = kotlin.math.cos(rad); val s = kotlin.math.sin(rad)
    val ux = dx * c + dy * s
    val uy = -dx * s + dy * c
    return abs(ux) <= p.widthFrac / 2f && abs(uy) <= hFrac / 2f
}
