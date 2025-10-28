package com.novapdf.reader

import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.android.EarlyEntryPoints
import com.novapdf.reader.coroutines.CoroutineDispatchers
import com.novapdf.reader.data.BookmarkManager
import com.novapdf.reader.data.NovaPdfDatabase
import com.novapdf.reader.domain.usecase.PdfViewerUseCases
import com.novapdf.reader.engine.AdaptiveFlowManager
import com.novapdf.reader.logging.CrashReporter
import com.novapdf.reader.search.DocumentSearchCoordinator
import com.novapdf.reader.work.DocumentMaintenanceScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.internal.testing.TestApplicationComponentManager
import dagger.hilt.android.internal.testing.TestApplicationComponentManagerHolder
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider

/**
 * Test-only application that preserves [NovaPdfAppBase]'s startup behaviour while
 * integrating with Hilt's instrumentation runner.
 */
class TestNovaPdfApp : NovaPdfAppBase(), TestApplicationComponentManagerHolder {

    private var dependenciesState: Dependencies? = null
    private var needsDependencyRefresh: Boolean = false

    private val testComponentManager by lazy(LazyThreadSafetyMode.NONE) {
        TestApplicationComponentManager(this)
    }

    override val dependencies: Dependencies
        get() = dependenciesState
            ?: error("TestNovaPdfApp dependencies accessed before initialization")

    override fun onCreate() {
        NovaPdfAppBase.harnessModeOverride = true
        val resolvedEntryPoint = resolveEntryPoint()
        dependenciesState = buildDependencies(resolvedEntryPoint.entryPoint)
        needsDependencyRefresh = resolvedEntryPoint.usedEarlyComponent
        try {
            // Ensure Hilt creates the test component before the base application logic
            // attempts to access any injected dependencies. The screenshot harness
            // launches the process before the test rules have a chance to invoke
            // [HiltAndroidRule.inject], which meant the first call to
            // [NovaPdfAppBase.dependencies] happened without an initialized component
            // and crashed with "The component was not created".
            super.onCreate()
        } finally {
            NovaPdfAppBase.harnessModeOverride = false
        }
        ensureStrictModeHarnessOverride()
    }

    fun refreshDependenciesIfNeeded() {
        if (!needsDependencyRefresh) {
            return
        }
        val refreshedEntryPoint = EntryPointAccessors.fromApplication(
            this,
            TestNovaPdfAppEntryPoint::class.java,
        )
        dependenciesState = buildDependencies(refreshedEntryPoint)
        needsDependencyRefresh = false
    }

    private fun buildDependencies(entryPoint: TestNovaPdfAppEntryPoint): Dependencies {
        return Dependencies(
            crashReporter = entryPoint.crashReporter(),
            adaptiveFlowManager = entryPoint.adaptiveFlowManager(),
            pdfViewerUseCases = entryPoint.pdfViewerUseCases(),
            workerFactory = entryPoint.workerFactory(),
            documentMaintenanceSchedulerProvider = entryPoint.documentMaintenanceSchedulerProvider(),
            searchCoordinatorProvider = entryPoint.searchCoordinatorProvider(),
            bookmarkManagerProvider = entryPoint.bookmarkManagerProvider(),
            databaseProvider = entryPoint.databaseProvider(),
            dispatchers = entryPoint.dispatchers(),
        )
    }

    private fun resolveEntryPoint(): ResolvedEntryPoint {
        val entryPoint = runCatching {
            EntryPointAccessors.fromApplication(this, TestNovaPdfAppEntryPoint::class.java)
        }.getOrElse { error ->
            if (error is IllegalStateException && error.message?.contains(HILT_RULE_MESSAGE, ignoreCase = true) == true) {
                val earlyEntryPoint = EarlyEntryPoints.get(
                    this,
                    TestNovaPdfAppEarlyEntryPoint::class.java,
                )
                return ResolvedEntryPoint(earlyEntryPoint, usedEarlyComponent = true)
            }
            throw error
        }
        return ResolvedEntryPoint(entryPoint, usedEarlyComponent = false)
    }

    private data class ResolvedEntryPoint(
        val entryPoint: TestNovaPdfAppEntryPoint,
        val usedEarlyComponent: Boolean,
    )

    override fun componentManager(): TestApplicationComponentManager = testComponentManager

    override fun generatedComponent(): Any = testComponentManager.generatedComponent()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TestNovaPdfAppEntryPoint {
        fun crashReporter(): CrashReporter
        fun adaptiveFlowManager(): AdaptiveFlowManager
        fun pdfViewerUseCases(): PdfViewerUseCases
        fun workerFactory(): HiltWorkerFactory
        fun documentMaintenanceSchedulerProvider(): Provider<DocumentMaintenanceScheduler>
        fun searchCoordinatorProvider(): Provider<DocumentSearchCoordinator>
        fun bookmarkManagerProvider(): Provider<BookmarkManager>
        fun databaseProvider(): Provider<NovaPdfDatabase>
        fun dispatchers(): CoroutineDispatchers
    }

    @dagger.hilt.android.EarlyEntryPoint
    @InstallIn(SingletonComponent::class)
    interface TestNovaPdfAppEarlyEntryPoint : TestNovaPdfAppEntryPoint

    private companion object {
        private const val HILT_RULE_MESSAGE = "HiltAndroidRule"
    }
}
