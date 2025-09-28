package com.novapdf.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.novapdf.reader.R

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

val NovaPdfTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = robotoFlexFamily),
        displayMedium = displayMedium.copy(fontFamily = robotoFlexFamily),
        displaySmall = displaySmall.copy(fontFamily = robotoFlexFamily),
        headlineLarge = headlineLarge.copy(fontFamily = robotoFlexFamily),
        headlineMedium = headlineMedium.copy(fontFamily = robotoFlexFamily),
        headlineSmall = headlineSmall.copy(fontFamily = robotoFlexFamily),
        titleLarge = titleLarge.copy(fontFamily = robotoFlexFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = robotoFlexFamily, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontFamily = robotoFlexFamily, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = robotoFlexFamily, lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(fontFamily = robotoFlexFamily),
        bodySmall = bodySmall.copy(fontFamily = robotoFlexFamily),
        labelLarge = labelLarge.copy(fontFamily = robotoFlexFamily, fontWeight = FontWeight.Medium),
        labelMedium = labelMedium.copy(fontFamily = robotoFlexFamily),
        labelSmall = labelSmall.copy(fontFamily = robotoFlexFamily)
    )
}
