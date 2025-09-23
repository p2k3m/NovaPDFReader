package com.novapdf.reader

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

private const val MAX_TRACKED_PAGES = 8
private const val MAX_ACCELERATION = 12f

class AdaptiveFlowManager(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val pageHistory = ArrayDeque<Pair<Int, Long>>()
    private val _swipeSensitivity = MutableStateFlow(1f)
    val swipeSensitivity: StateFlow<Float> = _swipeSensitivity.asStateFlow()

    private val _preloadTargets = MutableStateFlow(emptyList<Int>())
    val preloadTargets: StateFlow<List<Int>> = _preloadTargets.asStateFlow()

    private val _readingSpeedPagesPerMinute = MutableStateFlow(30f)
    val readingSpeedPagesPerMinute: StateFlow<Float> = _readingSpeedPagesPerMinute.asStateFlow()

    private var lastTiltMagnitude = 0f
    private var isRegistered = false

    fun start() {
        if (!isRegistered) {
            accelerometer?.also { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                isRegistered = true
            }
        }
    }

    fun stop() {
        if (isRegistered) {
            sensorManager.unregisterListener(this)
            isRegistered = false
        }
    }

    fun trackPageChange(pageIndex: Int, totalPages: Int) {
        val now = SystemClock.elapsedRealtime()
        pageHistory.addLast(pageIndex to now)
        while (pageHistory.size > MAX_TRACKED_PAGES) {
            pageHistory.removeFirst()
        }
        computeReadingSpeed()
        updatePreloadTargets(pageIndex, totalPages)
    }

    private fun computeReadingSpeed() {
        if (pageHistory.size < 2) return
        val intervals = pageHistory.zipWithNext { a, b -> b.second - a.second }
        if (intervals.isEmpty()) return
        val averageMillis = intervals.average().toFloat().coerceAtLeast(50f)
        val ppm = 60_000f / averageMillis
        _readingSpeedPagesPerMinute.value = ppm.coerceIn(5f, 240f)
    }

    private fun updatePreloadTargets(currentPage: Int, totalPages: Int) {
        coroutineScope.launch {
            val speed = readingSpeedPagesPerMinute.value
            val predictedAdvance = (speed / 60f).coerceAtLeast(0.5f)
            val additionalPages = when {
                predictedAdvance > 3f -> 4
                predictedAdvance > 1.5f -> 3
                else -> 2
            }
            val tiltBoost = if (lastTiltMagnitude > 1.4f) 1 else 0
            val pagesToPreload = (1..(additionalPages + tiltBoost)).mapNotNull { delta ->
                val index = currentPage + delta
                index.takeIf { it < totalPages }
            }
            _preloadTargets.value = pagesToPreload
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        lastTiltMagnitude = magnitude / SensorManager.GRAVITY_EARTH
        val sensitivity = 1f + (abs(y) + abs(x)) / MAX_ACCELERATION
        _swipeSensitivity.value = sensitivity.coerceIn(0.6f, 2.5f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
