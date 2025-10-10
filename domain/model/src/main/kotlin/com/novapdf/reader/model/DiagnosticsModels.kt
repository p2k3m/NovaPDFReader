package com.novapdf.reader.model

enum class BitmapMemoryLevel {
    NORMAL,
    WARNING,
    CRITICAL,
}

data class BitmapMemoryStats(
    val currentBytes: Long = 0L,
    val peakBytes: Long = 0L,
    val warnThresholdBytes: Long = 0L,
    val criticalThresholdBytes: Long = 0L,
    val level: BitmapMemoryLevel = BitmapMemoryLevel.NORMAL,
)
