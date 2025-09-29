package com.novapdf.reader.baselineprofile

import android.app.Instrumentation
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import com.novapdf.reader.ReaderActivity
import kotlin.system.measureTimeMillis

internal const val TARGET_PACKAGE = "com.novapdf.reader"

internal fun MacrobenchmarkScope.launchReaderAndAwait() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), 5_000)
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.openStressDocumentAndAwait() {
    openStressDocumentAndAwaitInternal()
}

internal fun MacrobenchmarkScope.measureStressDocumentLoadTimeMs(): Long {
    return openStressDocumentAndAwaitInternal()
}

private fun MacrobenchmarkScope.openStressDocumentAndAwaitInternal(): Long {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val documentUri = StressDocumentFixtures.ensureStressDocument(context)

    val activity = waitForReaderActivity(instrumentation)
    return measureTimeMillis {
        instrumentation.runOnMainSync {
            val method = ReaderActivity::class.java.getDeclaredMethod(
                "openDocumentForTest",
                Uri::class.java
            )
            method.isAccessible = true
            method.invoke(activity, documentUri)
        }

        device.wait(Until.hasObject(By.textContains("Loading your document…")), 5_000)
        device.wait(Until.gone(By.textContains("Loading your document…")), 20_000)
        device.wait(Until.hasObject(By.textContains("Adaptive Flow")), 5_000)
        device.waitForIdle()
    }
}

internal fun MacrobenchmarkScope.exerciseReaderContent() {
    val scrollable = device.findObject(By.scrollable(true))
    if (scrollable != null) {
        scrollable.setGestureMargin(device.displayWidth / 10)
        scrollable.scroll(Direction.DOWN, 1.0f)
        device.waitForIdle()
        scrollable.scroll(Direction.UP, 1.0f)
    } else {
        val centerX = device.displayWidth / 2
        val centerY = device.displayHeight / 2
        device.swipe(centerX, centerY, centerX, (centerY * 0.5).toInt(), 30)
        device.swipe(centerX, (centerY * 0.5).toInt(), centerX, centerY, 30)
    }
    device.waitForIdle()
}

private fun waitForReaderActivity(instrumentation: Instrumentation): ReaderActivity {
    repeat(20) {
        val resumed = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstOrNull { it is ReaderActivity } as? ReaderActivity
        if (resumed != null) {
            return resumed
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
    }
    val currentStageActivities = Stage.values().associateWith { stage ->
        ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(stage)
            .joinToString { it.javaClass.simpleName }
    }
    throw IllegalStateException(
        "ReaderActivity not resumed. Lifecycle snapshot: $currentStageActivities"
    )
}
