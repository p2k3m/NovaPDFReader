package com.novapdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Size
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.novapdf.reader.R
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.TilePreloadSpec
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
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
    renderTile: suspend (Int, Rect, Float) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    onTileSpecChanged: (TilePreloadSpec) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleHighContrast: (Boolean) -> Unit,
    dynamicColorSupported: Boolean
) {
    var searchQuery by remember { mutableStateOf("") }
    val pagerAdapter = remember(onStrokeFinished, renderTile, requestPageSize, onTileSpecChanged) {
        PdfPagerAdapter(
            onStrokeFinished = onStrokeFinished,
            renderTile = renderTile,
            requestPageSize = requestPageSize,
            onTileSpecChanged = onTileSpecChanged
        )
    }
    val viewPagerHolder = remember { ViewPagerHolder() }
    val physicsInterpolator = remember { PhysicsBasedInterpolator() }

    val latestOnPageChange by rememberUpdatedState(onPageChange)

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
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        ViewPager2(context).apply {
                            clipToPadding = false
                            clipChildren = false
                            offscreenPageLimit = 1
                            adapter = pagerAdapter
                            viewPagerHolder.viewPager = this
                            val recyclerView = getChildAt(0) as? RecyclerView
                            recyclerView?.let { child ->
                                child.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
                                child.applyPhysicsInterpolator(physicsInterpolator)
                                viewPagerHolder.recyclerView = child
                            }
                            pagerAdapter.attachPagerCallbacks(
                                onPageFling = { pageIndex, direction, velocity ->
                                    val recyclerView = viewPagerHolder.recyclerView
                                    val width = recyclerView?.width ?: width
                                    val target = (pageIndex + direction).coerceIn(0, pagerAdapter.itemCount - 1)
                                    if (target != pageIndex) {
                                        if (width > 0) {
                                            recyclerView?.smoothScrollBy(
                                                direction * width,
                                                0,
                                                physicsInterpolator,
                                                physicsInterpolator.durationForVelocity(abs(velocity))
                                            )
                                        }
                                        setCurrentItem(target, width == 0)
                                    }
                                }
                            )
                            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                                override fun onPageSelected(position: Int) {
                                    super.onPageSelected(position)
                                    viewPagerHolder.onPageSelected?.invoke(position)
                                }
                            }.also { callback ->
                                viewPagerHolder.pageChangeCallback = callback
                            })
                        }
                    },
                    update = { pager ->
                        if (pager.adapter !== pagerAdapter) {
                            pager.adapter = pagerAdapter
                        }
                        pagerAdapter.updateState(state)
                        pagerAdapter.attachPagerCallbacks { pageIndex, direction, velocity ->
                            val recyclerView = viewPagerHolder.recyclerView
                            val width = recyclerView?.width ?: pager.width
                            val target = (pageIndex + direction).coerceIn(0, pagerAdapter.itemCount - 1)
                            if (target != pageIndex) {
                                if (width > 0) {
                                    recyclerView?.smoothScrollBy(
                                        direction * width,
                                        0,
                                        physicsInterpolator,
                                        physicsInterpolator.durationForVelocity(abs(velocity))
                                    )
                                }
                                pager.setCurrentItem(target, width == 0)
                            }
                        }
                        if (pagerAdapter.itemCount > 0) {
                            val target = state.currentPage.coerceIn(0, pagerAdapter.itemCount - 1)
                            if (pager.currentItem != target) {
                                pager.setCurrentItem(target, false)
                            }
                        }
                        val recyclerView = pager.getChildAt(0) as? RecyclerView
                        if (recyclerView != null && recyclerView !== viewPagerHolder.recyclerView) {
                            recyclerView.overScrollMode = RecyclerView.OVER_SCROLL_NEVER
                            recyclerView.applyPhysicsInterpolator(physicsInterpolator)
                            viewPagerHolder.recyclerView = recyclerView
                        }
                    }
                )
                SideEffect {
                    viewPagerHolder.onPageSelected = latestOnPageChange
                }
                DisposableEffect(Unit) {
                    onDispose {
                        viewPagerHolder.detach()
                    }
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

private class ViewPagerHolder {
    var viewPager: ViewPager2? = null
    var recyclerView: RecyclerView? = null
    var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    var onPageSelected: ((Int) -> Unit)? = null

    fun detach() {
        viewPager?.let { pager ->
            pageChangeCallback?.let { pager.unregisterOnPageChangeCallback(it) }
        }
        pageChangeCallback = null
        recyclerView = null
        viewPager = null
        onPageSelected = null
    }
}

private class PdfPagerAdapter(
    private val onStrokeFinished: (AnnotationCommand) -> Unit,
    private val renderTile: suspend (Int, Rect, Float) -> Bitmap?,
    private val requestPageSize: suspend (Int) -> Size?,
    private val onTileSpecChanged: (TilePreloadSpec) -> Unit
) : RecyclerView.Adapter<PdfPagerAdapter.PageViewHolder>() {
    private var uiState: PdfViewerUiState = PdfViewerUiState()
    private var onPageFling: (Int, Int, Float) -> Unit = { _, _, _ -> }

    fun updateState(state: PdfViewerUiState) {
        val previousCount = uiState.pageCount
        uiState = state
        if (previousCount != state.pageCount) {
            notifyDataSetChanged()
        } else if (state.pageCount > 0) {
            notifyItemRangeChanged(0, state.pageCount)
        }
    }

    fun attachPagerCallbacks(onPageFling: (pageIndex: Int, direction: Int, velocity: Float) -> Unit) {
        this.onPageFling = onPageFling
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val composeView = ComposeView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        return PageViewHolder(composeView)
    }

    override fun getItemCount(): Int = uiState.pageCount

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(
            pageIndex = position,
            state = uiState,
            onStrokeFinished = onStrokeFinished,
            renderTile = renderTile,
            requestPageSize = requestPageSize,
            onTileSpecChanged = onTileSpecChanged,
            onRequestPageFling = { direction, velocity ->
                onPageFling(position, direction, velocity)
            }
        )
    }

    class PageViewHolder(private val composeView: ComposeView) : RecyclerView.ViewHolder(composeView) {
        private val pageIndexState = mutableIntStateOf(0)
        private val stateState = mutableStateOf(PdfViewerUiState())
        private val strokeCallbackState = mutableStateOf<(AnnotationCommand) -> Unit>({})
        private val renderRequestState = mutableStateOf<suspend (Int, Rect, Float) -> Bitmap?>({ _, _, _ -> null })
        private val pageSizeRequestState = mutableStateOf<suspend (Int) -> Size?>({ _ -> null })
        private val tileSpecCallbackState = mutableStateOf<(TilePreloadSpec) -> Unit>({})
        private val flingRequestState = mutableStateOf<(Int, Float) -> Unit>({ _, _ -> })

        init {
            composeView.setContent {
                val state = stateState.value
                PdfPageContainer(
                    pageIndex = pageIndexState.intValue,
                    state = state,
                    swipeSensitivity = state.swipeSensitivity,
                    onStrokeFinished = { command ->
                        strokeCallbackState.value(command)
                    },
                    renderTile = renderRequestState.value,
                    requestPageSize = pageSizeRequestState.value,
                    onTileSpecChanged = tileSpecCallbackState.value,
                    onRequestPageFling = { direction, velocity ->
                        flingRequestState.value(direction, velocity)
                    }
                )
            }
        }

        fun bind(
            pageIndex: Int,
            state: PdfViewerUiState,
            onStrokeFinished: (AnnotationCommand) -> Unit,
            renderTile: suspend (Int, Rect, Float) -> Bitmap?,
            requestPageSize: suspend (Int) -> Size?,
            onTileSpecChanged: (TilePreloadSpec) -> Unit,
            onRequestPageFling: (direction: Int, velocity: Float) -> Unit
        ) {
            pageIndexState.intValue = pageIndex
            stateState.value = state
            strokeCallbackState.value = onStrokeFinished
            renderRequestState.value = renderTile
            pageSizeRequestState.value = requestPageSize
            tileSpecCallbackState.value = onTileSpecChanged
            flingRequestState.value = onRequestPageFling
        }
    }
}

