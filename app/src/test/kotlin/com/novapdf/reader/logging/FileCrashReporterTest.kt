package com.novapdf.reader.logging

import androidx.test.core.app.ApplicationProvider
import com.novapdf.reader.TestPdfApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestPdfApp::class)
class FileCrashReporterTest {

    @Test
    fun `crash logs directory defaults to application data root`() {
        val app = ApplicationProvider.getApplicationContext<TestPdfApp>()
        val reporter = FileCrashReporter(app)

        val directory = reporter.crashLogDirectory()
        assertNotNull("Crash log directory should be initialised", directory)
        val resolved = directory!!.canonicalFile
        val expectedParent = app.dataDir?.canonicalFile
        assertEquals(expectedParent, resolved.parentFile?.canonicalFile)
        assertEquals("crashlogs", resolved.name)
    }
}
