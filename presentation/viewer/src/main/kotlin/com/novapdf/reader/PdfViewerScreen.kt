package com.novapdf.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import android.util.Patterns
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.novapdf.reader.presentation.viewer.R
import com.novapdf.reader.DocumentStatus
import com.novapdf.reader.accessibility.HapticFeedbackManager
import com.novapdf.reader.accessibility.rememberHapticFeedbackManager
import com.novapdf.reader.features.annotations.AnnotationOverlay
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PdfOutlineNode
import com.novapdf.reader.model.PdfRenderProgress
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ONBOARDING_PREFS = "nova_onboarding_prefs"
private const val ONBOARDING_COMPLETE_KEY = "onboarding_complete"
private const val PDF_REPAIR_URL = "https://www.ilovepdf.com/repair-pdf"
private val THUMBNAIL_WIDTH = 96.dp
private val THUMBNAIL_HEIGHT = 128.dp
private const val PAGE_RENDER_PARALLELISM = 2
private const val THUMBNAIL_PARALLELISM = 2
private const val VIEWPORT_WIDTH_DEBOUNCE_MS = 120L

@OptIn(ExperimentalCoroutinesApi::class)
private val pageRenderDispatcher = Dispatchers.IO.limitedParallelism(PAGE_RENDER_PARALLELISM)

