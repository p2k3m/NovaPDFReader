package com.novapdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novapdf.reader.R
import com.novapdf.reader.TilePreloadSpec
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PdfOutlineNode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerRoute(
    viewModel: PdfViewerViewModel,
    snackbarHost: SnackbarHostState,
    onOpenDocument: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PdfViewerScreen(
        state = uiState,
        snackbarHost = snackbarHost,
        onOpenDocument = onOpenDocument,
        onPageChange = { viewModel.onPageFocused(it) },
        onStrokeFinished = { viewModel.addAnnotation(it) },
        onSaveAnnotations = { viewModel.persistAnnotations() },
        onSearch = { viewModel.search(it) },
        onToggleBookmark = { viewModel.toggleBookmark() },
        onOutlineDestinationSelected = { viewModel.jumpToPage(it) },
        onExportDocument = { viewModel.exportDocument(context) },
        renderTile = { index, rect, scale -> viewModel.renderTile(index, rect, scale) },
        requestPageSize = { viewModel.pageSize(it) },
        onTileSpecChanged = { viewModel.updateTileSpec(it) },
        onToggleDynamicColor = { viewModel.setDynamicColorEnabled(it) },
        onToggleHighContrast = { viewModel.setHighContrastEnabled(it) },
        dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    state: PdfViewerUiState,
    snackbarHost: SnackbarHostState,
    onOpenDocument: () -> Unit,
    onPageChange: (Int) -> Unit,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    onSaveAnnotations: () -> Unit,
    onSearch: (String) -> Unit,
    onToggleBookmark: () -> Unit,
    onOutlineDestinationSelected: (Int) -> Unit,
    onExportDocument: () -> Boolean,
    renderTile: suspend (Int, Rect, Float) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    onTileSpecChanged: (TilePreloadSpec) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleHighContrast: (Boolean) -> Unit,
    dynamicColorSupported: Boolean
) {
    var searchQuery by remember { mutableStateOf("") }
    val latestOnPageChange by rememberUpdatedState(onPageChange)
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val echoModeController = remember(context.applicationContext) {
        EchoModeController(context.applicationContext)
    }
    val fallbackHaptics by rememberUpdatedState(newValue = {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    })
    val coroutineScope = rememberCoroutineScope()
    var showOutlineSheet by remember { mutableStateOf(false) }
    val exportErrorMessage = stringResource(id = R.string.export_failed)

    DisposableEffect(echoModeController) {
        onDispose { echoModeController.shutdown() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "NovaPDF Reader", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenDocument) {
                        Icon(imageVector = Icons.Outlined.FileOpen, contentDescription = "Open PDF")
                    }
                    IconButton(onClick = onSaveAnnotations) {
                        Icon(imageVector = Icons.Outlined.Download, contentDescription = "Save annotations")
                    }
                    val hasDocument = state.documentId != null
                    if (hasDocument) {
                        IconButton(
                            onClick = { showOutlineSheet = true },
                            enabled = state.outline.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.List,
                                contentDescription = stringResource(id = R.string.pdf_outline)
                            )
                        }
                        IconButton(onClick = {
                            val success = onExportDocument()
                            if (!success) {
                                coroutineScope.launch { snackbarHost.showSnackbar(exportErrorMessage) }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = stringResource(id = R.string.export_document)
                            )
                        }
                    }
                    IconButton(onClick = onToggleBookmark, enabled = hasDocument) {
                        val bookmarked = state.bookmarks.contains(state.currentPage)
                        val icon = if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder
                        Icon(imageVector = icon, contentDescription = "Toggle bookmark")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.Top
        ) {
            SearchBar(
                query = searchQuery,
                onQueryChange = {
                    searchQuery = it
                    onSearch(it)
                },
                resultsCount = state.searchResults.sumOf { it.matches.size }
            )

            AdaptiveFlowStatusRow(state) {
                val summary = state.echoSummary()
                if (summary != null) {
                    echoModeController.speakSummary(summary) {
                        fallbackHaptics()
                    }
                } else {
                    fallbackHaptics()
                }
            }

            AccessibilitySettings(
                dynamicColorEnabled = state.dynamicColorEnabled,
                highContrastEnabled = state.highContrastEnabled,
                dynamicColorSupported = dynamicColorSupported,
                onDynamicColorChanged = onToggleDynamicColor,
                onHighContrastChanged = onToggleHighContrast
            )

            if (state.pageCount == 0) {
                EmptyState(onOpenDocument)
            } else {
                PdfPager(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    onPageChange = latestOnPageChange,
                    onStrokeFinished = onStrokeFinished,
                    renderTile = renderTile,
                    requestPageSize = requestPageSize,
                    onTileSpecChanged = onTileSpecChanged
                )
            }
        }
    }

    if (showOutlineSheet) {
        OutlineSheet(
            outline = state.outline,
            onSelect = {
                onOutlineDestinationSelected(it)
                showOutlineSheet = false
            },
            onDismiss = { showOutlineSheet = false }
        )
    }
}

@Composable
private fun SearchHighlightOverlay(
    modifier: Modifier,
    matches: List<com.novapdf.reader.model.SearchMatch>
) {
    if (matches.isEmpty()) return
    val highlight = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
    androidx.compose.foundation.Canvas(modifier = modifier) {
        matches.flatMap { it.boundingBoxes }.forEach { rect ->
            drawRect(
                color = highlight,
                topLeft = Offset(rect.left * size.width, rect.top * size.height),
                size = androidx.compose.ui.geometry.Size(
                    (rect.right - rect.left) * size.width,
                    (rect.bottom - rect.top) * size.height
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(
    outline: List<PdfOutlineNode>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss
    ) {
        if (outline.isEmpty()) {
            Text(
                text = stringResource(id = R.string.outline_empty),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            val items = remember(outline) { flattenOutline(outline) }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                items(items, key = { it.key }) { item ->
                    OutlineRow(item = item, onSelect = onSelect)
                }
            }
        }
    }
}

private data class OutlineListItem(val node: PdfOutlineNode, val depth: Int) {
    val key: String = "${depth}_${node.pageIndex}_${node.title}"
}

private fun flattenOutline(nodes: List<PdfOutlineNode>, depth: Int = 0): List<OutlineListItem> {
    val result = mutableListOf<OutlineListItem>()
    nodes.forEach { node ->
        result += OutlineListItem(node, depth)
        if (node.children.isNotEmpty()) {
            result += flattenOutline(node.children, depth + 1)
        }
    }
    return result
}

@Composable
private fun OutlineRow(
    item: OutlineListItem,
    onSelect: (Int) -> Unit
) {
    val indent = (item.depth * 12).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(item.node.pageIndex) }
            .padding(start = 16.dp + indent, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Text(
            text = item.node.title,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(id = R.string.outline_page_label, item.node.pageIndex + 1),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


private fun LazyListState.closestPageIndex(): Int? {
    val layoutInfo = layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) return null
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    return visibleItems.minByOrNull { item ->
        abs((item.offset + item.size / 2f) - viewportCenter)
    }?.index
}

private class PagerSnapLayoutInfoProvider(
    private val lazyListState: LazyListState
) : SnapLayoutInfoProvider {

    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f

    override fun calculateSnapOffset(velocity: Float): Float {
        val layoutInfo = lazyListState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        if (visibleItems.isEmpty()) return 0f

        val viewportStart = layoutInfo.viewportStartOffset
        val viewportEnd = layoutInfo.viewportEndOffset
        val viewportCenter = viewportStart + (viewportEnd - viewportStart) / 2f
        val closest = visibleItems.minByOrNull { item ->
            abs((item.offset + item.size / 2f) - viewportCenter)
        } ?: return 0f

        val offsetFromStart = (closest.offset - viewportStart).toFloat()
        val halfSize = closest.size / 2f
        val velocityThreshold = PagerSnapVelocityThreshold

        val targetIndex = when {
            velocity > velocityThreshold -> (closest.index + 1).coerceAtMost(layoutInfo.totalItemsCount - 1)
            velocity < -velocityThreshold -> (closest.index - 1).coerceAtLeast(0)
            offsetFromStart <= -halfSize -> (closest.index + 1).coerceAtMost(layoutInfo.totalItemsCount - 1)
            offsetFromStart >= halfSize -> (closest.index - 1).coerceAtLeast(0)
            else -> closest.index
        }

        return distanceToIndex(layoutInfo, targetIndex)
    }

    private fun distanceToIndex(layoutInfo: LazyListLayoutInfo, index: Int): Float {
        val viewportStart = layoutInfo.viewportStartOffset
        val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (visible != null) {
            return (visible.offset - viewportStart).toFloat()
        }

        val reference = layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0f
        val pageSize = reference.size.takeIf { it != 0 } ?: return 0f
        val deltaItems = index - reference.index
        val predictedOffset = reference.offset + deltaItems * pageSize
        return (predictedOffset - viewportStart).toFloat()
    }

    companion object {
        private const val PagerSnapVelocityThreshold = 1200f
    }
}

private fun determineTileGrid(
    scale: Float,
    density: Density,
    widthPx: Float,
    heightPx: Float
): TileGrid {
    val densityScale = density.density.coerceAtLeast(0.5f)
    val scaledWidth = widthPx * scale
    val scaledHeight = heightPx * scale
    val targetTileSize = (densityScale * 640f).coerceAtLeast(1f)

    fun tilesFor(dimension: Float): Int {
        if (dimension <= 0f) return 2
        val raw = ceil(dimension / targetTileSize).toInt()
        return raw.coerceIn(2, 4)
    }

    val columns = tilesFor(scaledWidth)
    val rows = tilesFor(scaledHeight)
    return TileGrid(columns = columns, rows = rows)
}

private data class TileGrid(val columns: Int, val rows: Int)

private data class PageTileKey(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val scaleBits: Int
)

private class PagerGesturePipeline(context: Context) {
    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector
    private var sensitivity = 1f
    private var pageWidth = 0f
    private var pageHeight = 0f
    private var consumedFling = false
    private var accumulatedScrollX = 0f

    var onScaleListener: (Float) -> Unit = {}
    var onPanListener: (Float, Float) -> Unit = { _, _ -> }
    var onFlingListener: (Int, Float) -> Unit = { _, _ -> }

    init {
        val simpleListener = object : GestureDetector.OnGestureListener {
            override fun onDown(e: MotionEvent): Boolean {
                consumedFling = false
                return true
            }

            override fun onShowPress(e: MotionEvent) = Unit

            override fun onSingleTapUp(e: MotionEvent): Boolean = false

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                accumulatedScrollX += distanceX
                onPanListener(distanceX, distanceY)
                return false
            }

            override fun onLongPress(e: MotionEvent) = Unit

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (abs(velocityX) < minimumFlingVelocity()) {
                    return false
                }
                val direction = if (velocityX < 0) 1 else -1
                consumedFling = true
                onFlingListener(direction, abs(velocityX))
                return true
            }
        }
        gestureDetector = GestureDetector(context, simpleListener)
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = 1f + ((detector.scaleFactor - 1f) * sensitivity)
                onScaleListener(factor.coerceIn(0.75f, 1.4f))
                return true
            }
        })
    }

    fun updateSensitivity(value: Float) {
        sensitivity = value.coerceIn(0.6f, 2.5f)
    }

    fun updatePageBounds(width: Float, height: Float) {
        pageWidth = width
        pageHeight = height
    }

    fun onTouchEvent(event: MotionEvent, currentScale: Float): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            val shouldAdvance = !consumedFling && currentScale <= 1.05f && pageWidth > 0f
            if (shouldAdvance) {
                val threshold = (pageWidth * 0.25f) / sensitivity.coerceAtLeast(0.6f)
                if (abs(accumulatedScrollX) > threshold) {
                    val direction = if (accumulatedScrollX < 0) 1 else -1
                    onFlingListener(direction, 0f)
                }
            }
            accumulatedScrollX = 0f
            consumedFling = false
        }
        return currentScale > 1.05f || scaleDetector.isInProgress || consumedFling
    }

    private fun minimumFlingVelocity(): Float {
        return (900f / sensitivity).coerceAtLeast(320f)
    }
}





