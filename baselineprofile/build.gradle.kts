import com.android.build.api.dsl.TestExtension

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.novapdf.reader.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
        }
    }

    targetProjectPath = ":app"
    testBuildType = "benchmark"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(project(":app"))
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test:rules:1.5.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.2.4")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}

val testExtension = extensions.getByType<TestExtension>()

tasks.register("frameRateBenchmark") {
    group = "performance"
    description = "Collects frame rate metrics using Macrobenchmark instrumentation."
    dependsOn("connectedBenchmarkAndroidTest")
}

tasks.register("memoryBenchmark") {
    group = "performance"
    description = "Collects memory usage metrics using Macrobenchmark instrumentation."
    dependsOn("connectedBenchmarkAndroidTest")
}

tasks.register("startupBenchmark") {
    group = "performance"
    description = "Measures cold startup timing using Macrobenchmark instrumentation."
    dependsOn("connectedBenchmarkAndroidTest")
}

gradle.taskGraph.whenReady { taskGraph ->
    val arguments = testExtension.defaultConfig.testInstrumentationRunnerArguments
    when {
        taskGraph.hasTask(":baselineprofile:frameRateBenchmark") ->
            arguments["annotation"] = "com.novapdf.reader.baselineprofile.annotations.FrameRateMetric"
        taskGraph.hasTask(":baselineprofile:memoryBenchmark") ->
            arguments["annotation"] = "com.novapdf.reader.baselineprofile.annotations.MemoryMetric"
        taskGraph.hasTask(":baselineprofile:startupBenchmark") ->
            arguments["annotation"] = "com.novapdf.reader.baselineprofile.annotations.StartupMetric"
        else -> arguments.remove("annotation")
    }
}
