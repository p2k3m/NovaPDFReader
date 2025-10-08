import com.android.build.api.dsl.TestExtension
import com.android.build.api.variant.TestAndroidComponentsExtension
import org.gradle.StartParameter
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.compile.JavaCompile
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

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

abstract class VerifyEmulatorAccelerationTask : DefaultTask() {
    @get:Input
    var ciBuild: Boolean = false

    @get:Internal
    val sdkDirectory: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun verify() {
        if (ciBuild) {
            logger.warn(
                "Skipping emulator acceleration verification because the task is running " +
                    "in a CI environment without hardware acceleration access."
            )
            return
        }

        val sdkDir = sdkDirectory.orNull?.asFile
        if (sdkDir == null) {
            logger.warn(
                "Emulator acceleration verification skipped because the Android SDK directory could not be located."
            )
            return
        }

        val emulatorDirectory = File(sdkDir, "emulator")
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
            return
        }

        val process = ProcessBuilder(emulatorCheckBinary.absolutePath, "accel")
            .redirectErrorStream(true)
            .start()

        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) {
            process.destroy()
            throw GradleException(
                "Android Emulator acceleration check timed out. " +
                    "Run \"${emulatorCheckBinary.absolutePath} accel\" locally to inspect the host configuration."
            )
        }

        if (process.exitValue() != 0) {
            val output = process.inputStream.bufferedReader().use { reader -> reader.readText().trim() }
            val details = if (output.isNotEmpty()) "\nCommand output:\n$output" else ""
            throw GradleException(
                "Android Emulator acceleration is unavailable. " +
                    "Run \"${emulatorCheckBinary.absolutePath} accel\" locally to inspect the host configuration." +
                    details
            )
        }
    }
}

val isCiEnvironment = providers.environmentVariable("CI")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

val isCiBuild = isCiEnvironment.get()

android {
    namespace = "com.novapdf.reader.baselineprofile"
    compileSdk = libs.findVersion("androidCompileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = libs.findVersion("androidMinSdk").get().requiredVersion.toInt()
        targetSdk = libs.findVersion("androidTargetSdk").get().requiredVersion.toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-Xjsr305=strict",
            "-Xjvm-default=all",
            "-progressive"
        )
        allWarningsAsErrors = isCiBuild
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
) ?: if (isCiBuild) {
    true
} else {
    null
}

val skipConnectedTestsOnCi = isCiBuild && allowCiConnectedTests != true && requireConnectedDevice != true

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

val baselineRequestTaskNames = setOf(
    "baselineprofile", // allow gradle baselineprofile tasks without explicit task name
    "connectedBenchmarkAndroidTest",
    "frameRateBenchmark",
    "memoryBenchmark",
    "startupBenchmark",
    "renderBenchmark"
)

fun String.isBaselineProfileTaskRequest(): Boolean {
    if (contains(":baselineprofile")) {
        return true
    }
    val taskName = substringAfterLast(":")
    return baselineRequestTaskNames.any { candidate ->
        taskName.equals(candidate, ignoreCase = false)
    }
}

val baselineTasksRequested = gradle.startParameter.taskNames.any { it.isBaselineProfileTaskRequest() }

val sdkDirectoryProvider = androidComponents.sdkComponents.sdkDirectory.map { it.asFile }

val hasConnectedDeviceProvider = providers.provider {
    if (!baselineTasksRequested) {
        return@provider false
    }

    val sdkDir = sdkDirectoryProvider.orNull ?: return@provider false
    val platformTools = File(sdkDir, "platform-tools")
    val adbExecutable = sequenceOf("adb", "adb.exe")
        .map { candidate -> File(platformTools, candidate) }
        .firstOrNull { it.exists() }

    if (adbExecutable == null || !adbExecutable.exists()) {
        logger.warn("ADB executable not found. Skipping baseline profile connected checks.")
        return@provider false
    }

    fun List<String>.extractEligibleSerials(): List<String> = drop(1)
        .mapNotNull { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                return@mapNotNull null
            }

            val tabIndex = rawLine.indexOf('\t')
            if (tabIndex <= 0) {
                return@mapNotNull null
            }

            val serial = rawLine.substring(0, tabIndex).trim()
            val status = rawLine.substring(tabIndex).trim()
            if (serial.isEmpty() || status.endsWith("offline", ignoreCase = true)) {
                null
            } else {
                serial
            }
        }

    fun isDeviceApiLevelRecognized(serial: String): Boolean {
        val sdkProcess = ProcessBuilder(
            adbExecutable.absolutePath,
            "-s",
            serial,
            "shell",
            "getprop",
            "ro.build.version.sdk"
        )
            .redirectErrorStream(true)
            .start()

        if (!sdkProcess.waitFor(10, TimeUnit.SECONDS)) {
            sdkProcess.destroy()
            logger.warn(
                "Timed out while querying SDK level for connected device $serial. Skipping baseline profile connected checks."
            )
            return false
        }

        val sdkValue = sdkProcess.inputStream.bufferedReader().use { reader ->
            reader.readText().trim()
        }

        val sdkLevel = sdkValue.toIntOrNull()
        if (sdkLevel == null) {
            logger.warn(
                "Unable to determine SDK level for connected device $serial (value='$sdkValue'). " +
                    "Skipping baseline profile connected checks."
            )
        }
        return sdkLevel != null
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
            val lines = process.inputStream.bufferedReader().useLines { it.toList() }
            val eligibleSerials = lines.extractEligibleSerials()
            eligibleSerials.any { serial -> isDeviceApiLevelRecognized(serial) }
        }
    }.getOrElse { error ->
        logger.warn("Unable to query connected Android devices via adb. Skipping baseline profile connected checks.", error)
        false
    }
}