@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultsCount: Int
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        placeholder = { Text("Search") },
        leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (resultsCount > 0) {
                BadgedBox(badge = { Badge { Text(resultsCount.toString()) } }) {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                }
            }
        }
    )
}

@Composable
private fun AdaptiveFlowStatusRow(
    state: PdfViewerUiState,
    onEchoModeRequested: () -> Unit
) {
    val status = if (state.swipeSensitivity > 1.2f) "Adaptive Flow Active" else "Adaptive Flow Ready"
    val icon = if (state.isNightMode) Icons.Outlined.Brightness4 else Icons.Outlined.Brightness7
    AssistChip(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                contentDescription = "$status. Activate to hear an Echo Mode summary."
            },
        onClick = onEchoModeRequested,
        label = {
            Text(
                text = "$status · ${state.readingSpeed.toInt()} ppm",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null)
        }
    )
}

@Composable
private fun AccessibilitySettings(
    dynamicColorEnabled: Boolean,
    highContrastEnabled: Boolean,
    dynamicColorSupported: Boolean,
    onDynamicColorChanged: (Boolean) -> Unit,
    onHighContrastChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(id = R.string.accessibility_settings_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        SettingsToggleRow(
            label = stringResource(id = R.string.dynamic_color_label),
            description = stringResource(
                id = if (dynamicColorSupported) {
                    R.string.dynamic_color_description
                } else {
                    R.string.dynamic_color_unsupported
                }
            ),
            checked = dynamicColorEnabled && dynamicColorSupported,
            enabled = dynamicColorSupported,
            onCheckedChange = onDynamicColorChanged
        )
        SettingsToggleRow(
            label = stringResource(id = R.string.high_contrast_label),
            description = stringResource(id = R.string.high_contrast_description),
            checked = highContrastEnabled && dynamicColorEnabled && dynamicColorSupported,
            enabled = dynamicColorEnabled && dynamicColorSupported,
            onCheckedChange = onHighContrastChanged
        )
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label }
        )
    }
}

