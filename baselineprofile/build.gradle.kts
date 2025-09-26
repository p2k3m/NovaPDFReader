import com.android.build.api.dsl.TestExtension
import com.android.build.api.variant.TestAndroidComponentsExtension
import org.gradle.api.GradleException

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
    targetVariant = "benchmark"
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
val androidComponents = extensions.getByType<TestAndroidComponentsExtension>()

val verifyEmulatorAcceleration = tasks.register("verifyEmulatorAcceleration") {
    group = "verification"
    description = "Fails fast when required emulator acceleration is unavailable."

    val emulatorDirectoryProvider = androidComponents.sdkComponents.sdkDirectory.map { sdkDir ->
        sdkDir.dir("emulator")
    }

    doLast {
        val emulatorDirectory = emulatorDirectoryProvider.get().asFile
        val executableName = if (System.getProperty("os.name").startsWith("Windows")) {
            "emulator-check.exe"
        } else {
            "emulator-check"
        }
        val emulatorCheckBinary = emulatorDirectory.resolve(executableName)

        if (!emulatorCheckBinary.exists()) {
            throw GradleException("Unable to locate \"$executableName\" in the Android SDK. Ensure the emulator package is installed.")
        }

        val result = project.exec {
            commandLine(emulatorCheckBinary.absolutePath, "accel")
            isIgnoreExitValue = true
        }

        if (result.exitValue != 0) {
            throw GradleException(
                "Android Emulator acceleration is unavailable. " +
                    "Run \"${emulatorCheckBinary.absolutePath} accel\" locally to inspect the host configuration."
            )
        }
    }
}

tasks.configureEach {
    if (name.endsWith("AndroidTest")) {
        dependsOn(verifyEmulatorAcceleration)
    }
}

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

gradle.taskGraph.whenReady {
    val arguments = testExtension.defaultConfig.testInstrumentationRunnerArguments
    when {
        hasTask(":baselineprofile:frameRateBenchmark") ->
            arguments["annotation"] = "com.novapdf.reader.baselineprofile.annotations.FrameRateMetric"
        hasTask(":baselineprofile:memoryBenchmark") ->
            arguments["annotation"] = "com.novapdf.reader.baselineprofile.annotations.MemoryMetric"
        hasTask(":baselineprofile:startupBenchmark") ->
            arguments["annotation"] = "com.novapdf.reader.baselineprofile.annotations.StartupMetric"
        else -> arguments.remove("annotation")
    }
}
