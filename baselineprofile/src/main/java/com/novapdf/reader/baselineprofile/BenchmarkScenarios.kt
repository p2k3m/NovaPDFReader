package com.novapdf.reader.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "com.novapdf.reader"

internal fun MacrobenchmarkScope.launchReaderAndAwait() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), 5_000)
    device.waitForIdle()
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
