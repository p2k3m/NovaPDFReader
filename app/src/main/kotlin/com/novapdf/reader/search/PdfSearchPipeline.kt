package com.novapdf.reader.search

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.novapdf.reader.model.RectSnapshot
import com.novapdf.reader.model.SearchMatch
import kotlin.collections.LinkedHashSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class TextRunSnapshot(
    val text: String,
    val bounds: List<RectF>
)

internal fun normalizeSearchQuery(query: String): String {
    val builder = NormalizedBuilder()
    builder.append(query)
    return builder.buildText()
}

internal fun collectMatchesFromRuns(
    runs: List<TextRunSnapshot>,
    normalizedQuery: String,
    pageWidth: Int,
    pageHeight: Int
): List<SearchMatch> {
    if (normalizedQuery.isEmpty() || runs.isEmpty() || pageWidth <= 0 || pageHeight <= 0) {
        return emptyList()
    }
    val normalizedRuns = NormalizedRunsBuilder()
    runs.forEachIndexed { index, run ->
        normalizedRuns.append(run.text, index)
        normalizedRuns.finishRun()
    }
    val normalized = normalizedRuns.build()
    if (normalized.text.isEmpty()) {
        return emptyList()
    }
    val matches = mutableListOf<SearchMatch>()
    var nextMatchIndex = 0
    var currentIndex = normalized.text.indexOf(normalizedQuery)
    while (currentIndex >= 0) {
        val end = currentIndex + normalizedQuery.length
        val involvedRuns = LinkedHashSet<Int>()
        for (i in currentIndex until end) {
            val runIndex = normalized.runMapping.getOrNull(i) ?: -1
            if (runIndex >= 0) {
                involvedRuns.add(runIndex)
            }
        }
        if (involvedRuns.isNotEmpty()) {
            val boundingBoxes = ArrayList<RectSnapshot>()
            involvedRuns.forEach { runIndex ->
                val run = runs[runIndex]
                run.bounds.forEach { rect ->
                    boundingBoxes += RectSnapshot(
                        left = rect.left / pageWidth,
                        top = rect.top / pageHeight,
                        right = rect.right / pageWidth,
                        bottom = rect.bottom / pageHeight
                    )
                }
            }
            if (boundingBoxes.isNotEmpty()) {
                matches += SearchMatch(indexInPage = nextMatchIndex++, boundingBoxes = boundingBoxes)
            }
        }
        currentIndex = normalized.text.indexOf(normalizedQuery, currentIndex + 1)
    }
    return matches
}

