package com.novapdf.reader.accessibility

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

class HapticFeedbackManager(private val hapticFeedback: HapticFeedback?) {

    fun onToggleChange(enabled: Boolean) {
        val feedback = hapticFeedback ?: return
        val type = if (enabled) {
            HapticFeedbackType.LongPress
        } else {
            HapticFeedbackType.TextHandleMove
        }
        feedback.performHapticFeedback(type)
    }

    fun onAdjustment() {
        hapticFeedback?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}

@Composable
fun rememberHapticFeedbackManager(): HapticFeedbackManager {
    val hapticFeedback = LocalHapticFeedback.current
    return remember(hapticFeedback) { HapticFeedbackManager(hapticFeedback) }
}