@Composable
private fun EmptyState(onOpenDocument: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(imageVector = Icons.Outlined.FileOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = "Open a PDF to begin",
            modifier = Modifier.padding(top = 16.dp)
        )
        Button(onClick = onOpenDocument, modifier = Modifier.padding(top = 16.dp)) {
            Text("Open PDF")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfPager(
    modifier: Modifier,
    state: PdfViewerUiState,
    onPageChange: (Int) -> Unit,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    renderTile: suspend (Int, Rect, Float) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    onTileSpecChanged: (TilePreloadSpec) -> Unit
) {
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val latestOnPageChange by rememberUpdatedState(onPageChange)
    val latestState by rememberUpdatedState(state)
    val latestStrokeFinished by rememberUpdatedState(onStrokeFinished)
    val latestRenderTile by rememberUpdatedState(renderTile)
    val latestRequestPageSize by rememberUpdatedState(requestPageSize)
    val latestTileSpecChanged by rememberUpdatedState(onTileSpecChanged)
    val decaySpec = rememberSplineBasedDecay<Float>()
    val snapAnimationSpec: SpringSpec<Float> = remember {
        spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.85f)
    }
    val snapLayoutInfoProvider = remember(lazyListState) { PagerSnapLayoutInfoProvider(lazyListState) }
    val flingBehavior = remember(decaySpec, snapAnimationSpec, snapLayoutInfoProvider) {
        snapFlingBehavior(
            snapLayoutInfoProvider = snapLayoutInfoProvider,
            decayAnimationSpec = decaySpec,
            snapAnimationSpec = snapAnimationSpec
        )
    }

    LaunchedEffect(state.pageCount, state.currentPage) {
        if (state.pageCount <= 0) return@LaunchedEffect
        val target = state.currentPage.coerceIn(0, state.pageCount - 1)
        val current = lazyListState.closestPageIndex() ?: lazyListState.firstVisibleItemIndex
        if (current != target || lazyListState.firstVisibleItemScrollOffset != 0) {
            lazyListState.scrollToItem(target)
        }
    }

    LaunchedEffect(lazyListState, state.pageCount) {
        if (state.pageCount <= 0) return@LaunchedEffect
        snapshotFlow { lazyListState.closestPageIndex() }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { latestOnPageChange(it) }
    }

    val requestPageFling: (Int, Float) -> Unit = { direction, _ ->
        coroutineScope.launch {
            val pageCount = latestState.pageCount
            if (pageCount <= 0) return@launch
            val current = lazyListState.closestPageIndex() ?: latestState.currentPage
            val target = (current + direction).coerceIn(0, pageCount - 1)
            if (target != current) {
                lazyListState.animateScrollToItem(target)
            }
        }
    }

    LazyRow(
        modifier = modifier,
        state = lazyListState,
        flingBehavior = flingBehavior,
        userScrollEnabled = state.pageCount > 0
    ) {
        items(count = state.pageCount, key = { it }) { pageIndex ->
            Box(modifier = Modifier.fillMaxSize()) {
                PdfPageContainer(
                    pageIndex = pageIndex,
                    state = state,
                    swipeSensitivity = state.swipeSensitivity,
                    onStrokeFinished = latestStrokeFinished,
                    renderTile = latestRenderTile,
                    requestPageSize = latestRequestPageSize,
                    onTileSpecChanged = latestTileSpecChanged,
                    onRequestPageFling = requestPageFling
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun PdfPageContainer(
    pageIndex: Int,
    state: PdfViewerUiState,
    swipeSensitivity: Float,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    renderTile: suspend (Int, Rect, Float) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    onTileSpecChanged: (TilePreloadSpec) -> Unit,
    onRequestPageFling: (direction: Int, velocity: Float) -> Unit
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
    var translation by remember(pageIndex) { mutableStateOf(Offset.Zero) }
    var pageSize by remember(pageIndex) { mutableStateOf<Size?>(null) }
    val tileBitmaps = remember(pageIndex) { mutableStateMapOf<PageTileKey, Bitmap>() }
    val latestRenderTile by rememberUpdatedState(renderTile)
    val latestTileSpec by rememberUpdatedState(onTileSpecChanged)
    val latestPageSizeRequest by rememberUpdatedState(requestPageSize)
    val latestFling by rememberUpdatedState(onRequestPageFling)
    val gesturePipeline = remember(pageIndex) { PagerGesturePipeline(context) }

    LaunchedEffect(pageIndex) {
        pageSize = latestPageSizeRequest(pageIndex)
    }

    DisposableEffect(pageIndex) {
        onDispose {
            tileBitmaps.values.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            tileBitmaps.clear()
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val widthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
        val heightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val pageSizeValue = pageSize
        val pageWidth = pageSizeValue?.width?.coerceAtLeast(1) ?: 1
        val pageHeight = pageSizeValue?.height?.coerceAtLeast(1) ?: 1
        val pageAspect = if (pageSizeValue != null && pageWidth > 0) {
            pageHeight.toFloat() / pageWidth.toFloat()
        } else {
            (heightPx / widthPx).coerceAtLeast(1f)
        }
        val pageHeightPx = widthPx * pageAspect
        val baseScale = if (pageSizeValue != null && pageWidth > 0) {
            widthPx / pageWidth.toFloat()
        } else {
            1f
        }
        val effectiveScale = (baseScale * scale).coerceAtLeast(1f)
        val tileGrid = determineTileGrid(scale, density, widthPx, pageHeightPx)
        val tileFractions = remember(pageIndex, tileGrid) {
            buildList {
                val widthStep = 1f / tileGrid.columns
                val heightStep = 1f / tileGrid.rows
                for (row in 0 until tileGrid.rows) {
                    for (col in 0 until tileGrid.columns) {
                        val left = col * widthStep
                        val top = row * heightStep
                        val right = if (col == tileGrid.columns - 1) 1f else (col + 1) * widthStep
                        val bottom = if (row == tileGrid.rows - 1) 1f else (row + 1) * heightStep
                        add(RectF(left, top, right, bottom))
                    }
                }
            }
        }
        val tileInfos = if (pageSizeValue == null) {
            emptyList()
        } else {
            tileFractions.map { fraction ->
                val left = (fraction.left * pageWidth).toInt().coerceIn(0, pageWidth - 1)
                val top = (fraction.top * pageHeight).toInt().coerceIn(0, pageHeight - 1)
                val right = (fraction.right * pageWidth).roundToInt().coerceIn(left + 1, pageWidth)
                val bottom = (fraction.bottom * pageHeight).roundToInt().coerceIn(top + 1, pageHeight)
                val rect = Rect(left, top, right, bottom)
                PageTileKey(left, top, right, bottom, effectiveScale.toBits()) to rect
            }
        }

        fun applyTranslationDelta(deltaX: Float, deltaY: Float): Offset {
            val maxTranslationX = ((scale - 1f) * widthPx) / 2f
            val maxTranslationY = ((scale - 1f) * pageHeightPx) / 2f
            val newX = (translation.x + deltaX).coerceIn(-maxTranslationX, maxTranslationX)
            val newY = (translation.y + deltaY).coerceIn(-maxTranslationY, maxTranslationY)
            val applied = Offset(newX - translation.x, newY - translation.y)
            translation = Offset(newX, newY)
            return applied
        }

        fun consumeVerticalScroll(delta: Float): Float {
            if (scale <= 1.01f) return 0f
            val multiplier = 0.6f * swipeSensitivity
            if (multiplier == 0f || delta == 0f) return 0f
            val applied = applyTranslationDelta(0f, -delta * multiplier)
            return if (applied.y == 0f) 0f else -applied.y / multiplier
        }

        gesturePipeline.updateSensitivity(swipeSensitivity)
        gesturePipeline.updatePageBounds(widthPx, pageHeightPx)
        gesturePipeline.onScaleListener = { delta ->
            scale = (scale * delta).coerceIn(1f, 4f)
            if (scale <= 1.01f) {
                translation = Offset.Zero
            }
        }
        gesturePipeline.onPanListener = { dx, dy ->
            val multiplier = 0.6f * swipeSensitivity
            applyTranslationDelta(-dx * multiplier, -dy * multiplier)
        }
        gesturePipeline.onFlingListener = { direction, velocity ->
            latestFling(direction, velocity * swipeSensitivity)
        }

        val nestedScrollConnection = remember(pageIndex, swipeSensitivity) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val consumedY = consumeVerticalScroll(available.y)
                    return if (consumedY == 0f) Offset.Zero else Offset(x = 0f, y = consumedY)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset {
                    val consumedY = consumeVerticalScroll(available.y)
                    return if (consumedY == 0f) Offset.Zero else Offset(x = 0f, y = consumedY)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return if (scale > 1.01f) Velocity(0f, available.y) else Velocity.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return if (scale > 1.01f) Velocity(0f, available.y) else Velocity.Zero
                }
            }
        }

        LaunchedEffect(pageIndex, tileGrid, effectiveScale, pageSizeValue) {
            if (pageSizeValue == null) return@LaunchedEffect
            latestTileSpec(TilePreloadSpec(pageIndex, tileFractions, effectiveScale))
            val currentKeys = tileInfos.map { it.first }.toSet()
            val staleKeys = tileBitmaps.keys.toList().filter { it !in currentKeys }
            staleKeys.forEach { key ->
                tileBitmaps.remove(key)?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            }
            tileInfos.forEach { (key, rect) ->
                val cached = tileBitmaps[key]
                if (cached == null || cached.isRecycled) {
                    repeat(2) {
                        val bitmap = latestRenderTile(pageIndex, rect, effectiveScale)
                        if (bitmap != null) {
                            tileBitmaps[key] = bitmap
                            return@repeat
                        }
                        delay(32)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .pointerInteropFilter { event ->
                    gesturePipeline.onTouchEvent(event, scale)
                },
            contentAlignment = Alignment.Center
        ) {
            if (pageSizeValue != null) {
                Canvas(
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = translation.x
                            translationY = translation.y
                        }
                        .fillMaxWidth()
                        .aspectRatio(pageAspect)
                ) {
                    val drawScale = if (pageWidth > 0) size.width / pageWidth.toFloat() else 1f
                    tileInfos.forEach { (key, rect) ->
                        val bitmap = tileBitmaps[key]
                        if (bitmap != null && !bitmap.isRecycled) {
                            val destLeft = rect.left * drawScale
                            val destTop = rect.top * drawScale
                            val destWidth = (rect.width().toFloat() * drawScale).coerceAtLeast(1f)
                            val destHeight = (rect.height().toFloat() * drawScale).coerceAtLeast(1f)
                            drawImage(
                                image = bitmap.asImageBitmap(),
                                dstOffset = IntOffset(destLeft.roundToInt(), destTop.roundToInt()),
                                dstSize = IntSize(destWidth.roundToInt(), destHeight.roundToInt())
                            )
                        }
                    }
                }

                val sharedModifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translation.x
                        translationY = translation.y
                    }
                    .fillMaxWidth()
                    .aspectRatio(pageAspect)

                SearchHighlightOverlay(
                    modifier = sharedModifier,
                    matches = state.searchResults.firstOrNull { it.pageIndex == pageIndex }?.matches.orEmpty()
                )

                AnnotationOverlay(
                    modifier = sharedModifier,
                    pageIndex = pageIndex,
                    annotations = state.activeAnnotations.filterIsInstance<AnnotationCommand.Stroke>()
                        .filter { it.pageIndex == pageIndex },
                    onStrokeComplete = { points ->
                        val command = AnnotationCommand.Stroke(
                            pageIndex = pageIndex,
                            points = points.map { com.novapdf.reader.model.PointSnapshot(it.x, it.y) },
                            color = 0xFFFF4081,
                            strokeWidth = 4f
                        )
                        onStrokeFinished(command)
                    }
                )
            }
        }
    }
}

