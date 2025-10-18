package com.novapdf.reader

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import com.novapdf.reader.ui.automation.UiAutomatorTags
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SafDocumentPickerUiAutomatorTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule(ReaderActivity::class.java)

    @get:Rule(order = 2)
    val resourceMonitorRule = DeviceResourceMonitorRule(
        contextProvider = { runCatching { ApplicationProvider.getApplicationContext<Context>() }.getOrNull() },
        logger = { message -> Log.i(TAG, message) },
        onResourceExhausted = { reason -> Log.w(TAG, "Resource exhaustion detected: $reason") },
    )

    private lateinit var device: UiDevice
    private lateinit var appContext: Context
    private val targetPackage: String
        get() = appContext.packageName
    private lateinit var deviceTimeouts: DeviceAdaptiveTimeouts
    private var uiWaitTimeoutMs: Long = DEFAULT_UI_WAIT_TIMEOUT_MS
    private var shortWaitTimeoutMs: Long = DEFAULT_SHORT_WAIT_TIMEOUT_MS

    @Inject
    lateinit var testDocumentFixtures: TestDocumentFixtures

    private var cloudFixture: CloudDocumentFixture? = null

    @Before
    fun setUp() = runBlocking {
        hiltRule.inject()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        appContext = ApplicationProvider.getApplicationContext()
        deviceTimeouts = DeviceAdaptiveTimeouts.forContext(appContext)
        uiWaitTimeoutMs = deviceTimeouts.scaleTimeout(
            base = DEFAULT_UI_WAIT_TIMEOUT_MS,
            min = DEFAULT_UI_WAIT_TIMEOUT_MS,
            max = MAX_UI_WAIT_TIMEOUT_MS,
            allowTightening = false,
        )
        shortWaitTimeoutMs = deviceTimeouts.scaleTimeout(
            base = DEFAULT_SHORT_WAIT_TIMEOUT_MS,
            min = DEFAULT_SHORT_WAIT_TIMEOUT_MS,
            max = MAX_SHORT_WAIT_TIMEOUT_MS,
            allowTightening = false,
        )
        logTestInfo(
            "Using timeouts uiWait=${uiWaitTimeoutMs}ms shortWait=${shortWaitTimeoutMs}ms",
        )
        ensureWorkManagerInitialized(appContext)
        grantStoragePermissions()
        clearPersistedPermissions()
        cloudFixture = prepareCloudDocumentFixture(appContext)
    }

    @After
    fun tearDown() = runBlocking {
        clearPersistedPermissions()
        cloudFixture?.let { fixture ->
            withContext(Dispatchers.IO) {
                fixture.mediaStoreUri?.let { uri ->
                    runCatching { appContext.contentResolver.delete(uri, null, null) }
                }
                fixture.legacyFile?.let { file ->
                    runCatching { if (file.exists()) file.delete() }
                }
            }
        }
    }

    @Test
    fun openDocumentViaStorageAccessFrameworkGrantsPersistablePermission() = runBlocking {
        val fixture = checkNotNull(cloudFixture) { "Cloud document fixture unavailable" }

        dismissOnboardingIfPresent()
        openSourceDialog()
        launchCloudPicker()
        selectDocumentFromDownloads(fixture.displayName)
        waitForDocumentToLoad()

        val documentId = captureOpenedDocumentId()
        assertNotNull("Document ID should be populated after opening via SAF", documentId)

        val hasPersistedPermission = awaitPersistedPermission(documentId!!)
        assertTrue(
            "Expected a persisted read permission for document URI $documentId",
            hasPersistedPermission
        )
    }

    private fun dismissOnboardingIfPresent() {
        val skipButton = device.wait(
            Until.findObject(By.res(targetPackage, UiAutomatorTags.ONBOARDING_SKIP_BUTTON)),
            shortWaitTimeoutMs
        )
        skipButton?.click()
        device.waitForIdle(uiWaitTimeoutMs)
    }

    private fun openSourceDialog() {
        val openButton = device.wait(
            Until.findObject(By.res(targetPackage, UiAutomatorTags.HOME_OPEN_DOCUMENT_BUTTON)),
            uiWaitTimeoutMs
        )
        openButton?.click() ?: throw AssertionError("Unable to locate Open document button")
        device.waitForIdle(uiWaitTimeoutMs)
    }

    private fun launchCloudPicker() {
        val cloudOption = device.wait(
            Until.findObject(By.res(targetPackage, UiAutomatorTags.SOURCE_CLOUD_BUTTON)),
            uiWaitTimeoutMs
        ) ?: throw AssertionError("Cloud document option not visible")
        cloudOption.click()
        device.waitForIdle(uiWaitTimeoutMs)
    }

    private fun selectDocumentFromDownloads(displayName: String) {
        val documentsUiPackage = DOCUMENTS_UI_PACKAGE
        device.wait(Until.hasObject(By.pkg(documentsUiPackage)), uiWaitTimeoutMs)

        maybeAcceptDocumentsUiPermission()
        openDownloadsRoot()

        val documentItem = device.wait(Until.findObject(By.text(displayName)), uiWaitTimeoutMs)
            ?: throw AssertionError("Document $displayName not listed in Downloads")
        documentItem.click()

        val openAction = listOf("Open", "Select", "Allow")
            .firstNotNullOfOrNull { label ->
                device.wait(Until.findObject(By.textContains(label)), shortWaitTimeoutMs)
            }
        openAction?.click()
        device.waitForIdle(uiWaitTimeoutMs)
    }

    private fun maybeAcceptDocumentsUiPermission() {
        val allowButton = listOf("Allow", "While using the app")
            .firstNotNullOfOrNull { label ->
                device.wait(Until.findObject(By.textContains(label)), shortWaitTimeoutMs)
            }
        allowButton?.click()
        device.waitForIdle(uiWaitTimeoutMs)
    }

    private fun openDownloadsRoot() {
        val showRoots = device.wait(
            Until.findObject(By.descContains("Show roots")),
            shortWaitTimeoutMs
        )
        showRoots?.click()

        val downloads = device.wait(
            Until.findObject(By.textContains("Downloads")),
            uiWaitTimeoutMs
        ) ?: throw AssertionError("Downloads entry not visible in document picker")
        downloads.click()
        device.waitForIdle(uiWaitTimeoutMs)
    }

    private fun waitForDocumentToLoad() {
        val adaptiveFlowVisible = device.wait(
            Until.hasObject(adaptiveFlowStatusSelector()),
            uiWaitTimeoutMs
        )
        assertTrue("Adaptive Flow indicator should appear after loading document", adaptiveFlowVisible)
    }

    private fun adaptiveFlowStatusSelector() =
        By.res(targetPackage, UiAutomatorTags.ADAPTIVE_FLOW_STATUS_CHIP)

    private fun waitUntil(
        timeoutMs: Long = uiWaitTimeoutMs,
        checkIntervalMs: Long = POLL_INTERVAL_MS,
        condition: () -> Boolean
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) {
                return true
            }
            device.waitForIdle(checkIntervalMs)
        }
        return condition()
    }

    private fun captureOpenedDocumentId(): String? {
        var documentId: String? = null
        activityRule.scenario.onActivity { activity ->
            documentId = activity.currentDocumentStateForTest().documentId
        }
        return documentId
    }

    private fun awaitPersistedPermission(documentId: String): Boolean {
        val resolver = appContext.contentResolver
        return waitUntil(uiWaitTimeoutMs) {
            resolver.persistedUriPermissions.any { permission ->
                permission.uri.toString() == documentId &&
                    permission.isReadPermission
            }
        }
    }

    private fun grantStoragePermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val uiAutomation = instrumentation.uiAutomation
        val permissions = mutableListOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        permissions.forEach { permission ->
            runCatching { uiAutomation.grantRuntimePermission(packageName, permission) }
        }
    }

    private fun clearPersistedPermissions() {
        val resolver = appContext.contentResolver
        val persisted = resolver.persistedUriPermissions
        persisted.forEach { permission ->
            runCatching {
                resolver.releasePersistableUriPermission(
                    permission.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    private suspend fun prepareCloudDocumentFixture(context: Context): CloudDocumentFixture {
        val sourceUri = testDocumentFixtures.installThousandPageDocument(context)
        val displayName = "NovaThousand-${UUID.randomUUID()}.pdf"
        return withContext(Dispatchers.IO) {
            val inputStream = openFixtureInputStream(context, sourceUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val pendingValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val inserted = resolver.insert(collection, pendingValues)
                    ?: throw IllegalStateException("Unable to create Downloads entry for fixture")
                resolver.openOutputStream(inserted, "w")?.use { output ->
                    inputStream.use { it.copyTo(output) }
                } ?: throw IllegalStateException("Unable to open output stream for fixture")
                val finalizeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(inserted, finalizeValues, null, null)
                CloudDocumentFixture(displayName = displayName, mediaStoreUri = inserted, legacyFile = null)
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                    throw IllegalStateException("Unable to create Downloads directory at ${downloadsDir.absolutePath}")
                }
                val destination = File(downloadsDir, displayName)
                inputStream.use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                CloudDocumentFixture(displayName = displayName, mediaStoreUri = null, legacyFile = destination)
            }
        }
    }

    private fun openFixtureInputStream(context: Context, uri: Uri): InputStream {
        return when (uri.scheme?.lowercase(Locale.US)) {
            ContentResolver.SCHEME_CONTENT ->
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open input stream for content URI $uri")
            ContentResolver.SCHEME_FILE, null ->
                File(requireNotNull(uri.path) { "Missing file path for fixture URI $uri" }).inputStream()
            else -> throw IllegalStateException("Unsupported fixture URI scheme: ${uri.scheme}")
        }
    }

    private fun ensureWorkManagerInitialized(context: Context) {
        val appContext = context.applicationContext
        runCatching { WorkManager.getInstance(appContext) }.onFailure {
            val configuration = Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .build()
            WorkManagerTestInitHelper.initializeTestWorkManager(appContext, configuration)
        }
        runBlocking {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(appContext).cancelAllWork().result.get(5, TimeUnit.SECONDS)
            }
        }
    }

    private data class CloudDocumentFixture(
        val displayName: String,
        val mediaStoreUri: Uri?,
        val legacyFile: File?
    )

    private companion object {
        private const val TAG = "SafPickerUiTest"
        private const val DOCUMENTS_UI_PACKAGE = "com.android.documentsui"
        private const val DEFAULT_UI_WAIT_TIMEOUT_MS = 10_000L
        private const val MAX_UI_WAIT_TIMEOUT_MS = 30_000L
        private const val DEFAULT_SHORT_WAIT_TIMEOUT_MS = 3_000L
        private const val MAX_SHORT_WAIT_TIMEOUT_MS = 12_000L
        private const val POLL_INTERVAL_MS = 200L
    }

    private fun logTestInfo(message: String) {
        Log.i(TAG, message)
    }
}

