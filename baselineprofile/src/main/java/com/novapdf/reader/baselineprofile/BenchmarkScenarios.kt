package com.novapdf.reader.baselineprofile

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiObject2
import kotlin.system.measureTimeMillis

internal const val TARGET_PACKAGE = "com.novapdf.reader"
private const val READER_ACTIVITY_CLASS_NAME = "com.novapdf.reader.ReaderActivity"

private const val SYSTEM_UI_BOOT_TIMEOUT_MS = 30_000L
private const val APP_LAUNCH_TIMEOUT_MS = 60_000L

internal fun MacrobenchmarkScope.launchReaderAndAwait() {
    pressHome()
    // Warm up SystemUI to make sure UIAutomator interactions are responsive before launch
    device.wait(
        Until.hasObject(By.pkg("com.android.systemui").depth(0)),
        SYSTEM_UI_BOOT_TIMEOUT_MS
    )
    device.waitForIdle()
    startActivityAndWait()
    device.wait(
        Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)),
        APP_LAUNCH_TIMEOUT_MS
    )
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
    val readerActivityClass = instrumentation.targetContext.classLoader.loadClass(
        READER_ACTIVITY_CLASS_NAME
    )
    return measureTimeMillis {
        instrumentation.runOnMainSync {
            val method = readerActivityClass.getDeclaredMethod(
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
    openSearchIfAvailable()
    openOverflowMenuIfAvailable()
    openTableOfContentsIfAvailable()
    device.waitForIdle()
}

private fun MacrobenchmarkScope.openSearchIfAvailable() {
    val searchButton = findByTextOrDesc("Search", "Find")
    if (searchButton?.safeClick() == true) {
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}

private fun MacrobenchmarkScope.openOverflowMenuIfAvailable() {
    val overflowButton = findByTextOrDesc("More options", "Menu", "Overflow")
    if (overflowButton?.safeClick() == true) {
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}

private fun MacrobenchmarkScope.openTableOfContentsIfAvailable() {
    val tocButton = findByTextOrDesc("Table of contents", "Contents", "Outline")
    if (tocButton?.safeClick() == true) {
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}

private fun MacrobenchmarkScope.findByTextOrDesc(vararg tokens: String): UiObject2? {
    for (token in tokens) {
        device.findObject(By.descContains(token))?.let { return it }
        device.findObject(By.descContains(token.lowercase()))?.let { return it }
        device.findObject(By.textContains(token))?.let { return it }
        device.findObject(By.textContains(token.lowercase()))?.let { return it }
    }
    return null
}

private fun UiObject2.safeClick(): Boolean = runCatching {
    click()
    true
}.getOrDefault(false)


private fun waitForReaderActivity(instrumentation: Instrumentation): Activity {
    val readerActivityClass = instrumentation.targetContext.classLoader.loadClass(
        READER_ACTIVITY_CLASS_NAME
    )
    repeat(20) {
        val resumed = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .firstOrNull { readerActivityClass.isInstance(it) }
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
