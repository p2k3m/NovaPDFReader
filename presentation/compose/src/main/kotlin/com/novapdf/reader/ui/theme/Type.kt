package com.novapdf.reader.ui.theme

import com.novapdf.reader.logging.NovaLog
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.novapdf.reader.ui.compose.R

private val robotoFlexFamily = FontFamily(
    Font(
        resId = R.font.roboto_flex_variable,
        weight = FontWeight.Light
    ),
    Font(
        resId = R.font.roboto_flex_variable,
        weight = FontWeight.Normal
    ),
    Font(
        resId = R.font.roboto_flex_variable,
        weight = FontWeight.Medium
    ),
    Font(
        resId = R.font.roboto_flex_variable,
        weight = FontWeight.SemiBold
    ),
    Font(
        resId = R.font.roboto_flex_variable,
        weight = FontWeight.Bold
    )
)

private val fallbackTypography = createNovaPdfTypography(FontFamily.SansSerif)

@Composable
fun rememberNovaPdfTypography(): Typography {
    val context = LocalContext.current
    return remember(context) {
        val fontFamily = runCatching {
            ResourcesCompat.getFont(context, R.font.roboto_flex_variable)
            robotoFlexFamily
        }.getOrElse { error ->
            NovaLog.w(TAG, "Unable to load Roboto Flex font; falling back to platform sans-serif.", error)
            FontFamily.SansSerif
        }

        if (fontFamily == FontFamily.SansSerif) {
            fallbackTypography
        } else {
            createNovaPdfTypography(fontFamily)
        }
    }
}

private fun createNovaPdfTypography(fontFamily: FontFamily): Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = fontFamily),
        displayMedium = displayMedium.copy(fontFamily = fontFamily),
        displaySmall = displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
        headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
        titleLarge = titleLarge.copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = fontFamily, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontFamily = fontFamily, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = fontFamily, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = bodySmall.copy(fontFamily = fontFamily),
        labelLarge = labelLarge.copy(fontFamily = fontFamily, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = fontFamily),
        labelSmall = labelSmall.copy(fontFamily = fontFamily)
    )
}

private const val TAG = "NovaPdfTypography"