@OptIn(ExperimentalCoroutinesApi::class)
private val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(THUMBNAIL_PARALLELISM)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerRoute(
    viewModel: PdfViewerViewModel,
    snackbarHost: SnackbarHostState,
    onOpenLocalDocument: () -> Unit,
    onOpenCloudDocument: () -> Unit,
    onOpenRemoteDocument: (String) -> Unit,
    onDismissError: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PdfViewerScreen(
        state = uiState,
        snackbarHost = snackbarHost,
        messageFlow = viewModel.messageEvents,
        onOpenLocalDocument = onOpenLocalDocument,
        onOpenCloudDocument = onOpenCloudDocument,
        onOpenRemoteDocument = onOpenRemoteDocument,
        onDismissError = onDismissError,
        onPageChange = { viewModel.onPageFocused(it) },
        onStrokeFinished = { viewModel.addAnnotation(it) },
        onSaveAnnotations = { viewModel.persistAnnotations() },
        onSearch = { viewModel.search(it) },
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
        dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    state: PdfViewerUiState,
    snackbarHost: SnackbarHostState,
    messageFlow: Flow<UiMessage>,
    onOpenLocalDocument: () -> Unit,
    onOpenCloudDocument: () -> Unit,
    onOpenRemoteDocument: (String) -> Unit,
    onDismissError: () -> Unit,
    onPageChange: (Int) -> Unit,
    onStrokeFinished: (AnnotationCommand) -> Unit,
    onSaveAnnotations: () -> Unit,
    onSearch: (String) -> Unit,
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
    dynamicColorSupported: Boolean
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    val requestDocument: () -> Unit = { showSourceDialog = true }
    val latestOnPageChange by rememberUpdatedState(onPageChange)
    val baseDensity = LocalDensity.current
    val adjustedDensity = remember(baseDensity, state.fontScale) {
        Density(density = baseDensity.density, fontScale = state.fontScale)
    }

    CompositionLocalProvider(LocalDensity provides adjustedDensity) {
        val context = LocalContext.current
        val hapticFeedback = LocalHapticFeedback.current
        val accessibilityManager = remember(context) {
            (context.applicationContext.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? AccessibilityManager)
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
        val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
        val totalSearchResults = state.searchResults.sumOf { it.matches.size }
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
        val playAdaptiveSummary = remember(state, echoModeController, fallbackHaptics) {
            {
                val summary = state.echoSummary()
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
            showOnboarding = false
        }

        DisposableEffect(echoModeController) {
            onDispose { echoModeController.shutdown() }
        }

        Scaffold(
            topBar = {
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
                        IconButton(onClick = { showSourceDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.FileOpen,
                                contentDescription = stringResource(id = R.string.open_pdf)
                            )
                        }
                        IconButton(onClick = { showAccessibilitySheet = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Accessibility,
                                contentDescription = stringResource(id = R.string.accessibility_open_options)
                            )
                        }
                        if (selectedDestination == MainDestination.Reader) {
                            val hasDocument = state.documentId != null
                            IconButton(onClick = onSaveAnnotations, enabled = hasDocument) {
                                Icon(
                                    imageVector = Icons.Outlined.Download,
                                    contentDescription = stringResource(id = R.string.save_annotations)
                                )
                            }
                            if (hasDocument) {
                                IconButton(
                                    onClick = { showOutlineSheet = true },
                                    enabled = state.outline.isNotEmpty()
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.List,
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
                            IconButton(onClick = { onToggleBookmark(state.currentPage) }, enabled = hasDocument) {
                                val bookmarked = state.bookmarks.contains(state.currentPage)
                                val icon = if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder
                                Icon(
                                    imageVector = icon,
                                    contentDescription = stringResource(id = R.string.toggle_bookmark)
                                )
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {
                    selectedDestination = MainDestination.Reader
                    requestSearchFocus = true
                }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(id = R.string.search_hint)
                    )
                }
            },
            floatingActionButtonPosition = FabPosition.End,
            bottomBar = {
                NavigationBar {
                    navigationItems.forEach { item ->
                        val labelText = stringResource(id = item.labelRes)
                        NavigationBarItem(
                            selected = selectedDestination == item.destination,
                            onClick = { selectedDestination = item.destination },
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
            },
            snackbarHost = { SnackbarHost(snackbarHost) }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                when (selectedDestination) {
                    MainDestination.Home -> {
                        HomeContent(
                            onOpenDocument = requestDocument,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    MainDestination.Reader -> {
                        ReaderContent(
                            state = state,
                            searchQuery = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                onSearch(it)
                            },
                            totalSearchResults = totalSearchResults,
                            onOpenDocument = requestDocument,
                            onPlayAdaptiveSummary = playAdaptiveSummary,
                            onOpenAccessibilityOptions = { showAccessibilitySheet = true },
                            focusRequester = searchFocusRequester,
                            requestFocus = requestSearchFocus,
                            onFocusHandled = { requestSearchFocus = false },
                            onPageChange = latestOnPageChange,
                            onStrokeFinished = onStrokeFinished,
                            onToggleBookmark = onToggleBookmark,
                            renderPage = renderPage,
                            requestPageSize = requestPageSize,
                            onViewportWidthChanged = onViewportWidthChanged,
                            onPrefetchPages = onPrefetchPages,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    MainDestination.Annotations -> {
                        AnnotationsContent(
                            annotationCount = state.activeAnnotations.size,
                            modifier = Modifier.fillMaxSize()
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
                            hapticFeedbackManager = hapticManager,
                            onDynamicColorChanged = onToggleDynamicColor,
                            onHighContrastChanged = onToggleHighContrast,
                            onTalkBackIntegrationChanged = onToggleTalkBackIntegration,
                            onFontScaleChanged = onFontScaleChanged,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

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

                DocumentStatusHost(
                    status = state.documentStatus,
                    onDismissError = onDismissError
                )

                if (showOnboarding) {
                    OnboardingOverlay(
                        pages = onboardingPages,
                        pagerState = pagerState,
                        onSkip = completeOnboarding,
                        onFinish = completeOnboarding
                    )
                }
            }
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
            onConfirm = { url ->
                showUrlDialog = false
                onOpenRemoteDocument(url)
            }
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
                    modifier = Modifier.fillMaxHeight(0.95f)
                )
            }
        }
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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(id = R.string.open_source_device))
                }
                FilledTonalButton(
                    onClick = onSelectCloud,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = stringResource(id = R.string.open_source_cloud))
                }
                FilledTonalButton(
                    onClick = onSelectRemote,
                    modifier = Modifier.fillMaxWidth()
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
    onConfirm: (String) -> Unit,
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
                    onConfirm(trimmedUrl)
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
private fun DocumentStatusHost(
    status: DocumentStatus,
    onDismissError: () -> Unit
) {
    when (status) {
        is DocumentStatus.Loading -> LoadingOverlay(status)
        is DocumentStatus.Error -> DocumentErrorDialog(
            message = status.message,
            onDismiss = onDismissError
        )
        DocumentStatus.Idle -> Unit
    }
}

@Composable
private fun DocumentErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.error_pdf_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(id = R.string.error_pdf_dialog_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_try_again))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PDF_REPAIR_URL))
                try {
                    context.startActivity(intent)
                } catch (error: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.error_no_browser, Toast.LENGTH_LONG).show()
                } finally {
                    onDismiss()
                }
            }) {
                Text(text = stringResource(id = R.string.action_repair_pdf))
            }
        },
        properties = DialogProperties(dismissOnClickOutside = false)
    )
}

@Composable
private fun LoadingOverlay(status: DocumentStatus.Loading) {
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
            Surface(
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .widthIn(min = 260.dp)
                    .wrapContentHeight(),
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 28.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = message,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    AnimatedVisibility(visible = progressValue != null) {
                        if (progressValue != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LinearProgressIndicator(
                                    progress = { progressValue },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = stringResource(
                                        id = R.string.loading_progress,
                                        (progressValue * 100).roundToInt()
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
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
                    .semantics { contentDescription = resultsText }
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
    onStrokeFinished: (AnnotationCommand) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    renderPage: suspend (Int, Int, RenderWorkQueue.Priority) -> Bitmap?,
    requestPageSize: suspend (Int) -> Size?,
    onViewportWidthChanged: (Int) -> Unit,
    onPrefetchPages: (List<Int>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Top
    ) {
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

        if (state.pageCount == 0) {
            EmptyState(onOpenDocument)
        } else {
            PdfPager(
                modifier = Modifier.fillMaxSize(),
                state = state,
                onPageChange = onPageChange,
                onStrokeFinished = onStrokeFinished,
                onToggleBookmark = onToggleBookmark,
                renderPage = renderPage,
                requestPageSize = requestPageSize,
                onViewportWidthChanged = onViewportWidthChanged,
                onPrefetchPages = onPrefetchPages
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
            modifier = Modifier.semantics { contentDescription = statusText }
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
    modifier: Modifier = Modifier
) {
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
            modifier = Modifier
                .padding(bottom = 24.dp)
                .semantics { contentDescription = bodyText }
        )
        Button(onClick = onOpenDocument) {
            val buttonText = stringResource(id = R.string.empty_state_button)
            Text(
                text = buttonText,
                modifier = Modifier.semantics { contentDescription = buttonText }
            )
        }
    }
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
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnboardingOverlay(
    pages: List<OnboardingPage>,
    pagerState: PagerState,
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
            val coroutineScope = rememberCoroutineScope()
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
                    TextButton(onClick = onSkip) {
                        val skipText = stringResource(id = R.string.onboarding_skip)
                        Text(
                            text = skipText,
                            modifier = Modifier.semantics { contentDescription = skipText }
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth()
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
                            val isSelected = pagerState.currentPage == index
                            Box(
                                modifier = Modifier
                                    .size(if (isSelected) 12.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                    )
                            )
                            if (index != pages.lastIndex) {
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                    }

                    val isLastPage = pagerState.currentPage == pages.lastIndex
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
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
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
    Settings
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
    modifier: Modifier = Modifier
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
            initiallyExpanded = true
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
            description = stringResource(id = R.string.high_contrast_description)
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
            description = stringResource(id = R.string.accessibility_section_talkback_description)
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
            description = stringResource(id = R.string.accessibility_section_font_scale_description)
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
    }
}

@Composable
private fun ExpandableSettingSection(
    title: String,
    description: String,
    initiallyExpanded: Boolean = false,
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
        Column(modifier = Modifier.animateContentSize()) {
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
            AnimatedVisibility(visible = expanded) {
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

private fun AccessibilityManager?.sendAnnouncement(message: String) {
    val manager = this ?: return
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
        text.add(message)
    }
    manager.sendAccessibilityEvent(event)
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
        modifier.semantics { this.contentDescription = contentDescription }
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
    onStrokeFinished: (AnnotationCommand) -> Unit,
    onToggleBookmark: (Int) -> Unit,
    renderPage: suspend (Int, Int, RenderWorkQueue.Priority) -> Bitmap?,
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
    val latestBookmarkToggle by rememberUpdatedState(onToggleBookmark)

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
            // Debounce layout changes to avoid rapid recomposition loops during rotations or animations.
            .debounce(VIEWPORT_WIDTH_DEBOUNCE_MS)
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

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
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

        if (state.pageCount in 1..LARGE_DOCUMENT_PAGE_THRESHOLD) {
            ThumbnailStrip(
                state = state,
                lazyListState = lazyListState,
                renderPage = latestRenderPage,
                onToggleBookmark = latestBookmarkToggle,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun ThumbnailStrip(
    state: PdfViewerUiState,
    lazyListState: LazyListState,
    renderPage: suspend (Int, Int, RenderWorkQueue.Priority) -> Bitmap?,
    onToggleBookmark: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.pageCount <= 0) return
    var selectedPage by rememberSaveable(state.documentId) { mutableStateOf(state.currentPage) }
    var showBookmarksOnly by rememberSaveable(state.documentId) { mutableStateOf(false) }

    LaunchedEffect(state.documentId, state.currentPage) {
        selectedPage = state.currentPage
    }

    val pagesToDisplay = remember(state.pageCount, state.bookmarks, showBookmarksOnly) {
        val allPages = (0 until state.pageCount).toList()
        if (showBookmarksOnly) {
            allPages.filter { page -> page in state.bookmarks }
        } else {
            allPages
        }
    }

    val coroutineScope = rememberCoroutineScope()
    val surfaceColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)

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
                    Text(
                        text = stringResource(id = R.string.thumbnail_strip_filter_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = showBookmarksOnly,
                        onCheckedChange = { showBookmarksOnly = it }
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
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pagesToDisplay) { pageIndex ->
                        ThumbnailItem(
                            documentId = state.documentId,
                            pageIndex = pageIndex,
                            isSelected = pageIndex == selectedPage,
                            isBookmarked = state.bookmarks.contains(pageIndex),
                            renderPage = renderPage,
                            onSelect = {
                                selectedPage = pageIndex
                                coroutineScope.launch {
                                    lazyListState.animateScrollToItem(pageIndex)
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
        val rendered = withContext(thumbnailDispatcher) {
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
        val bitmap = thumbnail
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
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

    Box(
        modifier = modifier.background(
            brush = Brush.verticalGradient(colors = gradient)
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
    onStrokeFinished: (AnnotationCommand) -> Unit
) {
    val density = LocalDensity.current
    val latestRender by rememberUpdatedState(renderPage)
    val latestRequest by rememberUpdatedState(requestPageSize)
    val latestStroke by rememberUpdatedState(onStrokeFinished)
    var pageBitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var pageSize by remember(pageIndex) { mutableStateOf<Size?>(null) }
    var isLoading by remember(pageIndex) { mutableStateOf(false) }
    val isMalformed = remember(state.malformedPages, pageIndex) {
        pageIndex in state.malformedPages
    }

    DisposableEffect(pageIndex) {
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
        LaunchedEffect(state.documentId, state.malformedPages, pageIndex, widthPx) {
            if (state.documentId == null) {
                pageBitmap = null
                pageSize = null
                isLoading = false
                return@LaunchedEffect
            }
            if (widthPx <= 0) return@LaunchedEffect
            if (isMalformed) {
                val size = withContext(pageRenderDispatcher) { latestRequest(pageIndex) }
                pageSize = size
                pageBitmap = null
                isLoading = false
                return@LaunchedEffect
            }
            isLoading = true
            val (size, rendered) = withContext(pageRenderDispatcher) {
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.2f, 5f))
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

            Canvas(modifier = Modifier.fillMaxSize()) {
                if (gradientAlpha > 0.01f) {
                    drawRect(
                        brush = Brush.verticalGradient(colors = gradientColors),
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

            AnimatedVisibility(
                visible = showPageFlip && composition != null,
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
                composition?.let {
                    LottieAnimation(
                        composition = it,
                        iterations = 1,
                        speed = 1.2f,
                        modifier = Modifier.size(160.dp)
                    )
                }
            }
        }
    }
}