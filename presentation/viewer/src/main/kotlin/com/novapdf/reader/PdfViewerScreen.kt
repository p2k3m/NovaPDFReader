package com.novapdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import android.util.Patterns
import android.text.format.Formatter as AndroidFormatter
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import android.text.format.Formatter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.novapdf.reader.presentation.viewer.BuildConfig
import com.novapdf.reader.presentation.viewer.R
import com.novapdf.reader.ui.automation.UiAutomatorTags
import com.novapdf.reader.DocumentStatus
import com.novapdf.reader.model.DocumentSource
import com.novapdf.reader.accessibility.HapticFeedbackManager
import com.novapdf.reader.accessibility.rememberHapticFeedbackManager
import com.novapdf.reader.features.annotations.AnnotationOverlay
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.BitmapMemoryLevel
import com.novapdf.reader.model.BitmapMemoryStats
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.novapdf.reader.model.SearchIndexingPhase
import com.novapdf.reader.model.SearchIndexingState
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ONBOARDING_PREFS = "nova_onboarding_prefs"
private const val ONBOARDING_COMPLETE_KEY = "onboarding_complete"
private val THUMBNAIL_WIDTH = 96.dp
private val THUMBNAIL_HEIGHT = 128.dp
private const val VIEWPORT_WIDTH_DEBOUNCE_MS = 120L

@Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerRoute(
    viewModel: PdfViewerViewModel,
    snackbarHost: SnackbarHostState,
    onOpenLocalDocument: () -> Unit,
    onOpenCloudDocument: () -> Unit,
    onOpenLastDocument: () -> Unit,
    onOpenRemoteDocument: (DocumentSource) -> Unit,
    onDismissError: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
    ) {
        PdfViewerScreen(
            state = uiState,
            snackbarHost = snackbarHost,
            messageFlow = viewModel.messageEvents,
            onOpenLocalDocument = onOpenLocalDocument,
            onOpenCloudDocument = onOpenCloudDocument,
            onOpenLastDocument = onOpenLastDocument,
            onOpenRemoteDocument = onOpenRemoteDocument,
            onDismissError = onDismissError,
            onConfirmLargeDownload = { viewModel.confirmLargeRemoteDownload() },
            onDismissLargeDownload = { viewModel.dismissLargeRemoteDownload() },
            onPageChange = { viewModel.onPageFocused(it) },
            onPageCommit = { viewModel.onPageSettled(it) },
            onStrokeFinished = { viewModel.addAnnotation(it) },
            onSaveAnnotations = { viewModel.persistAnnotations() },
            onSearch = { viewModel.search(it) },
            onCancelIndexing = { viewModel.cancelIndexing() },
            onToggleBookmark = { page -> viewModel.toggleBookmark(page) },
            onOutlineDestinationSelected = { viewModel.jumpToPage(it) },
            onExportDocument = { viewModel.exportDocument(context) },
            renderPage = { index, width, priority ->
                viewModel.renderPage(index, width, priority)
            },
            requestPageSize = { viewModel.pageSize(it) },
            onViewportWidthChanged = { viewModel.updateViewportWidth(it) },
            onPrefetchPages = { indices, width -> viewModel.prefetchPages(indices, width) },
            onToggleDynamicColor = { viewModel.setDynamicColorEnabled(it) },
            onToggleHighContrast = { viewModel.setHighContrastEnabled(it) },
            onToggleTalkBackIntegration = { viewModel.setTalkBackIntegrationEnabled(it) },
            onFontScaleChanged = { viewModel.setFontScale(it) },
            onDumpDiagnostics = { viewModel.dumpRuntimeDiagnostics() },
            onToggleDevDiagnostics = { viewModel.setDevDiagnosticsEnabled(it) },
            onToggleDevCaches = { viewModel.setDevCachesEnabled(it) },
            onToggleDevArtificialDelay = { viewModel.setDevArtificialDelayEnabled(it) },
            renderDispatcher = viewModel.renderThreadDispatcher,
            dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    state: PdfViewerUiState,
    snackbarHost: SnackbarHostState,
    messageFlow: Flow<UiMessage>,
    onOpenLocalDocument: () -> Unit,
    onOpenCloudDocument: () -> Unit,
    onOpenLastDocument: () -> Unit,
    onOpenRemoteDocument: (DocumentSource) -> Unit,
    onDismissError: () -> Unit,
    onConfirmLargeDownload: () -> Unit,
    onDismissLargeDownload: () -> Unit,
    onPageChange: (Int) -> Unit,
    onPageCommit: (Int) -> Unit,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    onSaveAnnotations: () -> Unit,
    onSearch: (String) -> Unit,
    onCancelIndexing: () -> Unit,
    onToggleBookmark: (Int) -> Unit,
    onOutlineDestinationSelected: (Int) -> Unit,
    onExportDocument: () -> Boolean,
    renderPage: suspend (Int, Int, RenderWorkQueue.Priority) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    onViewportWidthChanged: (Int) -> Unit,
    onPrefetchPages: (List<Int>, Int) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleHighContrast: (Boolean) -> Unit,
    onToggleTalkBackIntegration: (Boolean) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    onDumpDiagnostics: () -> Unit,
    onToggleDevDiagnostics: (Boolean) -> Unit,
    onToggleDevCaches: (Boolean) -> Unit,
    onToggleDevArtificialDelay: (Boolean) -> Unit,
    renderDispatcher: CoroutineDispatcher,
    dynamicColorSupported: Boolean
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    val requestDocument: () -> Unit = { showSourceDialog = true }
    val latestOnPageChange by rememberUpdatedState(onPageChange)
    val latestOnPageCommit by rememberUpdatedState(onPageCommit)
    val baseDensity = LocalDensity.current
    val adjustedDensity = remember(baseDensity, state.fontScale) {
        Density(density = baseDensity.density, fontScale = state.fontScale)
    }

    var firstInteractiveFramePassed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        firstInteractiveFramePassed = true
    }
    val layoutAnimationsEnabled = firstInteractiveFramePassed && !state.uiUnderLoad

    CompositionLocalProvider(LocalDensity provides adjustedDensity) {
        val context = LocalContext.current
        val hapticFeedback = LocalHapticFeedback.current
        val accessibilityManager = remember(context) {
            (context.applicationContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager)
        }
        val accessibilityAnnouncementsEnabled = remember(state.talkBackIntegrationEnabled, accessibilityManager) {
            state.talkBackIntegrationEnabled && accessibilityManager?.isEnabled == true
        }
        MessageFlowHandler(
            messageFlow = messageFlow,
            snackbarHost = snackbarHost,
            accessibilityManager = accessibilityManager
        )
        val hapticManager = rememberHapticFeedbackManager()
        val echoModeController = remember(context.applicationContext) {
            EchoModeController(context.applicationContext)
        }
        val fallbackHaptics by rememberUpdatedState(newValue = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        })
        val coroutineScope = rememberCoroutineScope()
        var showOutlineSheet by remember { mutableStateOf(false) }
        var showAccessibilitySheet by remember { mutableStateOf(false) }
        val exportErrorMessage = stringResource(id = R.string.export_failed)
        var selectedDestination by rememberSaveable { mutableStateOf(MainDestination.Reader) }
        val onboardingPreferences = remember(context) {
            context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)
        }
        var showOnboarding by rememberSaveable {
            mutableStateOf(!onboardingPreferences.getBoolean(ONBOARDING_COMPLETE_KEY, false))
        }
        val onboardingPages = remember {
            listOf(
                OnboardingPage(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    titleRes = R.string.onboarding_page_library_title,
                    descriptionRes = R.string.onboarding_page_library_description
                ),
                OnboardingPage(
                    icon = Icons.Outlined.Edit,
                    titleRes = R.string.onboarding_page_annotation_title,
                    descriptionRes = R.string.onboarding_page_annotation_description
                ),
                OnboardingPage(
                    icon = Icons.Filled.Search,
                    titleRes = R.string.onboarding_page_search_title,
                    descriptionRes = R.string.onboarding_page_search_description
                )
            )
        }
        var onboardingPageIndex by rememberSaveable { mutableIntStateOf(0) }
        val totalSearchResults by remember(state.searchResults) {
            derivedStateOf { state.searchResults.sumOf { it.matches.size } }
        }
        val searchFocusRequester = remember { FocusRequester() }
        var requestSearchFocus by remember { mutableStateOf(false) }
        val navigationItems = remember {
            listOf(
                NavigationItem(MainDestination.Home, Icons.Outlined.Home, R.string.navigation_home),
                NavigationItem(MainDestination.Reader, Icons.AutoMirrored.Outlined.MenuBook, R.string.navigation_reader),
                NavigationItem(MainDestination.Annotations, Icons.Outlined.Edit, R.string.navigation_annotations),
                NavigationItem(MainDestination.Settings, Icons.Outlined.Settings, R.string.navigation_settings)
            )
        }
        val echoSummaryStrings = remember(context) { ResourceEchoModeSummaryStrings(context.resources) }
        val echoSummary by remember(state, echoSummaryStrings) {
            derivedStateOf { state.echoSummary(echoSummaryStrings) }
        }
        val playAdaptiveSummary = remember(echoSummary, echoModeController, fallbackHaptics) {
            {
                val summary = echoSummary
                if (summary != null) {
                    echoModeController.speakSummary(summary) {
                        fallbackHaptics()
                    }
                } else {
                    fallbackHaptics()
                }
            }
        }
        val completeOnboarding = {
            onboardingPreferences.edit().putBoolean(ONBOARDING_COMPLETE_KEY, true).apply()
            onboardingPageIndex = 0
            showOnboarding = false
        }

        DisposableEffect(echoModeController) {
            onDispose { echoModeController.shutdown() }
        }

        val onExportDocumentClick = {
            val success = onExportDocument()
            if (!success) {
                coroutineScope.launch { snackbarHost.showSnackbar(exportErrorMessage) }
            }
        }
        val hasDocument = state.documentId != null
        val isCurrentPageBookmarked = state.bookmarks.contains(state.currentPage)
        val hasOutline = state.outline.isNotEmpty()
        val bottomBarDestination = if (selectedDestination == MainDestination.DevOptions) {
            MainDestination.Settings
        } else {
            selectedDestination
        }

        LaunchedEffect(accessibilityAnnouncementsEnabled, state.currentPage, state.pageCount) {
            if (!accessibilityAnnouncementsEnabled || state.pageCount <= 0) return@LaunchedEffect
            val message = context.getString(
                R.string.reader_page_announcement,
                (state.currentPage + 1).coerceAtLeast(1),
                state.pageCount
            )
            accessibilityManager.sendAnnouncement(message)
        }

        LaunchedEffect(accessibilityAnnouncementsEnabled, totalSearchResults, searchQuery) {
            if (!accessibilityAnnouncementsEnabled || searchQuery.isBlank()) return@LaunchedEffect
            val message = context.resources.getQuantityString(
                R.plurals.search_results_announcement,
                totalSearchResults,
                totalSearchResults
            )
            accessibilityManager.sendAnnouncement(message)
        }

        Scaffold(
            topBar = {
                PdfViewerTopBar(
                    selectedDestination = selectedDestination,
                    hasDocument = hasDocument,
                    isCurrentPageBookmarked = isCurrentPageBookmarked,
                    hasOutline = hasOutline,
                    onOpenDocument = { showSourceDialog = true },
                    onOpenAccessibilityOptions = { showAccessibilitySheet = true },
                    onSaveAnnotations = onSaveAnnotations,
                    onShowOutlineSheet = { showOutlineSheet = true },
                    onExportDocument = onExportDocumentClick,
                    onToggleBookmark = { onToggleBookmark(state.currentPage) }
                )
            },
            floatingActionButton = {
                PdfViewerSearchFab(
                    onClick = {
                        selectedDestination = MainDestination.Reader
                        requestSearchFocus = true
                    }
                )
            },
            floatingActionButtonPosition = FabPosition.End,
            bottomBar = {
                PdfViewerBottomNavigation(
                    items = navigationItems,
                    selectedDestination = bottomBarDestination,
                    onDestinationSelected = { selectedDestination = it }
                )
            },
            snackbarHost = { NovaSnackbarHost(snackbarHost) }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                PdfViewerDestinationContainer(
                    selectedDestination = selectedDestination,
                    state = state,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { query ->
                        searchQuery = query
                        onSearch(query)
                    },
                    totalSearchResults = totalSearchResults,
                    onOpenDocument = requestDocument,
                    onOpenLastDocument = onOpenLastDocument,
                    onPlayAdaptiveSummary = playAdaptiveSummary,
                    onOpenAccessibilityOptions = { showAccessibilitySheet = true },
                    focusRequester = searchFocusRequester,
                    requestFocus = requestSearchFocus,
                    onFocusHandled = { requestSearchFocus = false },
                    onPageChange = latestOnPageChange,
                    onPageCommit = latestOnPageCommit,
                    onStrokeFinished = onStrokeFinished,
                    onToggleBookmark = onToggleBookmark,
                    renderPage = renderPage,
                    requestPageSize = requestPageSize,
                    renderDispatcher = renderDispatcher,
                    onViewportWidthChanged = onViewportWidthChanged,
                    onPrefetchPages = onPrefetchPages,
                    onOpenDevOptions = { selectedDestination = MainDestination.DevOptions },
                    onBackFromDevOptions = { selectedDestination = MainDestination.Settings },
                    onDumpDiagnostics = onDumpDiagnostics,
                    onToggleDevDiagnostics = onToggleDevDiagnostics,
                    onToggleDevCaches = onToggleDevCaches,
                    onToggleDevArtificialDelay = onToggleDevArtificialDelay,
                    dynamicColorSupported = dynamicColorSupported,
                    accessibilityManager = accessibilityManager,
                    hapticFeedbackManager = hapticManager,
                    onDynamicColorChanged = onToggleDynamicColor,
                    onHighContrastChanged = onToggleHighContrast,
                    onTalkBackIntegrationChanged = onToggleTalkBackIntegration,
                    onFontScaleChanged = onFontScaleChanged,
                    layoutAnimationsEnabled = layoutAnimationsEnabled,
                    modifier = Modifier.fillMaxSize()
                )

                val renderProgressState = state.renderProgress
                if (state.documentStatus is DocumentStatus.Idle &&
                    renderProgressState is PdfRenderProgress.Rendering
                ) {
                    RenderProgressIndicator(
                        pageIndex = renderProgressState.pageIndex,
                        progress = renderProgressState.progress,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp, start = 24.dp, end = 24.dp)
                    )
                }

                val indexingState = state.searchIndexing
                if (indexingState is SearchIndexingState.InProgress) {
                    SearchIndexingBanner(
                        state = indexingState,
                        onCancel = onCancelIndexing,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    )
                }

                if (BuildConfig.DEBUG && state.devDiagnosticsEnabled) {
                    HealthHud(
                        frameIntervalMillis = state.frameIntervalMillis,
                        queueStats = state.renderQueueStats,
                        memoryStats = state.bitmapMemory,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    )
                }
            }
        }

        DocumentStatusHost(
            status = state.documentStatus,
            snackbarHost = snackbarHost,
            onDismissError = onDismissError,
            animationsEnabled = layoutAnimationsEnabled
        )

        if (showOnboarding) {
            OnboardingOverlay(
                pages = onboardingPages,
                currentPageIndex = onboardingPageIndex,
                onPageChange = { index ->
                    onboardingPageIndex = index.coerceIn(0, onboardingPages.lastIndex)
                },
                onSkip = completeOnboarding,
                onFinish = completeOnboarding
            )
        }

        if (showSourceDialog) {
            DocumentSourceDialog(
                onDismiss = { showSourceDialog = false },
                onSelectDevice = {
                    showSourceDialog = false
                    onOpenLocalDocument()
                },
                onSelectCloud = {
                    showSourceDialog = false
                    onOpenCloudDocument()
                },
                onSelectRemote = {
                    showSourceDialog = false
                    showUrlDialog = true
                }
            )
        }

        if (showUrlDialog) {
            DocumentUrlDialog(
                onDismiss = { showUrlDialog = false },
                onConfirm = { source ->
                    showUrlDialog = false
                    onOpenRemoteDocument(source)
                }
            )
        }

        state.pendingLargeDownload?.let { pending ->
            LargeDownloadDialog(
                pending = pending,
                onConfirm = onConfirmLargeDownload,
                onDismiss = onDismissLargeDownload,
            )
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

        if (showAccessibilitySheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showAccessibilitySheet = false },
                sheetState = sheetState,
                modifier = Modifier.fillMaxHeight()
            ) {
                val sheetTitle = stringResource(id = R.string.accessibility_sheet_title)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { paneTitle = sheetTitle }
                ) {
                    BottomSheetDefaults.DragHandle()
                    AccessibilitySettingsSheet(
                        dynamicColorEnabled = state.dynamicColorEnabled,
                        highContrastEnabled = state.highContrastEnabled,
                        talkBackIntegrationEnabled = state.talkBackIntegrationEnabled,
                        fontScale = state.fontScale,
                        dynamicColorSupported = dynamicColorSupported,
                        accessibilityManager = accessibilityManager,
                        hapticFeedbackManager = hapticManager,
                        onDynamicColorChanged = onToggleDynamicColor,
                        onHighContrastChanged = onToggleHighContrast,
                        onTalkBackIntegrationChanged = onToggleTalkBackIntegration,
                        onFontScaleChanged = onFontScaleChanged,
                        layoutAnimationsEnabled = layoutAnimationsEnabled,
                        modifier = Modifier.fillMaxHeight(0.95f)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfViewerTopBar(
    selectedDestination: MainDestination,
    hasDocument: Boolean,
    isCurrentPageBookmarked: Boolean,
    hasOutline: Boolean,
    onOpenDocument: () -> Unit,
    onOpenAccessibilityOptions: () -> Unit,
    onSaveAnnotations: () -> Unit,
    onShowOutlineSheet: () -> Unit,
    onExportDocument: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    val titleText = stringResource(id = R.string.app_name)
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = titleText,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { contentDescription = titleText }
            )
        },
        actions = {
            IconButton(onClick = onOpenDocument) {
                Icon(
                    imageVector = Icons.Outlined.FileOpen,
                    contentDescription = stringResource(id = R.string.open_pdf)
                )
            }
            IconButton(onClick = onOpenAccessibilityOptions) {
                Icon(
                    imageVector = Icons.Outlined.Accessibility,
                    contentDescription = stringResource(id = R.string.accessibility_open_options)
                )
            }
            if (selectedDestination == MainDestination.Reader) {
                IconButton(
                    onClick = onSaveAnnotations,
                    enabled = hasDocument,
                    modifier = Modifier.testTag(UiAutomatorTags.SAVE_ANNOTATIONS_ACTION)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Download,
                        contentDescription = stringResource(id = R.string.save_annotations)
                    )
                }
                if (hasDocument) {
                    IconButton(onClick = onShowOutlineSheet, enabled = hasOutline) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.List,
                            contentDescription = stringResource(id = R.string.pdf_outline)
                        )
                    }
                    IconButton(onClick = onExportDocument) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(id = R.string.export_document)
                        )
                    }
                }
                IconButton(onClick = onToggleBookmark, enabled = hasDocument) {
                    val icon = if (isCurrentPageBookmarked) {
                        Icons.Filled.Bookmark
                    } else {
                        Icons.Outlined.BookmarkBorder
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(id = R.string.toggle_bookmark)
                    )
                }
            }
        }
    )
}

