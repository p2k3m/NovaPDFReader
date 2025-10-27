package com.novapdf.reader

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.novapdf.reader.anr.installDebugAnrDetector
import com.novapdf.reader.coroutines.CoroutineDispatchers
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.NovaPdfDatabase
import com.novapdf.reader.domain.usecase.PdfViewerUseCases
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.logging.NovaLog
import com.novapdf.reader.logging.ProcessMetricsLogger
import com.novapdf.reader.logging.field
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.search.PdfBoxInitializer
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import dagger.hilt.android.HiltAndroidApp
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.Volatile
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class NovaPdfAppBase : Application(), Configuration.Provider {

    protected abstract val dependencies: Dependencies

    protected data class Dependencies(
        val crashReporter: CrashReporter,
        val adaptiveFlowManager: AdaptiveFlowManager,
        val pdfViewerUseCases: PdfViewerUseCases,
        val workerFactory: HiltWorkerFactory,
        val documentMaintenanceSchedulerProvider: Provider<DocumentMaintenanceScheduler>,
        val searchCoordinatorProvider: Provider<DocumentSearchCoordinator>,
        val bookmarkManagerProvider: Provider<BookmarkManager>,
        val databaseProvider: Provider<NovaPdfDatabase>,
        val dispatchers: CoroutineDispatchers,
    )

    private val crashReporter: CrashReporter
        get() = dependencies.crashReporter

    private val adaptiveFlowManager: AdaptiveFlowManager
        get() = dependencies.adaptiveFlowManager

    private val pdfViewerUseCases: PdfViewerUseCases
        get() = dependencies.pdfViewerUseCases

    private val workerFactory: HiltWorkerFactory
        get() = dependencies.workerFactory

    private val documentMaintenanceSchedulerProvider: Provider<DocumentMaintenanceScheduler>
        get() = dependencies.documentMaintenanceSchedulerProvider

    private val searchCoordinatorProvider: Provider<DocumentSearchCoordinator>
        get() = dependencies.searchCoordinatorProvider

    private val bookmarkManagerProvider: Provider<BookmarkManager>
        get() = dependencies.bookmarkManagerProvider

    private val databaseProvider: Provider<NovaPdfDatabase>
        get() = dependencies.databaseProvider

    private val dispatchers: CoroutineDispatchers
        get() = dependencies.dispatchers

    private val scopeExceptionHandler = CoroutineExceptionHandler { context, error ->
        logUnhandledCoroutineException(context, error)
    }

    private val applicationScope by lazy {
        CoroutineScope(SupervisorJob() + dispatchers.default + scopeExceptionHandler)
    }

    private val backgroundInitializationStarted = AtomicBoolean(false)
    private val strictModeHarnessApplied = AtomicBoolean(false)

    private var searchCoordinatorInstance: DocumentSearchCoordinator? = null
    private var bookmarkManagerInstance: BookmarkManager? = null
    private var maintenanceSchedulerInstance: DocumentMaintenanceScheduler? = null

    private fun searchCoordinator(): DocumentSearchCoordinator {
        val existing = searchCoordinatorInstance
        if (existing != null) return existing
        return searchCoordinatorProvider.get().also { searchCoordinatorInstance = it }
    }

    private fun bookmarkManager(): BookmarkManager {
        val existing = bookmarkManagerInstance
        if (existing != null) return existing
        return bookmarkManagerProvider.get().also { bookmarkManagerInstance = it }
    }

    private fun documentMaintenanceScheduler(): DocumentMaintenanceScheduler {
        val existing = maintenanceSchedulerInstance
        if (existing != null) return existing
        return documentMaintenanceSchedulerProvider.get().also { maintenanceSchedulerInstance = it }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        NovaLog.install(debug = BuildConfig.DEBUG, crashReporter = crashReporter)
        ProcessMetricsLogger.install(this)
        if (BuildConfig.DEBUG) {
            val runningInHarness = isRunningInTestHarness()
            strictModeHarnessApplied.set(runningInHarness)
            if (!runningInHarness) {
                installDebugAnrDetector()
            }
            configureStrictMode(runningInHarness)
        }
    }

    internal fun ensureStrictModeHarnessOverride() {
        if (!BuildConfig.DEBUG) {
            return
        }
        if (!strictModeHarnessApplied.compareAndSet(false, true)) {
            return
        }
        NovaLog.i(
            TAG,
            "Reconfiguring StrictMode for screenshot harness",
            field("testHarness", true),
            field("lateOverride", true),
        )
        configureStrictMode(runningInTestHarness = true)
    }

    private fun configureStrictMode(runningInTestHarness: Boolean) {
        val threadPolicyBuilder = StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
            .applyAnrPenalty(killOnViolation = !runningInTestHarness)
        val vmPolicyBuilder = StrictMode.VmPolicy.Builder()
            .detectAll()
            .penaltyLog()

        if (runningInTestHarness) {
            NovaLog.i(
                TAG,
                "Skipping StrictMode death penalties in test harness",
                field("testHarness", true),
            )
        } else {
            threadPolicyBuilder.penaltyDeath()
            vmPolicyBuilder.penaltyDeath()
        }

        StrictMode.setThreadPolicy(threadPolicyBuilder.build())
        StrictMode.setVmPolicy(vmPolicyBuilder.build())
    }

    private fun StrictMode.ThreadPolicy.Builder.applyAnrPenalty(
        killOnViolation: Boolean,
    ): StrictMode.ThreadPolicy.Builder {
        return apply {
            penaltyListener(strictModePenaltyExecutor, StrictMode.OnThreadViolationListener { violation ->
                if (violation.javaClass.name == STRICT_MODE_UNRESPONSIVE_VIOLATION) {
                    if (killOnViolation) {
                        if (strictModeAnrDeathTriggered.compareAndSet(false, true)) {
                            handleStrictModeAnr(terminateProcess = true)
                        }
                    } else {
                        if (strictModeAnrDeathTriggered.compareAndSet(false, true)) {
                            handleStrictModeAnr(terminateProcess = false)
                            strictModeAnrDeathTriggered.set(false)
                        }
                    }
                }
            })
        }
    }

    private fun handleStrictModeAnr(terminateProcess: Boolean) {
        val pid = Process.myPid()
        val fields = mutableListOf(field("pid", pid))
        if (!terminateProcess) {
            fields += field("terminate", false)
        }
        NovaLog.e(
            tag = TAG,
            message = "StrictMode detected an unresponsive UI; forcing JVM dump via SIGQUIT.",
            fields = fields.toTypedArray(),
        )
        if (terminateProcess) {
            runCatching { Process.sendSignal(pid, Process.SIGNAL_QUIT) }
                .onFailure { error ->
                    NovaLog.w(
                        TAG,
                        "Failed to signal StrictMode ANR via SIGQUIT",
                        error,
                        field("pid", pid),
                    )
                }
            Process.killProcess(pid)
        }
    }

    private fun isRunningInTestHarness(): Boolean {
        if (harnessModeOverride) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ActivityManager.isRunningInUserTestHarness()
        ) {
            return true
        }
        @Suppress("DEPRECATION")
        if (ActivityManager.isRunningInTestHarness()) {
            return true
        }
        if (hasTestHarnessInstrumentationArguments()) {
            return true
        }
        return isInstrumentationRuntime()
    }

    private fun isInstrumentationRuntime(): Boolean {
        val processName = try {
            Application.getProcessName()
        } catch (_: Throwable) {
            null
        }
        if (!processName.isNullOrEmpty()) {
            if (processName.endsWith(".test")) {
                return true
            }
            if (processName.contains(":test")) {
                return true
            }
        }
        if (isActivityThreadInstrumented()) {
            return true
        }
        val registryCandidates = listOf(
            "androidx.test.platform.app.InstrumentationRegistry",
            "androidx.test.InstrumentationRegistry",
        )

        return registryCandidates.any { className ->
            runCatching {
                val registryClass = Class.forName(className)
                val method = registryClass.getMethod("getInstrumentation")
                method.invoke(null)
            }.getOrNull() != null
        }
    }

    private fun hasTestHarnessInstrumentationArguments(): Boolean {
        val arguments = obtainInstrumentationArguments() ?: return false
        val harnessValue = arguments.getString(HARNESS_ARGUMENT_KEY)?.lowercase(Locale.US)
        if (harnessValue != null && harnessValue in HARNESS_TRUTHY_VALUES) {
            return true
        }
        val orchestratorValue = arguments.getString(ORCHESTRATOR_RUNNER_BUILDER_KEY)
        if (!orchestratorValue.isNullOrEmpty()) {
            return true
        }
        return false
    }

    private fun obtainInstrumentationArguments(): Bundle? {
        return runCatching {
            val registryClass = Class.forName("androidx.test.platform.app.InstrumentationRegistry")
            val method = registryClass.getMethod("getArguments")
            method.invoke(null) as? Bundle
        }.getOrNull()
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun isActivityThreadInstrumented(): Boolean {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThread = activityThreadClass
                .getMethod("currentActivityThread")
                .invoke(null)
                ?: return@runCatching false
            val instrumentationField = activityThreadClass.getDeclaredField("mInstrumentation")
            instrumentationField.isAccessible = true
            val instrumentation = instrumentationField.get(currentThread) as? Instrumentation
                ?: return@runCatching false
            instrumentation::class.java != Instrumentation::class.java
        }.getOrDefault(false)
    }

    override fun onTerminate() {
        super.onTerminate()
        applicationScope.cancel()
        searchCoordinatorInstance?.dispose()
    }

    internal fun beginStartupInitialization() {
        if (!backgroundInitializationStarted.compareAndSet(false, true)) {
            return
        }

        applicationScope.launch {
            initializeDeferredComponents()
        }

        ensurePeriodicMaintenanceConfigured()
    }

    private suspend fun initializeDeferredComponents() {
        val databaseReady = withContext(dispatchers.io) {
            runCatching { databaseProvider.get() }
                .onFailure { error -> logDeferredInitializationFailure("database", error) }
                .isSuccess
        }
        if (databaseReady) {
            withContext(dispatchers.io) {
                runCatching { bookmarkManager() }
                    .onFailure { error -> logDeferredInitializationFailure("bookmarks", error) }
            }
        }
        val pdfBoxReady = runCatching { PdfBoxInitializer.ensureInitialized(applicationContext) }
            .onFailure { error -> logDeferredInitializationFailure("pdfbox", error) }
            .getOrDefault(false)
        if (!pdfBoxReady) {
            logDeferredInitializationWarning(
                "pdfbox",
                "PDFBox resources failed to initialise; search indexing will be disabled",
            )
        }
    }

    private fun ensurePeriodicMaintenanceConfigured() {
        runCatching { documentMaintenanceScheduler() }
            .onFailure { error -> logDeferredInitializationFailure("maintenance", error) }
    }

    private fun logDeferredInitializationFailure(
        stage: String,
        error: Throwable,
        metadata: Map<String, String> = emptyMap(),
    ) {
        val fields = buildList {
            add(field("stage", stage))
            metadata.forEach { (key, value) -> add(field(key, value)) }
        }.toTypedArray()
        NovaLog.w(
            tag = TAG,
            message = "Deferred initialisation failed for $stage",
            throwable = error,
            fields = fields,
        )
        val crashMetadata = HashMap<String, String>(metadata.size + 1)
        crashMetadata["stage"] = stage
        crashMetadata.putAll(metadata)
        crashReporter.recordNonFatal(error, crashMetadata)
    }

    private fun logDeferredInitializationWarning(stage: String, message: String) {
        NovaLog.w(TAG, message, null, field("stage", stage))
        crashReporter.logBreadcrumb("$stage: $message")
    }

    private fun logUnhandledCoroutineException(
        context: CoroutineContext,
        error: Throwable,
    ) {
        val threadName = Thread.currentThread().name
        val metadata = HashMap<String, String>()
        metadata["thread"] = threadName
        context[CoroutineName]?.name?.let { coroutineName ->
            metadata["coroutine"] = coroutineName
        }
        val coroutineName = metadata["coroutine"]
        val logFields = buildList {
            add(field("thread", threadName))
            coroutineName?.let { add(field("coroutine", it)) }
        }.toTypedArray()
        NovaLog.e(
            tag = TAG,
            message = "Unhandled coroutine exception on thread $threadName" +
                (coroutineName?.let { " (coroutine=$it)" } ?: ""),
            throwable = error,
            fields = logFields,
        )
        logDeferredInitializationFailure("uncaught", error, metadata)
    }

    companion object {
        private const val TAG = "NovaPdfApp"
        private const val STRICT_MODE_UNRESPONSIVE_VIOLATION =
            "android.os.strictmode.UnresponsiveUiViolation"
        private const val HARNESS_ARGUMENT_KEY = "runScreenshotHarness"
        private const val ORCHESTRATOR_RUNNER_BUILDER_KEY = "runnerBuilder"
        private val HARNESS_TRUTHY_VALUES = setOf("1", "true", "yes")
        private val strictModeAnrDeathTriggered = AtomicBoolean(false)
        private val strictModePenaltyExecutor = Executor { command -> command.run() }

        @Volatile
        internal var harnessModeOverride: Boolean = false
    }
}

