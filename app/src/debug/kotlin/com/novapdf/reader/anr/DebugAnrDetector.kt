package com.novapdf.reader.anr

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.novapdf.reader.logging.NovaLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "DebugAnrDetector"
private const val HEARTBEAT_INTERVAL_MS = 1_000L
private const val UNRESPONSIVE_THRESHOLD_MS = 5_000L
private const val STACK_LOG_INTERVAL_MS = 2_000L

/**
 * Installs a lightweight ANR detector for debug builds. The detector monitors main thread
 * responsiveness and emits stack traces while the thread is stalled, allowing tests to continue
 * running instead of terminating the process.
 */
fun installDebugAnrDetector() {
    val mainLooper = Looper.getMainLooper()
    val mainThread = mainLooper.thread
    val heartbeatAt = AtomicLong(SystemClock.uptimeMillis())
    val lastStackLoggedAt = AtomicLong(0L)
    val anrOngoing = AtomicBoolean(false)
    val anrBeganAt = AtomicLong(0L)

    val heartbeatThread = HandlerThread("DebugAnrDetector")
    heartbeatThread.isDaemon = true
    heartbeatThread.start()

    val monitorHandler = Handler(heartbeatThread.looper)
    val mainHandler = Handler(mainLooper)

    val heartbeatRunnable = object : Runnable {
        override fun run() {
            heartbeatAt.set(SystemClock.uptimeMillis())
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    val monitorRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val sinceHeartbeat = now - heartbeatAt.get()
            if (sinceHeartbeat >= UNRESPONSIVE_THRESHOLD_MS) {
                if (anrOngoing.compareAndSet(false, true)) {
                    anrBeganAt.set(now - sinceHeartbeat)
                    lastStackLoggedAt.set(0L)
                    NovaLog.e(
                        TAG,
                        "Main thread unresponsive for ${sinceHeartbeat}ms; logging stack traces until it recovers."
                    )
                }
                val lastLogged = lastStackLoggedAt.get()
                if (now - lastLogged >= STACK_LOG_INTERVAL_MS) {
                    val stackTrace = mainThread.stackTrace.joinToString(separator = "\n    ", prefix = "    ")
                    NovaLog.e(
                        TAG,
                        "Main thread stack (stalled for ${sinceHeartbeat}ms):\n$stackTrace"
                    )
                    lastStackLoggedAt.set(now)
                }
            } else if (anrOngoing.compareAndSet(true, false)) {
                val duration = now - anrBeganAt.get()
                NovaLog.i(
                    TAG,
                    "Main thread became responsive after being stalled for ${duration}ms."
                )
            }

            monitorHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    mainHandler.post(heartbeatRunnable)
    monitorHandler.postDelayed(monitorRunnable, HEARTBEAT_INTERVAL_MS)
}
