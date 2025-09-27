package com.novapdf.reader

import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PdfOutlineNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
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
        renderPage = { index, width -> viewModel.renderPage(index, width) },
        requestPageSize = { viewModel.pageSize(it) },
        onViewportWidthChanged = { viewModel.updateViewportWidth(it) },
        onPrefetchPages = { indices, width -> viewModel.prefetchPages(indices, width) },
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
    renderPage: suspend (Int, Int) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    onViewportWidthChanged: (Int) -> Unit,
    onPrefetchPages: (List<Int>, Int) -> Unit,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
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
                        renderPage = renderPage,
                        requestPageSize = requestPageSize,
                        onViewportWidthChanged = onViewportWidthChanged,
                        onPrefetchPages = onPrefetchPages
                    )
                }
            }

            if (state.isLoading) {
                LoadingOverlay(progress = state.loadingProgress)
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
private fun LoadingOverlay(progress: Float?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.7f)
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(id = R.string.loading_document),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleMedium
            )
            progress?.let {
                val clamped = it.coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = clamped,
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        id = R.string.loading_progress,
                        (clamped * 100).roundToInt()
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultsCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = stringResource(id = R.string.search_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(id = R.string.search_hint)
                )
            },
            singleLine = true
        )
        if (query.isNotBlank()) {
            Text(
                text = stringResource(id = R.string.search_results_count, resultsCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun AdaptiveFlowStatusRow(
    state: PdfViewerUiState,
    onPlaySummary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            )
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.adaptive_flow_summary_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        val isActive = state.readingSpeed > 0f
        val description = if (isActive) {
            stringResource(
                id = R.string.adaptive_flow_summary_description,
                state.readingSpeed,
                state.swipeSensitivity
            )
        } else {
            stringResource(id = R.string.adaptive_flow_summary_unavailable)
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onPlaySummary, enabled = isActive) {
            Icon(
                imageVector = Icons.Outlined.Brightness7,
                contentDescription = null,
                tint = Color.Unspecified
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(id = R.string.adaptive_flow_summary_button))
        }
    }
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
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            )
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.accessibility_settings_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.dynamic_color_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(id = R.string.dynamic_color_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!dynamicColorSupported) {
                    Text(
                        text = stringResource(id = R.string.dynamic_color_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = dynamicColorEnabled && dynamicColorSupported,
                onCheckedChange = {
                    if (dynamicColorSupported) {
                        onDynamicColorChanged(it)
                    }
                },
                enabled = dynamicColorSupported
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = R.string.high_contrast_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(id = R.string.high_contrast_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = highContrastEnabled && dynamicColorEnabled,
                onCheckedChange = { onHighContrastChanged(it) },
                enabled = dynamicColorEnabled
            )
        }
    }
}

@Composable
private fun EmptyState(onOpenDocument: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.FileOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(64.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(id = R.string.empty_state_message),
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenDocument) {
            Text(text = stringResource(id = R.string.empty_state_button))
        }
    }
}