@Composable
private fun PdfViewerBottomNavigation(
    items: List<NavigationItem>,
    selectedDestination: MainDestination,
    onDestinationSelected: (MainDestination) -> Unit,
) {
    NavigationBar {
        items.forEach { item ->
            val labelText = stringResource(id = item.labelRes)
            NavigationBarItem(
                selected = selectedDestination == item.destination,
                onClick = { onDestinationSelected(item.destination) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = labelText
                    )
                },
                label = {
                    Text(
                        text = labelText,
                        modifier = Modifier.semantics { contentDescription = labelText }
                    )
                }
            )
        }
    }
}

@Composable
private fun PdfViewerSearchFab(onClick: () -> Unit) {
    FloatingActionButton(onClick = onClick) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = stringResource(id = R.string.search_hint)
        )
    }
}

@Composable
private fun PdfViewerDestinationContainer(
    selectedDestination: MainDestination,
    state: PdfViewerUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    totalSearchResults: Int,
    onOpenDocument: () -> Unit,
    onOpenLastDocument: () -> Unit,
    onPlayAdaptiveSummary: () -> Unit,
    onOpenAccessibilityOptions: () -> Unit,
    focusRequester: FocusRequester,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onPageChange: (Int) -> Unit,
    onPageCommit: (Int) -> Unit,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    renderPage: suspend (Int, Int, RenderWorkQueue.Priority) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    renderDispatcher: CoroutineDispatcher,
    onViewportWidthChanged: (Int) -> Unit,
    onPrefetchPages: (List<Int>, Int) -> Unit,
    onOpenDevOptions: () -> Unit,
    onBackFromDevOptions: () -> Unit,
    onDumpDiagnostics: () -> Unit,
    onToggleDevDiagnostics: (Boolean) -> Unit,
    onToggleDevCaches: (Boolean) -> Unit,
    onToggleDevArtificialDelay: (Boolean) -> Unit,
    dynamicColorSupported: Boolean,
    accessibilityManager: AccessibilityManager?,
    hapticFeedbackManager: HapticFeedbackManager,
    onDynamicColorChanged: (Boolean) -> Unit,
    onHighContrastChanged: (Boolean) -> Unit,
    onTalkBackIntegrationChanged: (Boolean) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    layoutAnimationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    when (selectedDestination) {
        MainDestination.Home -> {
            HomeContent(
                onOpenDocument = onOpenDocument,
                onOpenLastDocument = onOpenLastDocument,
                preferencesReady = state.preferencesReady,
                lastDocumentUri = state.lastOpenedDocumentUri,
                modifier = modifier
            )
        }

        MainDestination.Reader -> {
            ReaderContent(
                state = state,
                searchQuery = searchQuery,
                onQueryChange = onSearchQueryChange,
                totalSearchResults = totalSearchResults,
                onOpenDocument = onOpenDocument,
                onPlayAdaptiveSummary = onPlayAdaptiveSummary,
                onOpenAccessibilityOptions = onOpenAccessibilityOptions,
                focusRequester = focusRequester,
                requestFocus = requestFocus,
                onFocusHandled = onFocusHandled,
                onPageChange = onPageChange,
                onPageCommit = onPageCommit,
                onStrokeFinished = onStrokeFinished,
                onToggleBookmark = onToggleBookmark,
                renderPage = renderPage,
                requestPageSize = requestPageSize,
                renderDispatcher = renderDispatcher,
                onViewportWidthChanged = onViewportWidthChanged,
                onPrefetchPages = onPrefetchPages,
                layoutAnimationsEnabled = layoutAnimationsEnabled,
                modifier = modifier
            )
        }

        MainDestination.Annotations -> {
            AnnotationsContent(
                annotationCount = state.activeAnnotations.size,
                modifier = modifier
            )
        }

        MainDestination.Settings -> {
            SettingsContent(
                dynamicColorEnabled = state.dynamicColorEnabled,
                highContrastEnabled = state.highContrastEnabled,
                talkBackIntegrationEnabled = state.talkBackIntegrationEnabled,
                fontScale = state.fontScale,
                dynamicColorSupported = dynamicColorSupported,
                accessibilityManager = accessibilityManager,
                hapticFeedbackManager = hapticFeedbackManager,
                onDynamicColorChanged = onDynamicColorChanged,
                onHighContrastChanged = onHighContrastChanged,
                onTalkBackIntegrationChanged = onTalkBackIntegrationChanged,
                onFontScaleChanged = onFontScaleChanged,
                layoutAnimationsEnabled = layoutAnimationsEnabled,
                onOpenDevOptions = onOpenDevOptions,
                modifier = modifier
            )
        }

        MainDestination.DevOptions -> {
            DevOptionsContent(
                stats = state.bitmapMemory,
                diagnosticsEnabled = state.devDiagnosticsEnabled,
                cachesEnabled = state.devCachesEnabled,
                cachesFallbackActive = state.renderCacheFallbackActive,
                artificialDelayEnabled = state.devArtificialDelayEnabled,
                onDiagnosticsToggle = onToggleDevDiagnostics,
                onCachesToggle = onToggleDevCaches,
                onArtificialDelayToggle = onToggleDevArtificialDelay,
                onDumpDiagnostics = onDumpDiagnostics,
                onBack = onBackFromDevOptions,
                accessibilityManager = accessibilityManager,
                hapticFeedbackManager = hapticFeedbackManager,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun MessageFlowHandler(
    messageFlow: Flow<UiMessage>,
    snackbarHost: SnackbarHostState,
    accessibilityManager: AccessibilityManager?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentContext by rememberUpdatedState(newValue = context)
    val currentSnackbarHost by rememberUpdatedState(newValue = snackbarHost)
    val currentAccessibilityManager by rememberUpdatedState(newValue = accessibilityManager)

    LaunchedEffect(messageFlow, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            messageFlow.collectLatest { message ->
                val text = currentContext.getString(message.messageRes)
                currentSnackbarHost.showSnackbar(text)
                currentAccessibilityManager?.sendAnnouncement(text)
            }
        }
    }
}

@Composable
private fun DocumentSourceDialog(
    onDismiss: () -> Unit,
    onSelectDevice: () -> Unit,
    onSelectCloud: () -> Unit,
    onSelectRemote: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.open_source_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(id = R.string.open_source_dialog_body))
                FilledTonalButton(
                    onClick = onSelectDevice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiAutomatorTags.SOURCE_DEVICE_BUTTON)
                ) {
                    Text(text = stringResource(id = R.string.open_source_device))
                }
                FilledTonalButton(
                    onClick = onSelectCloud,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiAutomatorTags.SOURCE_CLOUD_BUTTON)
                ) {
                    Text(text = stringResource(id = R.string.open_source_cloud))
                }
                FilledTonalButton(
                    onClick = onSelectRemote,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiAutomatorTags.SOURCE_REMOTE_BUTTON)
                ) {
                    Text(text = stringResource(id = R.string.open_source_url))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun DocumentUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (DocumentSource) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    val trimmedUrl = url.trim()
    val isValidUrl = remember(trimmedUrl) {
        Patterns.WEB_URL.matcher(trimmedUrl).matches()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.enter_pdf_url)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = stringResource(id = R.string.enter_pdf_url_hint))
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        if (showError) {
                            showError = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(text = stringResource(id = R.string.enter_pdf_url_label)) },
                    placeholder = { Text(text = stringResource(id = R.string.enter_pdf_url_placeholder)) },
                    singleLine = true,
                    isError = showError && !isValidUrl,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                if (showError && !isValidUrl) {
                    Text(
                        text = stringResource(id = R.string.enter_pdf_url_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isValidUrl) {
                    onConfirm(DocumentSource.RemoteUrl(trimmedUrl))
                } else {
                    showError = true
                }
            }) {
                Text(text = stringResource(id = R.string.action_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun LargeDownloadDialog(
    pending: PendingLargeDownload,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val formattedSize = remember(pending.sizeBytes) {
        pending.sizeBytes.takeIf { it > 0L }
            ?.let { size -> AndroidFormatter.formatShortFileSize(context, size) }
    }
    val formattedLimit = remember(pending.maxBytes) {
        AndroidFormatter.formatShortFileSize(context, pending.maxBytes)
    }
    val body = remember(formattedSize, formattedLimit) {
        if (formattedSize != null) {
            context.getString(
                R.string.remote_large_pdf_body_with_size,
                formattedSize,
                formattedLimit,
            )
        } else {
            context.getString(
                R.string.remote_large_pdf_body_unknown,
                formattedLimit,
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.remote_large_pdf_title)) },
        text = { Text(text = body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.remote_large_pdf_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun DocumentStatusHost(
    status: DocumentStatus,
    snackbarHost: SnackbarHostState,
    onDismissError: () -> Unit,
    animationsEnabled: Boolean
) {
    val currentDismissError by rememberUpdatedState(newValue = onDismissError)
    val currentSnackbarHost by rememberUpdatedState(newValue = snackbarHost)

    if (status is DocumentStatus.Error) {
        val retryLabel = stringResource(id = R.string.action_try_again)
        val message = status.message
        LaunchedEffect(status, currentSnackbarHost, retryLabel, message) {
            val result = currentSnackbarHost.showSnackbar(
                message = message,
                actionLabel = retryLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite
            )
            when (result) {
                SnackbarResult.ActionPerformed,
                SnackbarResult.Dismissed -> currentDismissError()
            }
        }
    }

    if (status is DocumentStatus.Loading) {
        LoadingOverlay(status, animationsEnabled)
    }
}

@Composable
private fun NovaSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState = hostState) { data ->
        val visuals = data.visuals
        val isError = visuals.duration == SnackbarDuration.Indefinite && visuals.withDismissAction
        val containerColor = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.inverseSurface
        }
        val contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.inverseOnSurface
        }
        val actionContentColor = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.inversePrimary
        }
        val dismissColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.inverseOnSurface
        }
        Snackbar(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            snackbarData = data,
            shape = MaterialTheme.shapes.large,
            containerColor = containerColor,
            contentColor = contentColor,
            actionColor = actionContentColor,
            actionContentColor = actionContentColor,
            dismissActionContentColor = dismissColor
        )
    }
}