private fun determineTilesPerAxis(scale: Float, density: Density, widthPx: Float, heightPx: Float): Int {
    val densityScale = density.density.coerceAtLeast(0.5f)
    val longestDimension = max(widthPx, heightPx) * scale
    val normalized = longestDimension / (densityScale * 640f)
    return when {
        normalized < 1.2f -> 2
        normalized < 2.2f -> 3
        else -> 4
    }
}

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

private class PhysicsBasedInterpolator : Interpolator {
    private val curve: Interpolator = PathInterpolator(0.16f, 0f, 0.1f, 1f)

    override fun getInterpolation(input: Float): Float {
        val clamped = input.coerceIn(0f, 1f)
        val damped = 1f - exp(-4.5f * clamped)
        return curve.getInterpolation(damped.coerceIn(0f, 1f))
    }

    fun durationForVelocity(velocity: Float): Int {
        if (velocity <= 0f) return DEFAULT_DURATION_MS
        val normalized = (velocity / 2200f).coerceIn(0.4f, 2.4f)
        return (DEFAULT_DURATION_MS / normalized).toInt().coerceIn(180, 420)
    }

    companion object {
        private const val DEFAULT_DURATION_MS = 320
    }
}

private fun RecyclerView.applyPhysicsInterpolator(interpolator: Interpolator) {
    runCatching {
        val viewFlingerField = RecyclerView::class.java.getDeclaredField("mViewFlinger").apply { isAccessible = true }
        val viewFlinger = viewFlingerField.get(this)
        val scrollerField = viewFlinger.javaClass.getDeclaredField("mScroller").apply { isAccessible = true }
        val overScroller = OverScroller(context, interpolator)
        scrollerField.set(viewFlinger, overScroller)
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
        val tilesPerAxis = determineTilesPerAxis(scale, density, widthPx, pageHeightPx)
        val tileFractions = remember(pageIndex, tilesPerAxis) {
            buildList {
                val step = 1f / tilesPerAxis
                for (row in 0 until tilesPerAxis) {
                    for (col in 0 until tilesPerAxis) {
                        val left = col * step
                        val top = row * step
                        val right = if (col == tilesPerAxis - 1) 1f else (col + 1) * step
                        val bottom = if (row == tilesPerAxis - 1) 1f else (row + 1) * step
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

        gesturePipeline.updateSensitivity(swipeSensitivity)
        gesturePipeline.updatePageBounds(widthPx, pageHeightPx)
        gesturePipeline.onScaleListener = { delta ->
            scale = (scale * delta).coerceIn(1f, 4f)
            if (scale <= 1.01f) {
                translation = Offset.Zero
            }
        }
        gesturePipeline.onPanListener = { dx, dy ->
            val panMultiplier = 0.6f * swipeSensitivity
            val updated = translation + Offset(-dx * panMultiplier, -dy * panMultiplier)
            val maxTranslationX = ((scale - 1f) * widthPx) / 2f
            val maxTranslationY = ((scale - 1f) * pageHeightPx) / 2f
            translation = Offset(
                x = updated.x.coerceIn(-maxTranslationX, maxTranslationX),
                y = updated.y.coerceIn(-maxTranslationY, maxTranslationY)
            )
        }
        gesturePipeline.onFlingListener = { direction, velocity ->
            latestFling(direction, velocity * swipeSensitivity)
        }

        LaunchedEffect(pageIndex, tilesPerAxis, effectiveScale, pageSizeValue) {
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

