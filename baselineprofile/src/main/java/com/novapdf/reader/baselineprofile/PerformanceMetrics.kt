package com.novapdf.reader.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope

private const val NANOSECONDS_PER_SECOND = 1_000_000_000.0
private const val WARM_UP_FRAME_COUNT = 5

internal const val TIME_TO_FIRST_PAGE_TARGET_MS = 4_000L
internal const val TIME_TO_FIRST_PAGE_VARIANCE_MS = 750L

internal const val STEADY_STATE_SCROLL_TARGET_FPS = 55.0
internal const val STEADY_STATE_SCROLL_VARIANCE_FPS = 5.0

internal const val PEAK_MEMORY_TARGET_MB = 600.0
internal const val PEAK_MEMORY_VARIANCE_MB = 75.0

internal fun List<out Number>.median(): Double {
    require(isNotEmpty()) { "Median is undefined for an empty collection." }
    val sorted = map { it.toDouble() }.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

internal fun MacrobenchmarkScope.measureSteadyStateScrollFps(): Double {
    device.waitForIdle()
    device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE reset")
    device.waitForIdle()

    repeat(4) {
        exerciseReaderContent()
    }
    device.waitForIdle()

    val frameStatsOutput = device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE framestats")
    val frameTimestamps = parseFrameCompletionTimestamps(frameStatsOutput)
    val steadyState = frameTimestamps.drop(WARM_UP_FRAME_COUNT)
    if (steadyState.size < 2) {
        throw IllegalStateException(
            "Insufficient frame data captured for FPS calculation (frames=${frameTimestamps.size})."
        )
    }

    val totalDurationNs = (steadyState.last() - steadyState.first()).coerceAtLeast(0)
    require(totalDurationNs > 0) { "Frame completion timestamps did not advance." }
    val averageFrameDurationNs = totalDurationNs / (steadyState.size - 1).toDouble()
    return NANOSECONDS_PER_SECOND / averageFrameDurationNs
}

internal fun MacrobenchmarkScope.captureTotalPssMb(): Double {
    val meminfoOutput = device.executeShellCommand("dumpsys meminfo $TARGET_PACKAGE")
    val match = MEMINFO_TOTAL_PSS_REGEX.find(meminfoOutput)
        ?: throw IllegalStateException(
            "Unable to parse TOTAL PSS from dumpsys meminfo output.\n${meminfoOutput.take(512)}"
        )
    val kilobytes = match.groupValues[1].toLong()
    return kilobytes / 1024.0
}

private val MEMINFO_TOTAL_PSS_REGEX = Regex("TOTAL\\s+PSS:\\s*(\\d+)", RegexOption.IGNORE_CASE)

private fun parseFrameCompletionTimestamps(output: String): List<Long> {
    val marker = "---PROFILEDATA---"
    val lastMarkerIndex = output.lastIndexOf(marker)
    if (lastMarkerIndex == -1) {
        return emptyList()
    }
    val section = output.substring(lastMarkerIndex + marker.length)
    val lines = section.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    val headerIndex = lines.indexOfFirst { it.startsWith("Flags") }
    if (headerIndex == -1 || headerIndex + 1 >= lines.size) {
        return emptyList()
    }

    return lines.subList(headerIndex + 1, lines.size)
        .takeWhile { line -> line.firstOrNull()?.isDigit() == true }
        .mapNotNull { line ->
            val columns = line.split(',')
            columns.getOrNull(13)?.trim()?.toLongOrNull()
        }
        .filter { it > 0L }
}