@Composable
private fun SearchIndexingBanner(
    state: SearchIndexingState.InProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phaseText = when (state.phase) {
        SearchIndexingPhase.PREPARING -> stringResource(id = R.string.search_indexing_phase_preparing)
        SearchIndexingPhase.EXTRACTING_TEXT -> stringResource(id = R.string.search_indexing_phase_extracting)
        SearchIndexingPhase.APPLYING_OCR -> stringResource(id = R.string.search_indexing_phase_ocr)
        SearchIndexingPhase.WRITING_INDEX -> stringResource(id = R.string.search_indexing_phase_writing)
    }
    val titleText = stringResource(id = R.string.search_indexing_title)
    val cancelLabel = stringResource(id = R.string.search_indexing_cancel)
    val progressPercent = state.progress?.let { (it * 100).roundToInt().coerceIn(0, 100) }
    val progressDescription = progressPercent?.let { percent ->
        stringResource(id = R.string.loading_progress, percent)
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 400.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                paneTitle = titleText
                stateDescription = progressDescription ?: phaseText
            }
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
        ) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = phaseText,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            val progress = state.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                progressDescription?.let { description ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.semantics { contentDescription = cancelLabel }
                ) {
                    Text(text = cancelLabel)
                }
            }
        }
    }
}

@Composable
private fun LoadingOverlay(status: DocumentStatus.Loading, animationsEnabled: Boolean) {
    val message = status.messageRes?.let { stringResource(id = it) }
        ?: stringResource(id = R.string.loading_document)
    val progressValue = status.progress?.coerceIn(0f, 1f)
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)
    val overlayInteraction = remember { MutableInteractionSource() }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimColor)
                .clickable(
                    interactionSource = overlayInteraction,
                    indication = null,
                    onClick = {}
                )
                .semantics {
                    liveRegion = LiveRegionMode.Assertive
                    contentDescription = message
                },
            contentAlignment = Alignment.Center
        ) {
            val containerShape = MaterialTheme.shapes.extraLarge
            val containerColor = MaterialTheme.colorScheme.surface
            val containerBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            Box(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .widthIn(min = 260.dp)
                    .wrapContentHeight()
                    .clip(containerShape)
                    .background(containerColor)
                    .border(width = 1.dp, color = containerBorder, shape = containerShape)
                    .padding(horizontal = 24.dp, vertical = 28.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = message,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    val progress = progressValue
                    val showProgress = progress != null
                    val progressContent: @Composable (Float) -> Unit = { value ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { value },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = stringResource(
                                    id = R.string.loading_progress,
                                    (value * 100).roundToInt()
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    if (animationsEnabled) {
                        AnimatedVisibility(visible = showProgress) {
                            progress?.let { progressContent(it) }
                        }
                    } else if (progress != null) {
                        progressContent(progress)
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderProgressIndicator(
    pageIndex: Int,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
        label = "RenderProgress"
    )
    val progressPercent = (animatedProgress * 100f).roundToInt()
    val announcement = stringResource(
        id = R.string.render_progress_announcement,
        pageIndex + 1,
        progressPercent
    )
    Surface(
        modifier = modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = announcement
        },
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 220.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(id = R.string.render_progress_title, pageIndex + 1),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
            )
            Text(
                text = stringResource(
                    id = R.string.loading_progress,
                    progressPercent
                ),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultsCount: Int,
    focusRequester: FocusRequester,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        LaunchedEffect(requestFocus) {
            if (requestFocus) {
                focusRequester.requestFocus()
                onFocusHandled()
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            label = {
                val labelText = stringResource(id = R.string.search_hint)
                Text(
                    text = labelText,
                    modifier = Modifier.semantics { contentDescription = labelText }
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = stringResource(id = R.string.search_hint)
                )
            },
            singleLine = true
        )
        if (query.isNotBlank()) {
            val resultsText = stringResource(id = R.string.search_results_count, resultsCount)
            Text(
                text = resultsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .semantics {
                        contentDescription = resultsText
                        liveRegion = LiveRegionMode.Polite
                    }
            )
        }
    }
}

@Composable
private fun ReaderContent(
    state: PdfViewerUiState,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    totalSearchResults: Int,
    onOpenDocument: () -> Unit,
    onPlayAdaptiveSummary: () -> Unit,
    onOpenAccessibilityOptions: () -> Unit,
    focusRequester: FocusRequester,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit,
    onPageChange: (Int) -> Unit,
    onPageCommit: (Int) -> Unit,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    renderPage: suspend (Int, Int, RenderWorkQueue.Priority) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    renderDispatcher: CoroutineDispatcher,
    onViewportWidthChanged: (Int) -> Unit,
    onPrefetchPages: (List<Int>, Int) -> Unit,
    layoutAnimationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    PdfPager(
        modifier = modifier.fillMaxSize(),
        state = state,
        onPageChange = onPageChange,
        onPageCommit = onPageCommit,
        onStrokeFinished = onStrokeFinished,
        onToggleBookmark = onToggleBookmark,
        renderPage = renderPage,
        requestPageSize = requestPageSize,
        renderDispatcher = renderDispatcher,
        onViewportWidthChanged = onViewportWidthChanged,
        onPrefetchPages = onPrefetchPages,
        animationsEnabled = layoutAnimationsEnabled,
        headerContent = {
            ReaderHeader(
                state = state,
                searchQuery = searchQuery,
                onQueryChange = onQueryChange,
                totalSearchResults = totalSearchResults,
                onPlayAdaptiveSummary = onPlayAdaptiveSummary,
                onOpenAccessibilityOptions = onOpenAccessibilityOptions,
                focusRequester = focusRequester,
                requestFocus = requestFocus,
                onFocusHandled = onFocusHandled
            )
        },
        emptyStateContent = {
            EmptyState(
                onOpenDocument = onOpenDocument,
                modifier = Modifier.fillMaxSize()
            )
        }
    )
}

@Composable
private fun ReaderHeader(
    state: PdfViewerUiState,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    totalSearchResults: Int,
    onPlayAdaptiveSummary: () -> Unit,
    onOpenAccessibilityOptions: () -> Unit,
    focusRequester: FocusRequester,
    requestFocus: Boolean,
    onFocusHandled: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = onQueryChange,
            resultsCount = totalSearchResults,
            focusRequester = focusRequester,
            requestFocus = requestFocus,
            onFocusHandled = onFocusHandled
        )

        AdaptiveFlowStatusRow(state, onPlaySummary = onPlayAdaptiveSummary)

        FilledTonalButton(
            onClick = onOpenAccessibilityOptions,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp)
                .fillMaxWidth()
        ) {
            val buttonText = stringResource(id = R.string.accessibility_open_options)
            Text(
                text = buttonText,
                modifier = Modifier.semantics { contentDescription = buttonText }
            )
        }

        if (state.pageCount > 0) {
            Spacer(modifier = Modifier.height(24.dp))
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
        val statusText = if (isActive) {
            stringResource(id = R.string.adaptive_flow_on)
        } else {
            stringResource(id = R.string.adaptive_flow_off)
        }
        val statusIcon = if (isActive) Icons.Outlined.AutoAwesome else Icons.Outlined.PauseCircle
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text(text = statusText) },
            leadingIcon = {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                )
            },
            modifier = Modifier
                .testTag(UiAutomatorTags.ADAPTIVE_FLOW_STATUS_CHIP)
                .semantics { contentDescription = statusText }
        )
        Spacer(modifier = Modifier.height(12.dp))
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
private fun HomeContent(
    onOpenDocument: () -> Unit,
    onOpenLastDocument: () -> Unit,
    preferencesReady: Boolean,
    lastDocumentUri: String?,
    modifier: Modifier = Modifier
) {
    if (!preferencesReady) {
        HomeSkeleton(modifier)
        return
    }

    val parsedDocumentName = remember(lastDocumentUri) {
        lastDocumentUri?.let { uriString ->
            runCatching { Uri.parse(uriString) }
                .getOrNull()
                ?.lastPathSegment
                ?.takeIf { it.isNotBlank() }
        }
    }
    val hasLastDocument = lastDocumentUri != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val titleText = stringResource(id = R.string.home_welcome_title)
        Text(
            text = titleText,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(bottom = 16.dp)
                .semantics { contentDescription = titleText }
        )
        val bodyText = stringResource(id = R.string.home_welcome_body)
        Text(
            text = bodyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { contentDescription = bodyText }
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (hasLastDocument) {
            ContinueReadingCard(
                documentName = parsedDocumentName,
                onOpenLastDocument = onOpenLastDocument,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
        Button(
            onClick = onOpenDocument,
            modifier = Modifier.testTag(UiAutomatorTags.HOME_OPEN_DOCUMENT_BUTTON)
        ) {
            val buttonText = stringResource(id = R.string.empty_state_button)
            Text(
                text = buttonText,
                modifier = Modifier.semantics { contentDescription = buttonText }
            )
        }
    }
}

@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(28.dp),
            shape = MaterialTheme.shapes.small
        )
        Spacer(modifier = Modifier.height(16.dp))
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            shape = MaterialTheme.shapes.large
        )
        Spacer(modifier = Modifier.height(24.dp))
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(48.dp),
            shape = MaterialTheme.shapes.medium
        )
    }
}

