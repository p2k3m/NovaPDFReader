package com.novapdf.reader.baselineprofile

import android.content.pm.ActivityInfo
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.novapdf.reader.HarnessDocument
import com.novapdf.reader.ReaderActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@MediumTest
@RunWith(AndroidJUnit4::class)
class ReaderMicrobenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var document: HarnessDocument

    @Before
    fun setUpDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        document = StressDocumentFixtures.ensureStressDocument(context)
    }

    @Test
    fun repeatedActivityOpenClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        benchmarkRule.measureRepeated {
            val scenario = ActivityScenario.launch(ReaderActivity::class.java)
            try {
                scenario.openHarnessDocument(document)
                instrumentation.waitForIdleSync()
            } finally {
                scenario.close()
            }
        }
    }

    @Test
    fun rapidOpenCloseBurst() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        benchmarkRule.measureRepeated {
            repeat(3) {
                val scenario = ActivityScenario.launch(ReaderActivity::class.java)
                try {
                    scenario.openHarnessDocument(document)
                    instrumentation.waitForIdleSync()
                } finally {
                    scenario.close()
                }
            }
        }
    }

    @Test
    fun rotationDuringActiveSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        benchmarkRule.measureRepeated {
            val scenario = ActivityScenario.launch(ReaderActivity::class.java)
            try {
                scenario.openHarnessDocument(document)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                instrumentation.waitForIdleSync()
            } finally {
                scenario.close()
            }
        }
    }
}

private fun ActivityScenario<ReaderActivity>.openHarnessDocument(document: HarnessDocument) {
    onActivity { activity ->
        when (document) {
            is HarnessDocument.Local -> activity.openDocumentForTest(document.uri)
            is HarnessDocument.Remote -> activity.openRemoteDocumentForTest(document.source)
        }
    }
}
