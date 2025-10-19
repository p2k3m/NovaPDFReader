package com.novapdf.reader

import android.util.Log

/**
 * Emits structured test points during the harness lifecycle so external tooling can synchronise
 * against key milestones.
 */
object HarnessTestPoints {
    private const val TAG = "HarnessTestPoint"
    private const val PREFIX = "HARNESS TESTPOINT: "

    fun emit(point: HarnessTestPoint, detail: String? = null) {
        val payload = buildPayload(point, detail)
        println(payload)
        Log.i(TAG, payload)
    }

    private fun buildPayload(point: HarnessTestPoint, detail: String?): String {
        val sanitizedDetail = detail
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.replace('\n', ' ')
        return buildString {
            append(PREFIX)
            append(point.label)
            if (!sanitizedDetail.isNullOrEmpty()) {
                append(": ")
                append(sanitizedDetail)
            }
        }
    }
}

enum class HarnessTestPoint(val label: String) {
    PRE_INITIALIZATION("pre_initialization"),
    CACHE_READY("cache_ready"),
    UI_LOADED("ui_loaded"),
    READY_FOR_SCREENSHOT("ready_for_screenshot"),
    ERROR_SIGNALED("error_signaled"),
}