@Composable
private fun ContinueReadingCard(
    documentName: String?,
    onOpenLastDocument: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(id = R.string.home_continue_card_title)
    val fallbackName = stringResource(id = R.string.home_continue_card_unknown)
    val resolvedName = documentName ?: fallbackName
    val body = stringResource(id = R.string.home_continue_card_body, resolvedName)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            FilledTonalButton(onClick = onOpenLastDocument) {
                Text(text = stringResource(id = R.string.home_continue_card_button))
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    modifier: Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    val color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(color)
    )
}

@Composable
private fun AnnotationsContent(
    annotationCount: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val titleText = stringResource(id = R.string.annotations_title)
        Text(
            text = titleText,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(bottom = 12.dp)
                .semantics { contentDescription = titleText }
        )
        val message = if (annotationCount > 0) {
            stringResource(id = R.string.annotations_summary, annotationCount)
        } else {
            stringResource(id = R.string.annotations_empty)
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { contentDescription = message }
        )
    }
}

@Composable
private fun SettingsContent(
    dynamicColorEnabled: Boolean,
    highContrastEnabled: Boolean,
    talkBackIntegrationEnabled: Boolean,
    fontScale: Float,
    dynamicColorSupported: Boolean,
    accessibilityManager: AccessibilityManager?,
    hapticFeedbackManager: HapticFeedbackManager,
    onDynamicColorChanged: (Boolean) -> Unit,
    onHighContrastChanged: (Boolean) -> Unit,
    onTalkBackIntegrationChanged: (Boolean) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    layoutAnimationsEnabled: Boolean,
    onOpenDevOptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    AccessibilitySettingsSheet(
        dynamicColorEnabled = dynamicColorEnabled,
        highContrastEnabled = highContrastEnabled,
        talkBackIntegrationEnabled = talkBackIntegrationEnabled,
        fontScale = fontScale,
        dynamicColorSupported = dynamicColorSupported,
        accessibilityManager = accessibilityManager,
        hapticFeedbackManager = hapticFeedbackManager,
        onDynamicColorChanged = onDynamicColorChanged,
        onHighContrastChanged = onHighContrastChanged,
        onTalkBackIntegrationChanged = onTalkBackIntegrationChanged,
        onFontScaleChanged = onFontScaleChanged,
        layoutAnimationsEnabled = layoutAnimationsEnabled,
        modifier = modifier,
        footer = {
            ExpandableSettingSection(
                title = stringResource(id = R.string.dev_options_title),
                description = stringResource(id = R.string.dev_options_description),
                animationsEnabled = layoutAnimationsEnabled
            ) {
                Text(
                    text = stringResource(id = R.string.dev_options_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onOpenDevOptions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(id = R.string.dev_options_open))
                }
            }
        }
    )
}

