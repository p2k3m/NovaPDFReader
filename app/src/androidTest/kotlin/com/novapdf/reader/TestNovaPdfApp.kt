package com.novapdf.reader

import androidx.hilt.work.HiltWorkerFactory
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

    private val testComponentManager by lazy(LazyThreadSafetyMode.NONE) {
        TestApplicationComponentManager(this)
    }

    private fun entryPoint(): TestNovaPdfAppEntryPoint {
        return EntryPointAccessors.fromApplication(this, TestNovaPdfAppEntryPoint::class.java)
    }

    override val dependencies: Dependencies by lazy(LazyThreadSafetyMode.NONE) {
        val ep = entryPoint()
        Dependencies(
            crashReporter = ep.crashReporter(),
            adaptiveFlowManager = ep.adaptiveFlowManager(),
            pdfViewerUseCases = ep.pdfViewerUseCases(),
            workerFactory = ep.workerFactory(),
            documentMaintenanceSchedulerProvider = ep.documentMaintenanceSchedulerProvider(),
            searchCoordinatorProvider = ep.searchCoordinatorProvider(),
            bookmarkManagerProvider = ep.bookmarkManagerProvider(),
            databaseProvider = ep.databaseProvider(),
            dispatchers = ep.dispatchers(),
        )
    }

    override fun onCreate() {
        NovaPdfAppBase.harnessModeOverride = true
        try {
            // Ensure Hilt creates the test component before the base application logic
            // attempts to access any injected dependencies. The screenshot harness
            // launches the process before the test rules have a chance to invoke
            // [HiltAndroidRule.inject], which meant the first call to
            // [NovaPdfAppBase.dependencies] happened without an initialized component
            // and crashed with "The component was not created".
            testComponentManager.generatedComponent()
            super.onCreate()
        } finally {
            NovaPdfAppBase.harnessModeOverride = false
        }
        ensureStrictModeHarnessOverride()
    }

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
}
