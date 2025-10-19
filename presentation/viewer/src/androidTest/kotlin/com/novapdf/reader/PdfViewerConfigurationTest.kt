package com.novapdf.reader

import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.ui.theme.NovaPdfTheme
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PdfViewerConfigurationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val supportedLocales = listOf(
        Locale.forLanguageTag("en-US"),
        Locale.forLanguageTag("es-419"),
        Locale.forLanguageTag("pt-BR"),
        Locale.JAPAN,
        Locale.KOREA,
    )

    private val orientations = listOf(
        Configuration.ORIENTATION_PORTRAIT,
        Configuration.ORIENTATION_LANDSCAPE,
    )

    private val nightModes = listOf(
        Configuration.UI_MODE_NIGHT_NO,
        Configuration.UI_MODE_NIGHT_YES,
    )

    private val screenSizes = listOf(
        Configuration.SCREENLAYOUT_SIZE_NORMAL,
        Configuration.SCREENLAYOUT_SIZE_LARGE,
    )

    private val fontScales = listOf(1f, 1.3f)
    private val accessibilityStates = listOf(false, true)

    @Test
    fun rendersAcrossLocalesOrientationsAndAccessibilityStates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val baseConfiguration = instrumentation.targetContext.resources.configuration

        supportedLocales.forEach { locale ->
            orientations.forEach { orientation ->
                nightModes.forEach { nightMode ->
                    screenSizes.forEach { screenSize ->
                        fontScales.forEach { fontScale ->
                            accessibilityStates.forEach { talkBackEnabled ->
                                accessibilityStates.forEach { highContrastEnabled ->
                                    val configuration = Configuration(baseConfiguration).apply {
                                        setLocales(LocaleList(locale))
                                        this.orientation = orientation
                                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                                        screenLayout =
                                            (screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK.inv()) or screenSize
                                        this.fontScale = fontScale
                                    }

                                    composeRule.setContent {
                                        ConfigurationHarness(configuration = configuration) {
                                            NovaPdfTheme(
                                                useDarkTheme = nightMode == Configuration.UI_MODE_NIGHT_YES,
                                                dynamicColor = true,
                                                highContrast = highContrastEnabled,
                                                seedColor = Color(0xFF6750A4)
                                            ) {
                                                PdfViewerScreen(
                                                    state = PdfViewerUiState(
                                                        preferencesReady = true,
                                                        isNightMode = nightMode == Configuration.UI_MODE_NIGHT_YES,
                                                        dynamicColorEnabled = true,
                                                        highContrastEnabled = highContrastEnabled,
                                                        talkBackIntegrationEnabled = talkBackEnabled,
                                                        fontScale = fontScale,
                                                        bookmarks = emptyList(),
                                                    ),
                                                    snackbarHost = SnackbarHostState(),
                                                    messageFlow = emptyFlow(),
                                                    onOpenLocalDocument = {},
                                                    onOpenCloudDocument = {},
                                                    onOpenLastDocument = {},
                                                    onOpenRemoteDocument = {},
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
                                                    onExportDocument = { false },
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
                                                    renderDispatcher = Dispatchers.IO,
                                                    dynamicColorSupported = true,
                                                )
                                            }
                                        }
                                    }

                                    composeRule.waitForIdle()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationHarness(
    configuration: Configuration,
    content: @Composable () -> Unit,
) {
    val baseDensity = LocalDensity.current
    val layoutDirection = if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    val densityValue = if (configuration.densityDpi > 0) {
        configuration.densityDpi / 160f
    } else {
        baseDensity.density
    }

    val fontScale = if (configuration.fontScale > 0) {
        configuration.fontScale
    } else {
        baseDensity.fontScale
    }

    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides layoutDirection,
        LocalDensity provides Density(density = densityValue, fontScale = fontScale),
    ) {
        content()
    }
}