@Composable
private fun DevOptionsContent(
    stats: BitmapMemoryStats,
    diagnosticsEnabled: Boolean,
    cachesEnabled: Boolean,
    cachesFallbackActive: Boolean,
    artificialDelayEnabled: Boolean,
    onDiagnosticsToggle: (Boolean) -> Unit,
    onCachesToggle: (Boolean) -> Unit,
    onArtificialDelayToggle: (Boolean) -> Unit,
    onDumpDiagnostics: () -> Unit,
    onBack: () -> Unit,
    accessibilityManager: AccessibilityManager?,
    hapticFeedbackManager: HapticFeedbackManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val statusTextRes = when (stats.level) {
        BitmapMemoryLevel.CRITICAL -> R.string.dev_options_status_critical
        BitmapMemoryLevel.WARNING -> R.string.dev_options_status_warning
        BitmapMemoryLevel.NORMAL -> R.string.dev_options_status_normal
    }
    val statusColor = when (stats.level) {
        BitmapMemoryLevel.CRITICAL -> MaterialTheme.colorScheme.error
        BitmapMemoryLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        BitmapMemoryLevel.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = stringResource(id = statusTextRes)

    fun readable(bytes: Long): String {
        val short = Formatter.formatShortFileSize(context, bytes)
        return context.getString(R.string.dev_options_memory_value_format, short, bytes)
    }

    fun threshold(bytes: Long): String {
        return if (bytes > 0L) {
            readable(bytes)
        } else {
            context.getString(R.string.dev_options_threshold_unavailable)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.dev_options_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.dev_options_memory_overview),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.dev_options_tools_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(id = R.string.dev_options_tools_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DevOptionToggleRow(
                    label = stringResource(id = R.string.dev_options_diagnostics_label),
                    description = stringResource(id = R.string.dev_options_diagnostics_description),
                    checked = diagnosticsEnabled,
                    enabled = true,
                    onToggle = { value ->
                        hapticFeedbackManager.onToggleChange(value)
                        onDiagnosticsToggle(value)
                        announceToggleState(
                            accessibilityManager,
                            context,
                            R.string.dev_options_diagnostics_label,
                            value
                        )
                    }
                )
                DevOptionToggleRow(
                    label = stringResource(id = R.string.dev_options_caches_label),
                    description = if (cachesFallbackActive) {
                        stringResource(id = R.string.dev_options_caches_fallback_description)
                    } else {
                        stringResource(id = R.string.dev_options_caches_description)
                    },
                    checked = cachesEnabled,
                    enabled = !cachesFallbackActive,
                    onToggle = { value ->
                        hapticFeedbackManager.onToggleChange(value)
                        onCachesToggle(value)
                        announceToggleState(
                            accessibilityManager,
                            context,
                            R.string.dev_options_caches_label,
                            value
                        )
                    }
                )
                if (cachesFallbackActive) {
                    Text(
                        text = stringResource(id = R.string.dev_options_caches_fallback_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                val debugAvailable = BuildConfig.DEBUG
                DevOptionToggleRow(
                    label = stringResource(id = R.string.dev_options_delay_label),
                    description = stringResource(id = R.string.dev_options_delay_description),
                    checked = artificialDelayEnabled,
                    enabled = debugAvailable,
                    onToggle = { value ->
                        hapticFeedbackManager.onToggleChange(value)
                        onArtificialDelayToggle(value)
                        announceToggleState(
                            accessibilityManager,
                            context,
                            R.string.dev_options_delay_label,
                            value
                        )
                    }
                )
                if (!debugAvailable) {
                    Text(
                        text = stringResource(id = R.string.dev_options_debug_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.dev_options_memory_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(id = R.string.dev_options_memory_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MemoryStatRow(
                    label = stringResource(id = R.string.dev_options_memory_current),
                    value = readable(stats.currentBytes)
                )
                MemoryStatRow(
                    label = stringResource(id = R.string.dev_options_memory_peak),
                    value = readable(stats.peakBytes)
                )
                MemoryStatRow(
                    label = stringResource(id = R.string.dev_options_memory_warn_threshold),
                    value = threshold(stats.warnThresholdBytes)
                )
                MemoryStatRow(
                    label = stringResource(id = R.string.dev_options_memory_critical_threshold),
                    value = threshold(stats.criticalThresholdBytes)
                )
                MemoryStatRow(
                    label = stringResource(id = R.string.dev_options_memory_status_label),
                    value = statusText,
                    valueColor = statusColor
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(id = R.string.dev_options_dump_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onDumpDiagnostics,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.dev_options_dump_runtime))
        }
        Spacer(modifier = Modifier.height(24.dp))
        FilledTonalButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = stringResource(id = R.string.dev_options_back))
        }
    }
}

@Composable
private fun DevOptionToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val stateText = stringResource(
        id = if (checked) {
            R.string.accessibility_state_on
        } else {
            R.string.accessibility_state_off
        }
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stateText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = stateText
            }
        )
    }
}

@Composable
private fun HealthHud(
    frameIntervalMillis: Float,
    queueStats: RenderQueueStats,
    memoryStats: BitmapMemoryStats,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fps = remember(frameIntervalMillis) {
        if (frameIntervalMillis > 0f) {
            1000f / frameIntervalMillis
        } else {
            0f
        }
    }
    val fpsText = remember(fps) { String.format(Locale.US, "%.1f", fps) }
    val queueDepth = queueStats.totalQueued
    val queueBreakdown = remember(queueStats) {
        "V${queueStats.visible}/N${queueStats.nearby}/T${queueStats.thumbnail}"
    }
    val pendingJobs = queueStats.active
    val currentMemory = remember(memoryStats.currentBytes, context) {
        Formatter.formatShortFileSize(context, memoryStats.currentBytes)
    }
    val peakMemory = remember(memoryStats.peakBytes, context) {
        Formatter.formatShortFileSize(context, memoryStats.peakBytes)
    }
    val levelLabel = remember(memoryStats.level) {
        memoryStats.level.name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        modifier = modifier
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Health",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "FPS: $fpsText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Queue: $queueDepth ($queueBreakdown)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Pending: $pendingJobs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Memory: $currentMemory / $peakMemory ($levelLabel)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MemoryStatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OnboardingOverlay(
    pages: List<OnboardingPage>,
    currentPageIndex: Int,
    onPageChange: (Int) -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onSkip,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        val dialogTitle = stringResource(id = R.string.onboarding_dialog_title)
        Surface(
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
            modifier = modifier
                .fillMaxSize()
                .systemBarsPadding()
                .semantics {
                    paneTitle = dialogTitle
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.testTag(UiAutomatorTags.ONBOARDING_SKIP_BUTTON)
                    ) {
                        val skipText = stringResource(id = R.string.onboarding_skip)
                        Text(
                            text = skipText,
                            modifier = Modifier.semantics { contentDescription = skipText }
                        )
                    }
                }

                Crossfade(
                    targetState = currentPageIndex,
                    label = "OnboardingPage"
                ) { pageIndex ->
                    val page = pages[pageIndex]
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = page.icon,
                            contentDescription = null,
                            modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        val titleText = stringResource(id = page.titleRes)
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { contentDescription = titleText }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        val descriptionText = stringResource(id = page.descriptionRes)
                        Text(
                            text = descriptionText,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .semantics { contentDescription = descriptionText }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(pages.size) { index ->
                            val isSelected = currentPageIndex == index
                            val indicatorDescription = if (isSelected) {
                                stringResource(
                                    id = R.string.onboarding_page_indicator_selected,
                                    index + 1,
                                    pages.size
                                )
                            } else {
                                stringResource(
                                    id = R.string.onboarding_page_indicator,
                                    index + 1,
                                    pages.size
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 12.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                    )
                                    .semantics {
                                        contentDescription = indicatorDescription
                                        role = Role.Button
                                        selected = isSelected
                                    }
                                    .clickable(onClick = { onPageChange(index) })
                            )
                            if (index != pages.lastIndex) {
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                    }

                    val isLastPage = currentPageIndex == pages.lastIndex
                    val buttonText = if (isLastPage) {
                        stringResource(id = R.string.onboarding_get_started)
                    } else {
                        stringResource(id = R.string.onboarding_next)
                    }
                    Button(
                        onClick = {
                            if (isLastPage) {
                                onFinish()
                            } else {
                                onPageChange(currentPageIndex + 1)
                            }
                        }
                    ) {
                        Text(
                            text = buttonText,
                            modifier = Modifier.semantics { contentDescription = buttonText }
                        )
                    }
                }
            }
        }
    }
}

private data class OnboardingPage(
    val icon: ImageVector,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int
)

private data class NavigationItem(
    val destination: MainDestination,
    val icon: ImageVector,
    @StringRes val labelRes: Int
)

private enum class MainDestination {
    Home,
    Reader,
    Annotations,
    Settings,
    DevOptions
}

@Composable
private fun AccessibilitySettingsSheet(
    dynamicColorEnabled: Boolean,
    highContrastEnabled: Boolean,
    talkBackIntegrationEnabled: Boolean,
    fontScale: Float,
    dynamicColorSupported: Boolean,
    accessibilityManager: AccessibilityManager?,
    hapticFeedbackManager: HapticFeedbackManager,
    onDynamicColorChanged: (Boolean) -> Unit,
    onHighContrastChanged: (Boolean) -> Unit,
    onTalkBackIntegrationChanged: (Boolean) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    layoutAnimationsEnabled: Boolean,
    modifier: Modifier = Modifier,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.accessibility_sheet_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.accessibility_sheet_supporting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        ExpandableSettingSection(
            title = stringResource(id = R.string.dynamic_color_label),
            description = stringResource(id = R.string.dynamic_color_description),
            initiallyExpanded = true,
            animationsEnabled = layoutAnimationsEnabled
        ) {
            val supported = dynamicColorSupported
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(
                        id = if (dynamicColorEnabled && supported) {
                            R.string.accessibility_state_on
                        } else {
                            R.string.accessibility_state_off
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = dynamicColorEnabled && supported,
                    onCheckedChange = { enabled ->
                        if (!supported) return@Switch
                        hapticFeedbackManager.onToggleChange(enabled)
                        onDynamicColorChanged(enabled)
                        announceToggleState(
                            accessibilityManager,
                            context,
                            R.string.dynamic_color_label,
                            enabled
                        )
                    },
                    enabled = supported
                )
            }
            if (!supported) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.dynamic_color_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        ExpandableSettingSection(
            title = stringResource(id = R.string.high_contrast_label),
            description = stringResource(id = R.string.high_contrast_description),
            animationsEnabled = layoutAnimationsEnabled
        ) {
            val enabled = dynamicColorEnabled
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(
                        id = if (highContrastEnabled && enabled) {
                            R.string.accessibility_state_on
                        } else {
                            R.string.accessibility_state_off
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = highContrastEnabled && enabled,
                    onCheckedChange = { value ->
                        if (!enabled) return@Switch
                        hapticFeedbackManager.onToggleChange(value)
                        onHighContrastChanged(value)
                        announceToggleState(
                            accessibilityManager,
                            context,
                            R.string.high_contrast_label,
                            value
                        )
                    },
                    enabled = enabled
                )
            }
            if (!enabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.high_contrast_dependency_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ExpandableSettingSection(
            title = stringResource(id = R.string.accessibility_section_talkback_title),
            description = stringResource(id = R.string.accessibility_section_talkback_description),
            animationsEnabled = layoutAnimationsEnabled
        ) {
            val talkBackAvailable = accessibilityManager?.isTouchExplorationEnabled == true
            val checked = talkBackIntegrationEnabled && talkBackAvailable
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(
                        id = if (checked) {
                            R.string.accessibility_state_on
                        } else {
                            R.string.accessibility_state_off
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = checked,
                    onCheckedChange = { value ->
                        if (!talkBackAvailable) return@Switch
                        hapticFeedbackManager.onToggleChange(value)
                        onTalkBackIntegrationChanged(value)
                        announceToggleState(
                            accessibilityManager,
                            context,
                            R.string.accessibility_section_talkback_title,
                            value
                        )
                    },
                    enabled = talkBackAvailable
                )
            }
            val infoText = if (talkBackAvailable) {
                R.string.accessibility_section_talkback_hint
            } else {
                R.string.accessibility_section_talkback_unavailable
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = infoText),
                style = MaterialTheme.typography.bodySmall,
                color = if (talkBackAvailable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }

        ExpandableSettingSection(
            title = stringResource(id = R.string.accessibility_section_font_scale_title),
            description = stringResource(id = R.string.accessibility_section_font_scale_description),
            animationsEnabled = layoutAnimationsEnabled
        ) {
            var sliderValue by remember { mutableStateOf(fontScale) }
            LaunchedEffect(fontScale) {
                if (sliderValue != fontScale) {
                    sliderValue = fontScale
                }
            }
            Text(
                text = stringResource(
                    id = R.string.accessibility_font_scale_value,
                    (sliderValue * 100).roundToInt()
                ),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            Slider(
                value = sliderValue,
                onValueChange = { value ->
                    sliderValue = value
                    onFontScaleChanged(value)
                },
                valueRange = 0.8f..2f,
                steps = 5,
                onValueChangeFinished = {
                    hapticFeedbackManager.onAdjustment()
                    announceFontScale(accessibilityManager, context, sliderValue)
                }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(id = R.string.accessibility_font_scale_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        footer()
    }
}

@Composable
private fun ExpandableSettingSection(
    title: String,
    description: String,
    initiallyExpanded: Boolean = false,
    animationsEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.animateContentSizeIf(animationsEnabled)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null
                )
            }
            if (animationsEnabled) {
                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        content = content
                    )
                }
            } else if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    content = content
                )
            }
        }
    }
}

private fun Modifier.animateContentSizeIf(enabled: Boolean): Modifier {
    return if (enabled) {
        this.animateContentSize()
    } else {
        this
    }
}

private fun announceToggleState(
    accessibilityManager: AccessibilityManager?,
    context: Context,
    @StringRes labelRes: Int,
    enabled: Boolean
) {
    if (accessibilityManager?.isEnabled != true) return
    val label = context.getString(labelRes)
    val state = context.getString(
        if (enabled) R.string.accessibility_state_on else R.string.accessibility_state_off
    )
    val message = context.getString(R.string.accessibility_toggle_announcement, label, state)
    accessibilityManager.sendAnnouncement(message)
}

private fun announceFontScale(
    accessibilityManager: AccessibilityManager?,
    context: Context,
    fontScale: Float
) {
    if (accessibilityManager?.isEnabled != true) return
    val percent = (fontScale * 100).roundToInt()
    val message = context.getString(R.string.accessibility_font_scale_announcement, percent)
    accessibilityManager.sendAnnouncement(message)
}

@Suppress("DEPRECATION")
private fun AccessibilityManager?.sendAnnouncement(message: String) {
    val manager = this ?: return
    val event = AccessibilityEvent.obtain().apply {
        eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
        text.add(message)
    }
    manager.sendAccessibilityEvent(event)
}

@Composable
private fun EmptyState(onOpenDocument: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val illustrationShape = MaterialTheme.shapes.extraLarge
        Box(
            modifier = Modifier
                .size(220.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.surface
                        )
                    ),
                    shape = illustrationShape
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = illustrationShape
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.empty_state_illustration),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = stringResource(id = R.string.empty_state_message),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(id = R.string.empty_state_supporting),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onOpenDocument) {
            Text(text = stringResource(id = R.string.empty_state_button))
        }
    }
}

