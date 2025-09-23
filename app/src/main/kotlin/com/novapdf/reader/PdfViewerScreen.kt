package com.novapdf.reader

import android.graphics.Bitmap
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novapdf.reader.model.AnnotationCommand
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerRoute(
    viewModel: PdfViewerViewModel,
    snackbarHost: SnackbarHostState,
    onOpenDocument: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PdfViewerScreen(
        state = uiState,
        snackbarHost = snackbarHost,
        onOpenDocument = onOpenDocument,
        onPageChange = { viewModel.onPageFocused(it) },
        onStrokeFinished = { viewModel.addAnnotation(it) },
        onSaveAnnotations = { viewModel.persistAnnotations() },
        onSearch = { viewModel.search(it) },
        onToggleBookmark = { viewModel.toggleBookmark() },
        renderPage = { index, size, scale -> viewModel.renderPage(index, size, scale) }
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
    renderPage: suspend (Int, Size, Float) -> Bitmap?
) {
    val pagerState = rememberPagerState(initialPage = state.currentPage, pageCount = { maxOf(state.pageCount, 1) })
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(state.currentPage) {
        if (state.currentPage != pagerState.currentPage) {
            pagerState.animateScrollToPage(state.currentPage)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        onPageChange(pagerState.currentPage)
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
                    IconButton(onClick = onToggleBookmark, enabled = state.documentId != null) {
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

            AdaptiveFlowStatusRow(state)

            if (state.pageCount == 0) {
                EmptyState(onOpenDocument)
            } else {
                HorizontalPager(
                    modifier = Modifier.fillMaxSize(),
                    state = pagerState,
                    contentPadding = PaddingValues(24.dp)
                ) { pageIndex ->
                    LaunchedEffect(pageIndex) {
                        onPageChange(pageIndex)
                    }
                    PdfPageContainer(
                        pageIndex = pageIndex,
                        state = state,
                        onStrokeFinished = onStrokeFinished,
                        renderPage = renderPage,
                        searchResults = state.searchResults
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchHighlightOverlay(
    modifier: Modifier,
    matches: List<com.novapdf.reader.model.SearchMatch>
) {
    if (matches.isEmpty()) return
    androidx.compose.foundation.Canvas(modifier = modifier) {
        matches.flatMap { it.boundingBoxes }.forEach { rect ->
            drawRect(
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                topLeft = Offset(rect.left * size.width, rect.top * size.height),
                size = androidx.compose.ui.geometry.Size(
                    (rect.right - rect.left) * size.width,
                    (rect.bottom - rect.top) * size.height
                )
            )
        }
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
private fun AdaptiveFlowStatusRow(state: PdfViewerUiState) {
    val status = if (state.swipeSensitivity > 1.2f) "Adaptive Flow Active" else "Adaptive Flow Ready"
    val icon = if (state.isNightMode) Icons.Outlined.Brightness4 else Icons.Outlined.Brightness7
    AssistChip(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
        onClick = { },
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
private fun PdfPageContainer(
    pageIndex: Int,
    state: PdfViewerUiState,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    renderPage: suspend (Int, Size, Float) -> Bitmap?,
    searchResults: List<com.novapdf.reader.model.SearchResult>
) {
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(1f) }
    var translation by remember { mutableStateOf(Offset.Zero) }
    var bitmapState by remember { mutableStateOf<Bitmap?>(null) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    translation += pan
                }
            }
    ) {
        val widthPx = with(density) { maxWidth.toPx().roundToInt().coerceAtLeast(1) }
        val heightPx = with(density) { maxHeight.toPx().roundToInt().coerceAtLeast(1) }

        LaunchedEffect(pageIndex, widthPx, heightPx, scale) {
            repeat(3) {
                val bitmap = renderPage(pageIndex, Size(widthPx, (heightPx * scale).roundToInt().coerceAtLeast(1)), scale)
                if (bitmap != null) {
                    bitmapState = bitmap
                    return@LaunchedEffect
                }
                delay(32)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            bitmapState?.let { bitmap ->
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "PDF page",
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = translation.x
                            translationY = translation.y
                        }
                        .fillMaxWidth()
                )
            }

            SearchHighlightOverlay(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translation.x
                        translationY = translation.y
                    }
                    .fillMaxSize(),
                matches = searchResults.firstOrNull { it.pageIndex == pageIndex }?.matches.orEmpty()
            )

            AnnotationOverlay(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translation.x
                        translationY = translation.y
                    }
                    .fillMaxSize(),
                pageIndex = pageIndex,
                annotations = state.activeAnnotations.filterIsInstance<AnnotationCommand.Stroke>().filter { it.pageIndex == pageIndex },
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
