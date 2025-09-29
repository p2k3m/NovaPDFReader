import com.android.build.api.dsl.TestExtension
import com.android.build.api.variant.TestAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

val rootLocalProperties = rootProject.file("local.properties")
val configuredAndroidSdk = listOfNotNull(
    providers.environmentVariable("ANDROID_SDK_ROOT").orNull,
    providers.environmentVariable("ANDROID_HOME").orNull
).firstOrNull { it.isNotBlank() }

if (!rootLocalProperties.exists() && configuredAndroidSdk == null) {
    val fallbackSdkDir = listOfNotNull(
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("ANDROID_HOME"),
        "/usr/local/lib/android/sdk"
    )
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && File(it).exists() }

    if (fallbackSdkDir != null) {
        val properties = Properties().apply {
            setProperty("sdk.dir", File(fallbackSdkDir).absolutePath.replace("\\", "\\\\"))
        }
        rootLocalProperties.outputStream().use { output ->
            properties.store(
                output,
                "Auto-generated to help Gradle locate the Android SDK on environments without local.properties"
            )
        }
        logger.lifecycle("Created local.properties with sdk.dir at ${fallbackSdkDir} for baseline profile configuration.")
    } else {
        logger.warn(
            "Unable to locate an Android SDK. Set ANDROID_SDK_ROOT or ANDROID_HOME, or create local.properties with sdk.dir."
        )
    }
}

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.novapdf.reader.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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

fun parseOptionalBoolean(raw: String?): Boolean? {
    val normalized = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when (normalized) {
        "true", "1", "yes", "y" -> true
        "false", "0", "no", "n" -> false
        else -> null
    }
}

val requireConnectedDevice = parseOptionalBoolean(
    (findProperty("novapdf.requireConnectedDevice") as? String)
        ?: providers.gradleProperty("novapdf.requireConnectedDevice").orNull
        ?: System.getenv("NOVAPDF_REQUIRE_CONNECTED_DEVICE")
)

val allowCiConnectedTests = parseOptionalBoolean(
    (findProperty("novapdf.allowCiConnectedTests") as? String)
        ?: providers.gradleProperty("novapdf.allowCiConnectedTests").orNull
        ?: System.getenv("NOVAPDF_ALLOW_CI_CONNECTED_TESTS")
)

val isCiEnvironment = providers.environmentVariable("CI")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

val skipConnectedTestsOnCi = isCiEnvironment.get() && allowCiConnectedTests != true && requireConnectedDevice != true

dependencies {
    implementation(project(":app"))
    implementation("androidx.test:core:1.5.0")
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

val sdkDirectoryProvider = androidComponents.sdkComponents.sdkDirectory.map { it.asFile }

val hasConnectedDeviceProvider = providers.provider {
    val sdkDir = sdkDirectoryProvider.orNull ?: return@provider false
    val platformTools = File(sdkDir, "platform-tools")
    val adbExecutable = sequenceOf("adb", "adb.exe")
        .map { candidate -> File(platformTools, candidate) }
        .firstOrNull { it.exists() }

    if (adbExecutable == null || !adbExecutable.exists()) {
        logger.warn("ADB executable not found. Skipping baseline profile connected checks.")
        return@provider false
    }

    runCatching {
        val process = ProcessBuilder(adbExecutable.absolutePath, "devices")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            logger.warn("Timed out while checking for connected Android devices. Skipping baseline profile connected checks.")
            false
        } else {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.drop(1).any { line ->
                    val trimmed = line.trim()
                    val isDeviceListing = '\t' in line
                    isDeviceListing && trimmed.isNotEmpty() && !trimmed.endsWith("offline", ignoreCase = true)
                }
            }
        }
    }.getOrElse { error ->
        logger.warn("Unable to query connected Android devices via adb. Skipping baseline profile connected checks.", error)
        false
    }
}

val hasConnectedDevice by lazy { hasConnectedDeviceProvider.get() }

if (skipConnectedTestsOnCi) {
    androidComponents.beforeVariants { variantBuilder ->
        variantBuilder.enable = false
    }

    tasks.matching { task ->
        (task.name.startsWith("connected") && task.name.endsWith("AndroidTest")) ||
            task.name.startsWith("checkTestedAppObfuscation")
    }.configureEach {
        onlyIf {
            logger.warn(
                "Skipping task $name because connected Android tests are disabled on CI. " +
                    "Set novapdf.allowCiConnectedTests=true or NOVAPDF_ALLOW_CI_CONNECTED_TESTS=true to enable them."
            )
            false
        }
    }
} else {
    androidComponents.beforeVariants { variantBuilder ->
        if (!hasConnectedDevice) {
            variantBuilder.enable = false
        }
    }

    tasks.matching { task -> task.name.startsWith("checkTestedAppObfuscation") }.configureEach {
        onlyIf {
            val hasDevice = hasConnectedDevice
            if (!hasDevice) {
                logger.warn("Skipping task $name because no connected Android devices/emulators were detected.")
            }
            hasDevice
        }
    }
}

val verifyEmulatorAcceleration = tasks.register("verifyEmulatorAcceleration") {
    group = "verification"
    description = "Fails fast when required emulator acceleration is unavailable."

    val emulatorDirectoryProvider = androidComponents.sdkComponents.sdkDirectory.map { sdkDir ->
        sdkDir.dir("emulator")
    }

    doLast {
        if (isCiEnvironment.get()) {
            logger.warn(
                "Skipping emulator acceleration verification because the task is running " +
                    "in a CI environment without hardware acceleration access."
            )
            return@doLast
        }

        val emulatorDirectory = emulatorDirectoryProvider.get().asFile
        val executableName = if (System.getProperty("os.name").startsWith("Windows")) {
            "emulator-check.exe"
        } else {
            "emulator-check"
        }
        val emulatorCheckBinary = emulatorDirectory.resolve(executableName)

        if (!emulatorCheckBinary.exists()) {
            logger.warn(
                "Emulator acceleration verification skipped because $executableName is " +
                    "unavailable. Install the Android Emulator package to enable the check."
            )
            return@doLast
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

tasks.register("renderBenchmark") {
    group = "performance"
    description = "Captures first-page rendering trace metrics using Macrobenchmark instrumentation."
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
        hasTask(":baselineprofile:renderBenchmark") ->
            arguments["annotation"] = "com.novapdf.reader.baselineprofile.annotations.RenderMetric"
        else -> arguments.remove("annotation")
    }
}