@Composable
private fun SearchHighlightOverlay(
    modifier: Modifier,
    matches: List<com.novapdf.reader.model.SearchMatch>,
    contentDescription: String? = null
) {
    if (matches.isEmpty()) return
    val highlight = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
    val semanticsModifier = if (!contentDescription.isNullOrBlank()) {
        modifier.semantics {
            this.contentDescription = contentDescription
            liveRegion = LiveRegionMode.Polite
        }
    } else {
        modifier
    }
    Canvas(modifier = semanticsModifier) {
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
        val outlineTitle = stringResource(id = R.string.outline_sheet_title)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { paneTitle = outlineTitle }
        ) {
            BottomSheetDefaults.DragHandle()
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
@OptIn(FlowPreview::class)
@Composable
private fun PdfPager(
    modifier: Modifier,
    state: PdfViewerUiState,
    onPageChange: (Int) -> Unit,
    onPageCommit: (Int) -> Unit,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    renderPage: suspend (Int, Int, RenderWorkQueue.Priority) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    renderDispatcher: CoroutineDispatcher,
    onViewportWidthChanged: (Int) -> Unit,
    onPrefetchPages: (List<Int>, Int) -> Unit,
    animationsEnabled: Boolean,
    headerContent: (@Composable () -> Unit)? = null,
    emptyStateContent: (@Composable () -> Unit)? = null
) {
    val lazyListState = rememberLazyListState()
    val latestOnPageChange by rememberUpdatedState(onPageChange)
    val latestOnPageCommit by rememberUpdatedState(onPageCommit)
    val latestRenderPage by rememberUpdatedState(renderPage)
    val latestRequestPageSize by rememberUpdatedState(requestPageSize)
    val latestStrokeFinished by rememberUpdatedState(onStrokeFinished)
    val latestViewportWidth by rememberUpdatedState(onViewportWidthChanged)
    val latestPrefetch by rememberUpdatedState(onPrefetchPages)
    val latestBookmarkToggle by rememberUpdatedState(onToggleBookmark)
    val headerCount = if (headerContent != null) 1 else 0

    if (state.pageCount <= 0) {
        Column(modifier = modifier.fillMaxSize()) {
            headerContent?.invoke()
            emptyStateContent?.let { content ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                ) {
                    content()
                }
            }
        }
        return
    }

    LaunchedEffect(state.pageCount, state.currentPage) {
        if (state.pageCount <= 0) return@LaunchedEffect
        val target = state.currentPage.coerceIn(0, state.pageCount - 1)
        val listIndex = target + headerCount
        if (lazyListState.firstVisibleItemIndex != listIndex || lazyListState.firstVisibleItemScrollOffset != 0) {
            lazyListState.scrollToItem(listIndex)
        }
    }

    LaunchedEffect(lazyListState, state.pageCount, headerCount) {
        if (state.pageCount <= 0) return@LaunchedEffect
        snapshotFlow { lazyListState.firstVisiblePageIndex(headerCount) }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { latestOnPageChange(it) }
    }

    LaunchedEffect(lazyListState, state.pageCount, headerCount) {
        if (state.pageCount <= 0) return@LaunchedEffect
        snapshotFlow { lazyListState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling ->
                if (!isScrolling) {
                    val settled = lazyListState.firstVisiblePageIndex(headerCount)
                    if (settled != null) {
                        latestOnPageCommit(settled)
                    }
                }
            }
    }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.layoutInfo.viewportEndOffset - lazyListState.layoutInfo.viewportStartOffset
        }
            .distinctUntilChanged()
            // Debounce layout changes to avoid rapid recomposition loops during rotations or animations.
            .debounce(VIEWPORT_WIDTH_DEBOUNCE_MS)
            .collect { width ->
                if (width > 0) {
                    latestViewportWidth(width)
                }
            }
    }

    LaunchedEffect(lazyListState, state.pageCount, headerCount) {
        if (state.pageCount <= 0) return@LaunchedEffect
        var lastIndex = -1
        var lastOffset = 0
        var lastTime = System.currentTimeMillis()
        snapshotFlow {
            val info = lazyListState.layoutInfo
            val firstPageInfo = info.firstPageItem(headerCount)
            PrefetchSnapshot(
                firstVisible = firstPageInfo?.let { it.index - headerCount } ?: -1,
                firstOffset = firstPageInfo?.offset?.let { offset -> max(-offset, 0) } ?: 0,
                viewportWidth = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0),
                firstItemSize = firstPageInfo?.size ?: 0,
                isScrolling = lazyListState.isScrollInProgress
            )
        }.collect { snapshot ->
            val now = System.currentTimeMillis()
            val dt = now - lastTime
            if (snapshot.firstVisible < 0) {
                lastIndex = -1
                lastOffset = 0
                lastTime = now
                return@collect
            }
            if (lastIndex < 0) {
                lastIndex = snapshot.firstVisible
                lastOffset = snapshot.firstOffset
                lastTime = now
                return@collect
            }
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

    val pageAnnouncement = if (state.pageCount > 0) {
        val totalPages = state.pageCount
        val currentPageNumber = (state.currentPage + 1).coerceIn(1, totalPages)
        stringResource(
            id = R.string.reader_page_announcement,
            currentPageNumber,
            totalPages
        )
    } else {
        null
    }
    val pagerSemantics = pageAnnouncement?.let { announcement ->
        Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = announcement
        }
    } ?: Modifier

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(pagerSemantics)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (headerContent != null) {
                item(key = PAGER_HEADER_KEY) { headerContent() }
            }
            if (state.pageCount <= 0) {
                if (emptyStateContent != null) {
                    item(key = PAGER_EMPTY_STATE_KEY) { emptyStateContent() }
                }
            } else {
                items(state.pageCount, key = { it }) { pageIndex ->
                    PdfPageItem(
                        pageIndex = pageIndex,
                        state = state,
                        renderPage = latestRenderPage,
                        requestPageSize = latestRequestPageSize,
                        renderDispatcher = renderDispatcher,
                        onStrokeFinished = latestStrokeFinished,
                        animationsEnabled = animationsEnabled
                    )
                }
            }
        }

        if (state.pageCount > 0) {
            PageTransitionScrim(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(96.dp)
            )
            PageTransitionScrim(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(96.dp),
                reverse = true
            )
        }

        if (state.pageCount in 1..LARGE_DOCUMENT_PAGE_THRESHOLD) {
            ThumbnailStrip(
                state = state,
                lazyListState = lazyListState,
                renderPage = latestRenderPage,
                renderDispatcher = renderDispatcher,
                onToggleBookmark = latestBookmarkToggle,
                listStartIndexOffset = headerCount,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
        }
    }
}

private const val PAGER_HEADER_KEY = "pdf_pager_header"
private const val PAGER_EMPTY_STATE_KEY = "pdf_pager_empty_state"

private fun LazyListState.firstVisiblePageIndex(headerCount: Int): Int? {
    val info = layoutInfo
    val firstPage = info.firstPageItem(headerCount) ?: return null
    return firstPage.index - headerCount
}

