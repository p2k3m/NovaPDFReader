package com.novapdf.reader

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.novapdf.reader.model.AnnotationCommand
import com.novapdf.reader.model.SearchResult
import com.novapdf.reader.ui.theme.NovaPdfTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
                    onOpenDocument = {},
                    onPageChange = {},
                    onStrokeFinished = {},
                    onSaveAnnotations = {},
                    onSearch = {},
                    onToggleBookmark = {},
                    renderTile = { _, _, _ -> null },
                    requestPageSize = { null },
                    onTileSpecChanged = {}
                )
            }
        }

        composeRule.onNodeWithText("Open a PDF to begin").assertIsDisplayed()
    }
}