@Composable
private fun SearchHighlightOverlay(
    modifier: Modifier,
    matches: List<com.novapdf.reader.model.SearchMatch>
) {
    if (matches.isEmpty()) return
    val highlight = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
    Canvas(modifier = modifier) {
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
@Composable
private fun PdfPager(
    modifier: Modifier,
    state: PdfViewerUiState,
    onPageChange: (Int) -> Unit,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    renderPage: suspend (Int, Int) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    onViewportWidthChanged: (Int) -> Unit,
    onPrefetchPages: (List<Int>, Int) -> Unit
) {
    val lazyListState = rememberLazyListState()
    val latestOnPageChange by rememberUpdatedState(onPageChange)
    val latestRenderPage by rememberUpdatedState(renderPage)
    val latestRequestPageSize by rememberUpdatedState(requestPageSize)
    val latestStrokeFinished by rememberUpdatedState(onStrokeFinished)
    val latestViewportWidth by rememberUpdatedState(onViewportWidthChanged)
    val latestPrefetch by rememberUpdatedState(onPrefetchPages)

    LaunchedEffect(state.pageCount, state.currentPage) {
        if (state.pageCount <= 0) return@LaunchedEffect
        val target = state.currentPage.coerceIn(0, state.pageCount - 1)
        if (lazyListState.firstVisibleItemIndex != target || lazyListState.firstVisibleItemScrollOffset != 0) {
            lazyListState.scrollToItem(target)
        }
    }

    LaunchedEffect(lazyListState, state.pageCount) {
        if (state.pageCount <= 0) return@LaunchedEffect
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { latestOnPageChange(it) }
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.viewportEndOffset - lazyListState.layoutInfo.viewportStartOffset
        }
            .distinctUntilChanged()
            .collect { width ->
                if (width > 0) {
                    latestViewportWidth(width)
                }
            }
    }

    LaunchedEffect(lazyListState, state.pageCount) {
        if (state.pageCount <= 0) return@LaunchedEffect
        var lastIndex = lazyListState.firstVisibleItemIndex
        var lastOffset = lazyListState.firstVisibleItemScrollOffset
        var lastTime = System.currentTimeMillis()
        snapshotFlow {
            val info = lazyListState.layoutInfo
            PrefetchSnapshot(
                firstVisible = info.visibleItemsInfo.firstOrNull()?.index
                    ?: lazyListState.firstVisibleItemIndex,
                firstOffset = lazyListState.firstVisibleItemScrollOffset,
                viewportWidth = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0),
                firstItemSize = info.visibleItemsInfo.firstOrNull()?.size ?: 0,
                isScrolling = lazyListState.isScrollInProgress
            )
        }.collect { snapshot ->
            val now = System.currentTimeMillis()
            val dt = now - lastTime
            val deltaIndex = snapshot.firstVisible - lastIndex
            val deltaOffset = snapshot.firstOffset - lastOffset
            val direction = when {
                deltaIndex > 0 || (deltaIndex == 0 && deltaOffset > 0) -> 1
                deltaIndex < 0 || (deltaIndex == 0 && deltaOffset < 0) -> -1
                else -> 0
            }
            if (direction != 0 && dt > 0 && snapshot.viewportWidth > 0 && snapshot.isScrolling) {
                val itemSize = snapshot.firstItemSize.takeIf { it > 0 } ?: snapshot.viewportWidth
                val deltaPages = deltaIndex + (deltaOffset.toFloat() / itemSize.toFloat())
                val velocity = kotlin.math.abs(deltaPages * 1000f / dt.toFloat())
                val prefetchDistance = if (velocity > 1.2f) 3 else 2
                val targets = (1..prefetchDistance).mapNotNull { step ->
                    val candidate = snapshot.firstVisible + direction * step
                    candidate.takeIf { candidate in 0 until state.pageCount }
                }
                if (targets.isNotEmpty()) {
                    latestPrefetch(targets, snapshot.viewportWidth)
                }
            }
            lastIndex = snapshot.firstVisible
            lastOffset = snapshot.firstOffset
            lastTime = now
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 24.dp)
    ) {
        items(state.pageCount, key = { it }) { pageIndex ->
            PdfPageItem(
                pageIndex = pageIndex,
                state = state,
                renderPage = latestRenderPage,
                requestPageSize = latestRequestPageSize,
                onStrokeFinished = latestStrokeFinished
            )
        }
    }
}

private data class PrefetchSnapshot(
    val firstVisible: Int,
    val firstOffset: Int,
    val viewportWidth: Int,
    val firstItemSize: Int,
    val isScrolling: Boolean
)

@Composable
private fun PdfPageItem(
    pageIndex: Int,
    state: PdfViewerUiState,
    renderPage: suspend (Int, Int) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    onStrokeFinished: (AnnotationCommand) -> Unit
) {
    val density = LocalDensity.current
    val latestRender by rememberUpdatedState(renderPage)
    val latestRequest by rememberUpdatedState(requestPageSize)
    val latestStroke by rememberUpdatedState(onStrokeFinished)
    var pageBitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var pageSize by remember(pageIndex) { mutableStateOf<Size?>(null) }
    var isLoading by remember(pageIndex) { mutableStateOf(false) }

    DisposableEffect(pageIndex) {
        onDispose {
            pageBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            pageBitmap = null
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        val widthPx = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        LaunchedEffect(state.documentId, pageIndex, widthPx) {
            if (state.documentId == null) {
                pageBitmap?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
                pageBitmap = null
                pageSize = null
                return@LaunchedEffect
            }
            if (widthPx <= 0) return@LaunchedEffect
            isLoading = true
            pageSize = latestRequest(pageIndex)
            val rendered = latestRender(pageIndex, widthPx)
            val previous = pageBitmap
            pageBitmap = rendered
            if (previous != null && previous != rendered && !previous.isRecycled) {
                previous.recycle()
            }
            isLoading = false
        }

        val aspect = pageBitmap?.let { bitmap ->
            bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
        } ?: pageSize?.let { size ->
            size.height.toFloat() / size.width.coerceAtLeast(1)
        } ?: 1.4142f

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.2f, 5f))
        ) {
            val bitmap = pageBitmap
            if (bitmap != null && !bitmap.isRecycled) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            SearchHighlightOverlay(
                modifier = Modifier.fillMaxSize(),
                matches = state.searchResults.firstOrNull { it.pageIndex == pageIndex }?.matches.orEmpty()
            )

            AnnotationOverlay(
                modifier = Modifier.fillMaxSize(),
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
                    latestStroke(command)
                }
            )
        }
    }
}