private fun LazyListLayoutInfo.firstPageItem(headerCount: Int): LazyListItemInfo? {
    return visibleItemsInfo.firstOrNull { it.index >= headerCount }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThumbnailStrip(
    state: PdfViewerUiState,
    lazyListState: LazyListState,
    renderPage: suspend (Int, Int, RenderWorkQueue.Priority) -> Bitmap?,
    renderDispatcher: CoroutineDispatcher,
    onToggleBookmark: (Int) -> Unit,
    listStartIndexOffset: Int,
    modifier: Modifier = Modifier
) {
    if (state.pageCount <= 0) return
    var selectedPage by rememberSaveable(state.documentId) { mutableStateOf(state.currentPage) }
    var showBookmarksOnly by rememberSaveable(state.documentId) { mutableStateOf(false) }

    LaunchedEffect(state.documentId, state.currentPage) {
        selectedPage = state.currentPage
    }

    val pagesToDisplay by remember(state.pageCount, state.bookmarks, showBookmarksOnly) {
        derivedStateOf {
            val allPages = (0 until state.pageCount).toList()
            if (showBookmarksOnly) {
                allPages.filter { page -> page in state.bookmarks }
            } else {
                allPages
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val surfaceColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)

    val thumbnailListState = rememberLazyListState()
    Surface(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .shadow(12.dp, MaterialTheme.shapes.large, clip = false),
        color = surfaceColor,
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.thumbnail_strip_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val filterLabel = stringResource(id = R.string.thumbnail_strip_filter_label)
                    val filterState = if (showBookmarksOnly) {
                        stringResource(id = R.string.accessibility_state_on)
                    } else {
                        stringResource(id = R.string.accessibility_state_off)
                    }
                    Text(
                        text = filterLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = showBookmarksOnly,
                        onCheckedChange = { showBookmarksOnly = it },
                        modifier = Modifier.semantics {
                            contentDescription = filterLabel
                            stateDescription = filterState
                        }
                    )
                }
            }

            if (pagesToDisplay.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.thumbnail_strip_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                )
            } else {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    state = thumbnailListState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pagesToDisplay, key = { it }) { pageIndex ->
                        ThumbnailItem(
                            documentId = state.documentId,
                            pageIndex = pageIndex,
                            isSelected = pageIndex == selectedPage,
                            isBookmarked = state.bookmarks.contains(pageIndex),
                            renderPage = renderPage,
                            renderDispatcher = renderDispatcher,
                            onSelect = {
                                selectedPage = pageIndex
                                coroutineScope.launch {
                                    lazyListState.animateScrollToItem(pageIndex + listStartIndexOffset)
                                }
                            },
                            onToggleBookmark = { onToggleBookmark(pageIndex) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailItem(
    documentId: String?,
    pageIndex: Int,
    isSelected: Boolean,
    isBookmarked: Boolean,
    renderPage: suspend (Int, Int, RenderWorkQueue.Priority) -> Bitmap?,
    renderDispatcher: CoroutineDispatcher,
    onSelect: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val targetWidthPx = remember(density) {
        with(density) { THUMBNAIL_WIDTH.roundToPx().coerceAtLeast(1) }
    }
    var thumbnail by remember(documentId, pageIndex) { mutableStateOf<Bitmap?>(null) }
    val latestRender by rememberUpdatedState(renderPage)

    LaunchedEffect(documentId, pageIndex, targetWidthPx) {
        if (documentId == null) {
            thumbnail = null
            return@LaunchedEffect
        }
        val rendered = withContext(renderDispatcher) {
            latestRender(pageIndex, targetWidthPx, RenderWorkQueue.Priority.THUMBNAIL)
        }
        // As with the main page bitmaps we avoid recycling the previous thumbnail here to
        // keep the draw pipeline stable. The DisposableEffect below cleans up once the
        // composable leaves composition.
        thumbnail = rendered
    }

    DisposableEffect(documentId, pageIndex) {
        onDispose {
            // Avoid recycling thumbnails directly. Compose can issue a final draw after the
            // composable leaves the composition, and calling Bitmap.recycle() here would crash
            // with "Canvas: trying to use a recycled bitmap" on large documents. Clearing the
            // reference allows the bitmap to be GC'd once it is no longer in use.
            thumbnail = null
        }
    }

    val outlineColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val elevation = if (isSelected) 8.dp else 2.dp
    val baseModifier = modifier
        .width(THUMBNAIL_WIDTH)
        .height(THUMBNAIL_HEIGHT)
        .clip(MaterialTheme.shapes.medium)
        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(elevation))
        .border(
            width = if (isSelected) 2.dp else 1.dp,
            color = outlineColor,
            shape = MaterialTheme.shapes.medium
        )
        .clickable(onClick = onSelect)

    Box(modifier = baseModifier) {
        val imageBitmap = remember(thumbnail) {
            thumbnail?.takeUnless(Bitmap::isRecycled)?.asImageBitmap()
        }
        if (imageBitmap != null) {
            Image(
                bitmap = imageBitmap,
                contentDescription = stringResource(
                    id = R.string.thumbnail_page_content_description,
                    pageIndex + 1
                ),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }

        IconButton(
            onClick = onToggleBookmark,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            val icon = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder
            Icon(
                imageVector = icon,
                contentDescription = stringResource(id = R.string.toggle_bookmark),
                tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Text(
            text = (pageIndex + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .clip(MaterialTheme.shapes.small)
                .background(
                    MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
                        .copy(alpha = 0.85f)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PageTransitionScrim(modifier: Modifier, reverse: Boolean = false) {
    val surface = MaterialTheme.colorScheme.surface
    val gradient = remember(surface, reverse) {
        if (reverse) {
            listOf(Color.Transparent, surface.copy(alpha = 0.92f))
        } else {
            listOf(surface.copy(alpha = 0.92f), Color.Transparent)
        }
    }
    val gradientBrush = remember(gradient) {
        Brush.verticalGradient(colors = gradient)
    }

    Box(
        modifier = modifier.background(
            brush = gradientBrush
        )
    )
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
    renderPage: suspend (Int, Int, RenderWorkQueue.Priority) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    renderDispatcher: CoroutineDispatcher,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    animationsEnabled: Boolean
) {
    val density = LocalDensity.current
    val latestRender by rememberUpdatedState(renderPage)
    val latestRequest by rememberUpdatedState(requestPageSize)
    val latestStroke by rememberUpdatedState(onStrokeFinished)
    val documentId = state.documentId
    var pageBitmap by remember(documentId, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var pageSize by remember(documentId, pageIndex) { mutableStateOf<Size?>(null) }
    var isLoading by remember(documentId, pageIndex) { mutableStateOf(false) }
    val isMalformed by remember(state.malformedPages, pageIndex) {
        derivedStateOf { pageIndex in state.malformedPages }
    }

    DisposableEffect(documentId, pageIndex) {
        onDispose {
            // Avoid explicitly recycling the bitmap here. When the composable leaves the
            // composition Compose can still issue a final draw pass, and calling
            // Bitmap.recycle() results in "Canvas: trying to use a recycled bitmap"
            // crashes on large documents. Clearing the reference lets the bitmap be
            // collected naturally without tripping the renderer.
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
        LaunchedEffect(documentId, state.malformedPages, pageIndex, widthPx) {
            if (documentId == null) {
                pageBitmap = null
                pageSize = null
                isLoading = false
                return@LaunchedEffect
            }
            if (widthPx <= 0) return@LaunchedEffect
            if (isMalformed) {
                val size = withContext(renderDispatcher) { latestRequest(pageIndex) }
                pageSize = size
                pageBitmap = null
                isLoading = false
                return@LaunchedEffect
            }
            isLoading = true
            val (size, rendered) = withContext(renderDispatcher) {
                val requested = latestRequest(pageIndex)
                val priority = when {
                    pageIndex == state.currentPage -> RenderWorkQueue.Priority.VISIBLE_PAGE
                    kotlin.math.abs(pageIndex - state.currentPage) == 1 -> RenderWorkQueue.Priority.NEARBY_PAGE
                    else -> RenderWorkQueue.Priority.THUMBNAIL
                }
                val bitmap = latestRender(pageIndex, widthPx, priority)
                requested to bitmap
            }
            pageSize = size
            // Avoid recycling the previous bitmap here. Doing so can race with Compose's
            // draw pipeline and trigger "Canvas: trying to use a recycled bitmap" crashes
            // on large documents while the next frame is being composed. We instead allow
            // the old bitmap to be released once it falls out of scope or when the composable
            // leaves the composition (see the DisposableEffect above).
            pageBitmap = rendered
            isLoading = false
        }

        val aspect = pageBitmap?.let { bitmap ->
            bitmap.height.toFloat() / bitmap.width.coerceAtLeast(1)
        } ?: pageSize?.let { size ->
            size.height.toFloat() / size.width.coerceAtLeast(1)
        } ?: 1.4142f
        val isActive = state.currentPage == pageIndex
        val gradientAlpha by animateFloatAsState(
            targetValue = if (isActive) 1f else 0f,
            animationSpec = tween(durationMillis = 550, easing = LinearOutSlowInEasing),
            label = "PageTransitionGradient"
        )
        val pageShape = MaterialTheme.shapes.extraLarge
        val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
        val colorScheme = MaterialTheme.colorScheme
        val gradientColors = remember(colorScheme.primary, colorScheme.secondary) {
            listOf(
                colorScheme.primary.copy(alpha = 0.4f),
                Color.Transparent,
                colorScheme.secondary.copy(alpha = 0.4f)
            )
        }
        var showPageFlip by remember(state.documentId, pageIndex) { mutableStateOf(false) }
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.page_flip))

        LaunchedEffect(isActive) {
            if (isActive) {
                showPageFlip = true
                delay(600)
                showPageFlip = false
            } else {
                showPageFlip = false
            }
        }

        val totalPages = state.pageCount.takeIf { it > 0 } ?: (pageIndex + 1)
        val pageDescription = stringResource(
            id = R.string.reader_page_content_description,
            (pageIndex + 1).coerceAtLeast(1),
            totalPages
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.2f, 5f))
                .semantics { contentDescription = pageDescription }
                .shadow(
                    elevation = 18.dp,
                    shape = pageShape,
                    clip = false,
                    ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = pageShape
                )
                .clip(pageShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(elevatedSurface, MaterialTheme.colorScheme.surface)
                    )
                )
        ) {
            val imageBitmap = remember(pageBitmap) {
                pageBitmap?.takeUnless(Bitmap::isRecycled)?.asImageBitmap()
            }
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                when {
                    isMalformed -> {
                        val placeholderText = stringResource(id = R.string.page_render_malformed_placeholder)
                        Text(
                            text = placeholderText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        )
                    }

                    isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                }
            }

            val gradientBrush = remember(gradientColors) {
                Brush.verticalGradient(colors = gradientColors)
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (gradientAlpha > 0.01f) {
                    drawRect(
                        brush = gradientBrush,
                        alpha = gradientAlpha * 0.35f
                    )
                }
            }

            val pageMatches = state.searchResults
                .firstOrNull { it.pageIndex == pageIndex }
                ?.matches
                .orEmpty()
            val highlightDescription = if (pageMatches.isNotEmpty()) {
                stringResource(
                    id = R.string.search_highlight_description,
                    pageMatches.size
                )
            } else {
                null
            }
            SearchHighlightOverlay(
                modifier = Modifier.fillMaxSize(),
                matches = pageMatches,
                contentDescription = highlightDescription
            )

            val annotationDescription = stringResource(
                id = R.string.annotation_canvas_description,
                pageIndex + 1
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
                },
                enabled = !state.talkBackIntegrationEnabled,
                contentDescription = annotationDescription
            )

            val showPageFlipAnimation = showPageFlip && composition != null
            val flipContent: @Composable () -> Unit = {
                composition?.let {
                    LottieAnimation(
                        composition = it,
                        iterations = 1,
                        speed = 1.2f,
                        modifier = Modifier.size(160.dp)
                    )
                }
            }
            if (animationsEnabled) {
                AnimatedVisibility(
                    visible = showPageFlipAnimation,
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(animationSpec = tween(220)) + scaleIn(
                        initialScale = 0.85f,
                        animationSpec = tween(220)
                    ),
                    exit = fadeOut(animationSpec = tween(200)) + scaleOut(
                        targetScale = 0.95f,
                        animationSpec = tween(200)
                    )
                ) {
                    flipContent()
                }
            } else if (showPageFlipAnimation) {
                Box(modifier = Modifier.align(Alignment.Center)) {
                    flipContent()
                }
            }
        }
    }
}
