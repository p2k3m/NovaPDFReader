package com.novapdf.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novapdf.reader.presentation.viewer.R as ViewerR
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.ui.theme.NovaPdfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.emptyFlow

@RunWith(AndroidJUnit4::class)
class PdfViewerScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsEmptyStateWhenNoDocument() {
        composeRule.setContent {
            NovaPdfTheme {
                PdfViewerScreen(
                    state = PdfViewerUiState(),
                    snackbarHost = androidx.compose.material3.SnackbarHostState(),
                    messageFlow = emptyFlow(),
                    onOpenLocalDocument = {},
                    onOpenCloudDocument = {},
                    onOpenRemoteDocument = { _ -> },
                    onOpenLastDocument = {},
                    onDismissError = {},
                    onConfirmLargeDownload = {},
                    onDismissLargeDownload = {},
                    onPageChange = {},
                    onPageCommit = {},
                    onStrokeFinished = {},
                    onSaveAnnotations = {},
                    onSearch = {},
                    onCancelIndexing = {},
                    onToggleBookmark = {},
                    onOutlineDestinationSelected = {},
                    onExportDocument = { true },
                    renderPage = { _, _, _ -> null },
                    requestPageSize = { null },
                    onViewportWidthChanged = {},
                    onPrefetchPages = { _, _ -> },
                    onToggleDynamicColor = {},
                    onToggleHighContrast = {},
                    onToggleTalkBackIntegration = {},
                    onFontScaleChanged = {},
                    onDumpDiagnostics = {},
                    onToggleDevDiagnostics = {},
                    onToggleDevCaches = {},
                    onToggleDevArtificialDelay = {},
                    dynamicColorSupported = true
                )
            }
        }

        val emptyStateMessage = composeRule.activity.getString(ViewerR.string.empty_state_message)
        composeRule.onNodeWithText(emptyStateMessage).assertIsDisplayed()
        val accessibilityTrigger = composeRule.activity.getString(ViewerR.string.accessibility_open_options)
        composeRule.onNodeWithText(accessibilityTrigger).assertIsDisplayed()
    }
}