@HiltAndroidApp
class NovaPdfApp : NovaPdfAppBase() {

    @Inject
    lateinit var crashReporter: CrashReporter

    @Inject
    lateinit var adaptiveFlowManager: AdaptiveFlowManager

    @Inject
    lateinit var pdfViewerUseCases: PdfViewerUseCases

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var documentMaintenanceSchedulerProvider: Provider<DocumentMaintenanceScheduler>

    @Inject
    lateinit var searchCoordinatorProvider: Provider<DocumentSearchCoordinator>

    @Inject
    lateinit var bookmarkManagerProvider: Provider<BookmarkManager>

    @Inject
    lateinit var databaseProvider: Provider<NovaPdfDatabase>

    @Inject
    lateinit var dispatchers: CoroutineDispatchers

    override val dependencies: Dependencies by lazy(LazyThreadSafetyMode.NONE) {
        Dependencies(
            crashReporter = crashReporter,
            adaptiveFlowManager = adaptiveFlowManager,
            pdfViewerUseCases = pdfViewerUseCases,
            workerFactory = workerFactory,
            documentMaintenanceSchedulerProvider = documentMaintenanceSchedulerProvider,
            searchCoordinatorProvider = searchCoordinatorProvider,
            bookmarkManagerProvider = bookmarkManagerProvider,
            databaseProvider = databaseProvider,
            dispatchers = dispatchers,
        )
    }
}
