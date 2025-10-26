package com.novapdf.reader

import dagger.hilt.android.internal.testing.TestApplicationComponentManager
import dagger.hilt.android.internal.testing.TestApplicationComponentManagerHolder

/**
 * Test-only application that preserves [NovaPdfApp]'s startup behaviour while
 * integrating with Hilt's instrumentation runner.
 */
class TestNovaPdfApp : NovaPdfApp(), TestApplicationComponentManagerHolder {

    private val testComponentManager by lazy(LazyThreadSafetyMode.NONE) {
        TestApplicationComponentManager(this)
    }

    override fun onCreate() {
        NovaPdfApp.harnessModeOverride = true
        try {
            super.onCreate()
        } finally {
            NovaPdfApp.harnessModeOverride = false
        }
        ensureStrictModeHarnessOverride()
    }

    override fun componentManager(): TestApplicationComponentManager = testComponentManager

    override fun generatedComponent(): Any = testComponentManager.generatedComponent()
}