tasks.withType<JavaCompile>().configureEach {
    if (isCiBuild && "-Werror" !in options.compilerArgs) {
        options.compilerArgs.add("-Werror")
    }
}

val connectedAndroidTestTasks = tasks.matching { task ->
    (task.name.startsWith("connected") && task.name.endsWith("AndroidTest")) ||
        task.name.startsWith("checkTestedAppObfuscation")
}

if (skipConnectedTestsOnCi) {
    androidComponents.beforeVariants { variantBuilder ->
        variantBuilder.enable = false
    }

    connectedAndroidTestTasks.configureEach {
        onlyIf {
            logger.warn(
                "Skipping task $name because connected Android tests are disabled on CI. " +
                    "Set novapdf.allowCiConnectedTests=true or NOVAPDF_ALLOW_CI_CONNECTED_TESTS=true to enable them."
            )
            false
        }
    }
} else {
    connectedAndroidTestTasks.configureEach {
        onlyIf {
            val hasDevice = hasConnectedDeviceProvider.get()
            if (!hasDevice) {
                logger.warn("Skipping task $name because no connected Android devices/emulators were detected.")
            }
            hasDevice
        }
    }
}

val verifyEmulatorAcceleration = tasks.register<VerifyEmulatorAccelerationTask>("verifyEmulatorAcceleration") {
    group = "verification"
    description = "Fails fast when required emulator acceleration is unavailable."
    ciBuild = isCiBuild
    sdkDirectory.set(androidComponents.sdkComponents.sdkDirectory)
}

tasks.configureEach {
    if (name.endsWith("AndroidTest")) {
        dependsOn(verifyEmulatorAcceleration)
    }
}

fun TaskContainer.namedOrNull(name: String) =
    try {
        named(name)
    } catch (_: UnknownTaskException) {
        null
    }

val frameRateBenchmarkTask = tasks.register("frameRateBenchmark") {
    group = "performance"
    description = "Collects frame rate metrics using Macrobenchmark instrumentation."
}

val memoryBenchmarkTask = tasks.register("memoryBenchmark") {
    group = "performance"
    description = "Collects memory usage metrics using Macrobenchmark instrumentation."
}

val startupBenchmarkTask = tasks.register("startupBenchmark") {
    group = "performance"
    description = "Measures cold startup timing using Macrobenchmark instrumentation."
}

val renderBenchmarkTask = tasks.register("renderBenchmark") {
    group = "performance"
    description = "Captures first-page rendering trace metrics using Macrobenchmark instrumentation."
}

val performanceBenchmarkTasks = listOf(
    frameRateBenchmarkTask,
    memoryBenchmarkTask,
    startupBenchmarkTask,
    renderBenchmarkTask
)

val configurationCacheIncompatibleReason =
    "Baseline Profile connected checks invoke adb during configuration."

afterEvaluate {
    val candidateBenchmarkTasks = listOfNotNull(
        tasks.namedOrNull("connectedBenchmarkAndroidTest"),
        tasks.namedOrNull("connectedNonMinifiedReleaseAndroidTest"),
        tasks.namedOrNull("connectedNonMinifiedBenchmarkAndroidTest"),
        tasks.namedOrNull("connectedBenchmarkReleaseAndroidTest"),
        tasks.namedOrNull("connectedBenchmarkBenchmarkAndroidTest"),
        tasks.namedOrNull("connectedAndroidTest")
    )

    val primaryBenchmarkTask = candidateBenchmarkTasks.firstOrNull()
        ?: throw GradleException(
            "Unable to locate a connected Android benchmark task. Check your Android Gradle Plugin setup."
        )

    val connectedBenchmarkTask = if (primaryBenchmarkTask.name == "connectedBenchmarkAndroidTest") {
        primaryBenchmarkTask
    } else {
        tasks.namedOrNull("connectedBenchmarkAndroidTest")
            ?: tasks.register("connectedBenchmarkAndroidTest") {
                group = "verification"
                description = "Compatibility alias for the connected benchmark Android test task."
                notCompatibleWithConfigurationCache(configurationCacheIncompatibleReason)
                dependsOn(primaryBenchmarkTask)
            }
    }

    performanceBenchmarkTasks.forEach { taskProvider ->
        taskProvider.configure {
            dependsOn(connectedBenchmarkTask)
        }
    }
}

fun StartParameter.requestsTask(taskName: String): Boolean =
    taskNames.any { requested ->
        requested == taskName || requested.substringAfterLast(":") == taskName
    }

tasks.matching { task -> task.name.startsWith("connected") }.configureEach {
    notCompatibleWithConfigurationCache(configurationCacheIncompatibleReason)
}

listOf("frameRateBenchmark", "memoryBenchmark", "startupBenchmark", "renderBenchmark").forEach { taskName ->
    tasks.named(taskName).configure {
        notCompatibleWithConfigurationCache(configurationCacheIncompatibleReason)
    }
}

testExtension.defaultConfig.testInstrumentationRunnerArguments.apply {
    when {
        gradle.startParameter.requestsTask("frameRateBenchmark") ->
            this["annotation"] = "com.novapdf.reader.baselineprofile.annotations.FrameRateMetric"
        gradle.startParameter.requestsTask("memoryBenchmark") ->
            this["annotation"] = "com.novapdf.reader.baselineprofile.annotations.MemoryMetric"
        gradle.startParameter.requestsTask("startupBenchmark") ->
            this["annotation"] = "com.novapdf.reader.baselineprofile.annotations.StartupMetric"
        gradle.startParameter.requestsTask("renderBenchmark") ->
            this["annotation"] = "com.novapdf.reader.baselineprofile.annotations.RenderMetric"
        else -> remove("annotation")
    }
}