internal fun detectTextRegions(bitmap: Bitmap): List<RectSnapshot> {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) {
        return emptyList()
    }
    val targetWidth = min(512, max(64, width))
    val targetHeight = max(64, (height.toLong() * targetWidth / max(1, width)).toInt())
    val scaled = if (width == targetWidth && height == targetHeight) {
        bitmap
    } else {
        Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
    try {
        val scaledWidth = scaled.width
        val scaledHeight = scaled.height
        val pixelCount = scaledWidth * scaledHeight
        if (pixelCount <= 0) {
            return emptyList()
        }
        val pixels = IntArray(pixelCount)
        scaled.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
        val luminance = FloatArray(pixelCount)
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            luminance[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        val gradient = FloatArray(pixelCount)
        val rowScores = FloatArray(scaledHeight)
        for (y in 1 until scaledHeight - 1) {
            var rowSum = 0f
            for (x in 1 until scaledWidth - 1) {
                val index = y * scaledWidth + x
                val gx = abs(luminance[index + 1] - luminance[index - 1])
                val gy = abs(luminance[index + scaledWidth] - luminance[index - scaledWidth])
                val magnitude = gx + gy
                gradient[index] = magnitude
                rowSum += magnitude
            }
            rowScores[y] = rowSum / max(1, scaledWidth - 2)
        }
        val rowMax = rowScores.maxOrNull() ?: 0f
        if (rowMax <= 1f) {
            return emptyList()
        }
        val rowThreshold = rowMax * 0.35f
        val detected = mutableListOf<RectF>()
        var y = 0
        while (y < scaledHeight) {
            while (y < scaledHeight && rowScores[y] < rowThreshold) {
                y++
            }
            if (y >= scaledHeight) break
            val startY = y
            while (y < scaledHeight && rowScores[y] >= rowThreshold) {
                y++
            }
            val endY = min(y + 1, scaledHeight)
            if (endY - startY < 2) {
                continue
            }
            val colScores = FloatArray(scaledWidth)
            for (yy in startY until endY) {
                val rowOffset = yy * scaledWidth
                for (x in 1 until scaledWidth - 1) {
                    colScores[x] += gradient[rowOffset + x]
                }
            }
            val colMax = colScores.maxOrNull() ?: 0f
            if (colMax <= 1f) {
                continue
            }
            val colThreshold = colMax * 0.35f
            var x = 0
            while (x < scaledWidth) {
                while (x < scaledWidth && colScores[x] < colThreshold) {
                    x++
                }
                if (x >= scaledWidth) break
                val startX = x
                while (x < scaledWidth && colScores[x] >= colThreshold) {
                    x++
                }
                val endX = min(x + 1, scaledWidth)
                if (endX - startX < 2) {
                    continue
                }
                val rect = RectF(
                    startX / scaledWidth.toFloat(),
                    startY / scaledHeight.toFloat(),
                    endX / scaledWidth.toFloat(),
                    endY / scaledHeight.toFloat()
                )
                mergeRect(detected, rect)
            }
        }
        detected.sortWith(compareBy<RectF> { it.top }.thenBy { it.left })
        val result = ArrayList<RectSnapshot>(detected.size)
        for (rect in detected) {
            if (rect.width() < 0.01f || rect.height() < 0.01f) {
                continue
            }
            result += RectSnapshot(rect.left, rect.top, rect.right, rect.bottom)
        }
        return result
    } finally {
        if (scaled !== bitmap) {
            scaled.recycle()
        }
    }
}

private fun mergeRect(rects: MutableList<RectF>, candidate: RectF) {
    for (existing in rects) {
        val overlapH = min(existing.right, candidate.right) - max(existing.left, candidate.left)
        val overlapV = min(existing.bottom, candidate.bottom) - max(existing.top, candidate.top)
        if (overlapH >= -0.02f && overlapV >= -0.02f) {
            existing.union(candidate)
            return
        }
    }
    rects += candidate
}

private class NormalizedBuilder {
    private val builder = StringBuilder()
    private var lastWasSpace = true

    fun append(text: CharSequence) {
        text.forEach { char ->
            val normalized = normalizeChar(char)
            when {
                normalized == null -> Unit
                normalized == ' ' -> appendSpace()
                else -> {
                    builder.append(normalized)
                    lastWasSpace = false
                }
            }
        }
    }

    fun buildText(): String {
        if (lastWasSpace && builder.isNotEmpty()) {
            builder.setLength(builder.length - 1)
        }
        return builder.toString()
    }

    private fun appendSpace() {
        if (!lastWasSpace) {
            builder.append(' ')
            lastWasSpace = true
        }
    }
}

private class NormalizedRunsBuilder {
    private val textBuilder = StringBuilder()
    private val mappingBuilder = mutableListOf<Int>()
    private var lastWasSpace = true

    fun append(text: CharSequence, runIndex: Int) {
        text.forEach { char ->
            val normalized = normalizeChar(char)
            when {
                normalized == null -> Unit
                normalized == ' ' -> appendSpace(runIndex)
                else -> {
                    textBuilder.append(normalized)
                    mappingBuilder.add(runIndex)
                    lastWasSpace = false
                }
            }
        }
    }

    fun finishRun() {
        appendSpace(-1)
    }

    fun build(): NormalizedRuns {
        if (lastWasSpace && textBuilder.isNotEmpty()) {
            textBuilder.setLength(textBuilder.length - 1)
            mappingBuilder.removeAt(mappingBuilder.lastIndex)
        }
        return NormalizedRuns(textBuilder.toString(), mappingBuilder.toIntArray())
    }

    private fun appendSpace(runIndex: Int) {
        if (!lastWasSpace) {
            textBuilder.append(' ')
            mappingBuilder.add(runIndex)
            lastWasSpace = true
        }
    }
}

private data class NormalizedRuns(val text: String, val runMapping: IntArray)

private fun normalizeChar(char: Char): Char? {
    if (char.isWhitespace()) return ' '
    val lower = char.lowercaseChar()
    return when {
        lower in 'a'..'z' -> lower
        lower in '0'..'9' -> lower
        lower == '’' || lower == '\'' -> lower
        else -> ' '
    }
}
