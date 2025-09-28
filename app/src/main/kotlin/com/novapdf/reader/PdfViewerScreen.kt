package com.novapdf.reader

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novapdf.reader.R
import com.novapdf.reader.accessibility.HapticFeedbackManager
import com.novapdf.reader.accessibility.rememberHapticFeedbackManager
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.PdfOutlineNode
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
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
    onToggleTalkBackIntegration: (Boolean) -> Unit,
    onFontScaleChanged: (Float) -> Unit,
    dynamicColorSupported: Boolean
) {
    var searchQuery by remember { mutableStateOf("") }
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
                    IconButton(onClick = { showAccessibilitySheet = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Accessibility,
                            contentDescription = stringResource(id = R.string.accessibility_open_options)
                        )
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

                FilledTonalButton(
                    onClick = { showAccessibilitySheet = true },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                ) {
                    Text(text = stringResource(id = R.string.accessibility_open_options))
                }

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

    if (showAccessibilitySheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAccessibilitySheet = false },
            sheetState = sheetState,
            modifier = Modifier.fillMaxHeight()
        ) {
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
                onFontScaleChanged = onFontScaleChanged
            )
        }
    }
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
    onFontScaleChanged: (Float) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.95f)
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
    val event = AccessibilityEvent.obtain().apply {
        eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
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
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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