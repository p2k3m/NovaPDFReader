package com.novapdf.reader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val defaultFontFamily = FontFamily.SansSerif

val Typography = Typography(
    displayLarge = Typography().displayLarge.copy(fontFamily = defaultFontFamily),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = defaultFontFamily, lineHeight = 24.sp),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.SemiBold)
)
