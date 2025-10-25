package com.novapdf.reader

import dagger.hilt.android.HiltAndroidApp

/**
 * Test-only application that preserves [NovaPdfApp]'s startup behaviour while
 * integrating with Hilt's instrumentation runner.
 */
@HiltAndroidApp
class TestNovaPdfApp : NovaPdfApp()
