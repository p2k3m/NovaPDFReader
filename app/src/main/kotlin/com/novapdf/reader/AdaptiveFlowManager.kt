package com.novapdf.reader

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Choreographer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.sqrt

private const val MAX_TRACKED_PAGES = 8
private const val MAX_ACCELERATION = 12f
private const val MAX_TRACKED_FRAMES = 90
private const val DEFAULT_FRAME_INTERVAL_MS = 16.6f

class AdaptiveFlowManager(
    context: Context,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var choreographer: Choreographer? = null

    private val pageHistory = ArrayDeque<Pair<Int, Long>>()
    private val _swipeSensitivity = MutableStateFlow(1f)
    val swipeSensitivity: StateFlow<Float> = _swipeSensitivity.asStateFlow()

    private val _preloadTargets = MutableStateFlow(emptyList<Int>())
    val preloadTargets: StateFlow<List<Int>> = _preloadTargets.asStateFlow()

    private val _readingSpeedPagesPerMinute = MutableStateFlow(30f)
    val readingSpeedPagesPerMinute: StateFlow<Float> = _readingSpeedPagesPerMinute.asStateFlow()

    private val _frameIntervalMillis = MutableStateFlow(DEFAULT_FRAME_INTERVAL_MS)
    val frameIntervalMillis: StateFlow<Float> = _frameIntervalMillis.asStateFlow()

    private var lastTiltMagnitude = 0f
    private var isRegistered = false
    private var isFrameCallbackRegistered = false
    private var lastFrameTimeNanos = 0L
    private val frameDurations = ArrayDeque<Float>()

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        val currentChoreographer = choreographer ?: return@FrameCallback
        if (lastFrameTimeNanos != 0L) {
            val deltaMillis = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000f
            updateFrameMetrics(deltaMillis)
        }
        lastFrameTimeNanos = frameTimeNanos
        currentChoreographer.postFrameCallback(frameCallback)
    }

    fun start() {
        if (!isRegistered) {
            accelerometer?.also { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
                isRegistered = true
            }
        }
        startFrameMonitoring()
    }

    fun stop() {
        if (isRegistered) {
            sensorManager.unregisterListener(this)
            isRegistered = false
        }
        stopFrameMonitoring()
    }

    fun trackPageChange(pageIndex: Int, totalPages: Int) {
        val now = wallClock()
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
            val frameInterval = frameIntervalMillis.value
            val predictedAdvance = (speed / 60f).coerceAtLeast(0.5f)
            val additionalPages = when {
                predictedAdvance > 3f -> 4
                predictedAdvance > 1.5f -> 3
                else -> 2
            }
            val frameAdjustment = when {
                frameInterval > 28f -> -2
                frameInterval > 22f -> -1
                frameInterval < 14f -> 1
                else -> 0
            }
            val tiltBoost = if (lastTiltMagnitude > 1.4f) 1 else 0
            val targetCount = max(0, additionalPages + tiltBoost + frameAdjustment)
            val pagesToPreload = if (targetCount == 0) {
                emptyList()
            } else {
                (1..targetCount).mapNotNull { delta ->
                    val index = currentPage + delta
                    index.takeIf { it < totalPages }
                }
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
        val frameInterval = frameIntervalMillis.value
        val frameModifier = when {
            frameInterval > 28f -> 0.75f
            frameInterval > 22f -> 0.9f
            else -> 1f
        }
        _swipeSensitivity.value = (sensitivity * frameModifier).coerceIn(0.6f, 2.5f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startFrameMonitoring() {
        if (isFrameCallbackRegistered) return
        choreographer = Choreographer.getInstance().also {
            frameDurations.clear()
            _frameIntervalMillis.value = DEFAULT_FRAME_INTERVAL_MS
            lastFrameTimeNanos = 0L
            it.postFrameCallback(frameCallback)
            isFrameCallbackRegistered = true
        }
    }

    private fun stopFrameMonitoring() {
        if (!isFrameCallbackRegistered) return
        choreographer?.removeFrameCallback(frameCallback)
        choreographer = null
        isFrameCallbackRegistered = false
        lastFrameTimeNanos = 0L
        frameDurations.clear()
        _frameIntervalMillis.value = DEFAULT_FRAME_INTERVAL_MS
    }

    internal fun updateFrameMetrics(frameDurationMillis: Float) {
        if (frameDurationMillis <= 0f || frameDurationMillis.isNaN() || frameDurationMillis > 1_000f) {
            return
        }
        frameDurations.addLast(frameDurationMillis)
        while (frameDurations.size > MAX_TRACKED_FRAMES) {
            frameDurations.removeFirst()
        }
        if (frameDurations.isEmpty()) {
            _frameIntervalMillis.value = DEFAULT_FRAME_INTERVAL_MS
        } else {
            var sum = 0f
            frameDurations.forEach { sum += it }
            _frameIntervalMillis.value = sum / frameDurations.size
        }
    }
}
