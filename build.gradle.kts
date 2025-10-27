import com.android.build.gradle.BaseExtension
import com.android.build.gradle.internal.tasks.DeviceProviderInstrumentTestTask
import com.android.build.gradle.internal.tasks.InstallVariantTask
import com.diffplug.gradle.spotless.SpotlessExtension
import com.android.ddmlib.DdmPreferences
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.StartParameter
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.logging.Logger
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask
import org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask
import kotlin.LazyThreadSafetyMode
import kotlin.math.max
import kotlin.random.Random
import java.io.File
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.gradle.api.tasks.StopExecutionException
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.dependency.analysis) apply false
}

buildscript {
    dependencies {
        classpath("com.squareup:javapoet:1.13.0")
    }
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val ktlintVersion = versionCatalog.findVersion("ktlint").get().requiredVersion
val androidTestRunnerDependencyProvider = versionCatalog.findLibrary("androidx-test-runner").get()

val dependencyAnalysisRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("dependency", ignoreCase = true) ||
        taskName.contains("buildHealth", ignoreCase = true)
}

val dependencyAnalysisEnabled = providers.gradleProperty("novapdf.enableDependencyAnalysis")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

if (dependencyAnalysisRequested || dependencyAnalysisEnabled.get()) {
    pluginManager.apply("com.autonomousapps.dependency-analysis")
}

fun parseOptionalBoolean(raw: String?): Boolean? = when {
    raw == null -> null
    raw.equals("true", ignoreCase = true) -> true
    raw.equals("false", ignoreCase = true) -> false
    else -> null
}

fun locateAndroidSdkDir(): File? {
    val localProperties = rootProject.file("local.properties")
    if (localProperties.exists()) {
        val properties = Properties()
        localProperties.inputStream().use { input ->
            properties.load(input)
        }
        properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { sdkPath ->
            val sdkDirCandidate = File(sdkPath)
            if (sdkDirCandidate.exists()) {
                return sdkDirCandidate
            }
        }
    }

    return sequenceOf("ANDROID_SDK_ROOT", "ANDROID_HOME")
        .mapNotNull { key -> System.getenv(key)?.takeIf { it.isNotBlank() } }
        .map { path -> File(path) }
        .firstOrNull { it.exists() }
}

fun findAdbExecutable(sdkDirectory: File?): File? {
    if (sdkDirectory == null) {
        return null
    }

    val platformTools = File(sdkDirectory, "platform-tools")
    return sequenceOf("adb", "adb.exe")
        .map { executable -> File(platformTools, executable) }
        .firstOrNull { it.exists() }
}

fun StartParameter.requestsConnectedAndroidTests(): Boolean = taskNames.any { taskPath ->
    val taskName = taskPath.substringAfterLast(":")
    taskName.contains("connected", ignoreCase = true) &&
        (taskName.contains("AndroidTest", ignoreCase = true) ||
            taskName.contains("Check", ignoreCase = true))
}

data class ConnectedDeviceCheck(
    val hasDevice: Boolean,
    val readyDevices: List<String>,
    val warningMessage: String?
)

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

val isCiBuild = parseOptionalBoolean(System.getenv("CI")) ?: false
val skipConnectedTestsOnCi = isCiBuild && allowCiConnectedTests != true && requireConnectedDevice != true

fun parseAccelerationPreference(raw: String?): String? = raw
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.lowercase()

val accelerationPreference = parseAccelerationPreference(System.getenv("ACTIONS_RUNNER_ACCELERATION_PREFERENCE"))
val nestedVirtualizationDisabled = parseOptionalBoolean(System.getenv("ACTIONS_RUNNER_DISABLE_NESTED_VIRTUALIZATION"))
val virtualizationUnavailable = when {
    requireConnectedDevice == true -> false
    nestedVirtualizationDisabled == true -> true
    accelerationPreference == "software" || accelerationPreference == "none" -> true
    else -> false
}

val minimumSupportedApiLevel = runCatching {
    versionCatalog.findVersion("androidMinSdk").get().requiredVersion.toInt()
}.getOrNull()

val minimumConnectedTestApiLevel = max(21, minimumSupportedApiLevel ?: 21)

val androidSdkDirectory = locateAndroidSdkDir()
val adbExecutable = findAdbExecutable(androidSdkDirectory)

val playServicesAutoUpdateDisabledDevices = ConcurrentHashMap.newKeySet<String>()

val testShuffleSeedOverride = (findProperty("novapdf.testShuffleSeed") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("NOVAPDF_TEST_SHUFFLE_SEED")?.takeIf { it.isNotBlank() }

private val testShuffleSeedLogged = AtomicBoolean(false)

val testShuffleSeed: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
    val override = testShuffleSeedOverride
    if (override != null) {
        if (testShuffleSeedLogged.compareAndSet(false, true)) {
            logger.lifecycle("NovaPDF tests using provided shuffle seed $override")
        }
        override
    } else {
        val generatedValue = Random.Default.nextLong()
        val normalized = if (generatedValue == Long.MIN_VALUE) 0L else generatedValue
        val seed = normalized.toString()
        if (testShuffleSeedLogged.compareAndSet(false, true)) {
            logger.lifecycle(
                "NovaPDF tests generated shuffle seed $seed. " +
                    "Re-run with -Pnovapdf.testShuffleSeed=$seed or NOVAPDF_TEST_SHUFFLE_SEED=$seed to reproduce ordering.",
            )
        }
        seed
    }
}

fun queryDeviceApiLevel(serial: String): Int? {
    val executable = adbExecutable ?: return null

    return try {
        val process = ProcessBuilder(
            executable.absolutePath,
            "-s",
            serial,
            "shell",
            "getprop",
            "ro.build.version.sdk"
        )
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroy()
            process.destroyForcibly()
            null
        } else {
            process.inputStream.bufferedReader().use { reader ->
                reader.readText().trim().toIntOrNull()
            }
        }
    } catch (error: Exception) {
        logger.warn(
            "Unable to determine API level for connected Android device $serial. Skipping.",
            error
        )
        null
    }
}

fun runAdbCommand(
    serial: String,
    logger: Logger,
    timeoutSeconds: Long = 45,
    vararg arguments: String
): String? {
    val executable = adbExecutable
    if (executable == null || !executable.exists()) {
        logger.warn(
            "Unable to execute adb command ${arguments.joinToString(" ")} for device $serial because the adb executable was not found."
        )
        return null
    }

    val command = buildList {
        add(executable.absolutePath)
        add("-s")
        add(serial)
        addAll(arguments)
    }

    return runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            process.destroyForcibly()
            logger.warn(
                "Timed out while executing adb command ${arguments.joinToString(" ")} for device $serial."
            )
            null
        } else {
            process.inputStream.bufferedReader().use { reader ->
                reader.readText()
            }
        }
    }.getOrElse { error ->
        logger.warn(
            "Failed to execute adb command ${arguments.joinToString(" ")} for device $serial.",
            error
        )
        null
    }
}

fun disablePlayServicesAutoUpdates(serial: String, logger: Logger) {
    if (playServicesAutoUpdateDisabledDevices.contains(serial)) {
        return
    }

    val apiLevel = queryDeviceApiLevel(serial)
    if (apiLevel == null) {
        logger.info(
            "Skipping Play Services auto-update suppression for Android device $serial because the API level could not be determined."
        )
        return
    }

    val operations = buildList {
        if (apiLevel >= 26) {
            add(
                listOf(
                    "shell",
                    "cmd",
                    "appops",
                    "set",
                    "com.google.android.gms",
                    "REQUEST_INSTALL_PACKAGES",
                    "ignore"
                )
            )
        }
        if (apiLevel >= 31) {
            add(
                listOf(
                    "shell",
                    "cmd",
                    "appops",
                    "set",
                    "com.google.android.gms",
                    "UPDATE_PACKAGES_WITHOUT_USER_ACTION",
                    "ignore"
                )
            )
        }
    }

    if (operations.isEmpty()) {
        logger.info(
            "Skipping Play Services auto-update suppression for Android device $serial because API level $apiLevel does not expose background app-ops toggles."
        )
        playServicesAutoUpdateDisabledDevices.add(serial)
        return
    }

    var commandApplied = false
    operations.forEach { command ->
        val output = runAdbCommand(serial, logger, timeoutSeconds = 15, *command.toTypedArray())
        if (output == null) {
            logger.info(
                "Unable to apply Play Services auto-update suppression via '${command.joinToString(" ")}' on device $serial."
            )
            return
        }

        val trimmedOutput = output.trim()
        val normalizedOutput = trimmedOutput.lowercase()
        if (normalizedOutput.startsWith("error:") ||
            normalizedOutput.contains("unknown operation") ||
            normalizedOutput.contains("securityexception") ||
            normalizedOutput.contains("security exception")
        ) {
            logger.info(
                "Play Services auto-update suppression command '${command.joinToString(" ")}' is unsupported on device $serial: $trimmedOutput"
            )
            return@forEach
        }

        commandApplied = true
        if (trimmedOutput.isNotEmpty() && trimmedOutput.equals("No operations.", ignoreCase = true).not()) {
            logger.info(
                "adb ${command.joinToString(" ")} for device $serial responded with: $trimmedOutput"
            )
        }
    }

    if (commandApplied) {
        playServicesAutoUpdateDisabledDevices.add(serial)
        logger.info(
            "Play Services auto-update suppression applied to Android device $serial (API level $apiLevel)."
        )
    }
}

fun runDeviceHealthChecksBeforeTests(serial: String, logger: Logger) {
    if (!waitForDeviceBootCompletion(serial, logger)) {
        logger.warn(
            "Android device $serial did not report sys.boot_completed=1 before running device health checks."
        )
    }

    disablePlayServicesAutoUpdates(serial, logger)
    clearDeviceLogcat(serial, logger)
    stabilizeSystemUiIfRequested(serial, logger)
}

private fun waitForDeviceBootCompletion(serial: String, logger: Logger): Boolean {
    val attempts = 10
    repeat(attempts) { attempt ->
        val bootCompleted = runAdbCommand(
            serial,
            logger,
            timeoutSeconds = 15,
            "shell",
            "getprop",
            "sys.boot_completed"
        )?.trim()
        val devBootCompleted = runAdbCommand(
            serial,
            logger,
            timeoutSeconds = 15,
            "shell",
            "getprop",
            "dev.bootcomplete"
        )?.trim()

        if (bootCompleted == "1" || devBootCompleted == "1") {
            return true
        }

        if (attempt < attempts - 1) {
            logger.info(
                "Android device $serial has not completed boot (attempt ${attempt + 1} of $attempts). Retrying before device health checks."
            )
            Thread.sleep(5_000)
        }
    }

    return false
}

private fun clearDeviceLogcat(serial: String, logger: Logger) {
    val output = runAdbCommand(serial, logger, timeoutSeconds = 15, "shell", "logcat", "-c")
    if (output == null) {
        logger.warn("Failed to clear logcat on Android device $serial prior to connected tests.")
    }
}

private fun stabilizeSystemUiIfRequested(serial: String, logger: Logger) {
    if (!isEnvFlagEnabled("NOVAPDF_STABILIZE_SYSTEM_UI")) {
        return
    }

    val component = System.getenv("NOVAPDF_STABILIZE_SYSTEM_UI_COMPONENT")?.takeIf { it.isNotBlank() }
    val command = if (component != null) {
        listOf("shell", "am", "start", "-W", "-n", component)
    } else {
        listOf(
            "shell",
            "am",
            "start",
            "-W",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.HOME"
        )
    }

    val output = runAdbCommand(serial, logger, timeoutSeconds = 30, *command.toTypedArray())
    if (output == null) {
        logger.warn(
            "Failed to launch a stabilizing activity on Android device $serial before connected tests."
        )
    } else {
        val trimmed = output.trim()
        if (trimmed.isNotEmpty()) {
            logger.info("Stabilized system UI on Android device $serial: $trimmed")
        }
    }
}

private fun isEnvFlagEnabled(name: String): Boolean {
    val value = System.getenv(name) ?: return false
    return value.equals("1", ignoreCase = true) ||
        value.equals("true", ignoreCase = true) ||
        value.equals("yes", ignoreCase = true) ||
        value.equals("on", ignoreCase = true)
}

subprojects {
    tasks.withType<Test>().configureEach {
        val seed = testShuffleSeed
        systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$Random")
        systemProperty("junit.jupiter.testmethod.order.default", "org.junit.jupiter.api.MethodOrderer\$Random")
        systemProperty("junit.jupiter.execution.order.random.seed", seed)
        doFirst {
            logger.lifecycle("Executing $path with JUnit random order seed $seed")
        }
    }
}

fun ensureDeviceReadyForApkInstall(serial: String, logger: Logger): Boolean {
    val waitResult = runAdbCommand(serial, logger, timeoutSeconds = 180, "wait-for-device")
    if (waitResult == null) {
        logger.warn(
            "Android device $serial did not respond to wait-for-device before APK installation."
        )
        return false
    }

    val bootCheckAttempts = 20
    repeat(bootCheckAttempts) { attempt ->
        val bootCompleted = runAdbCommand(
            serial,
            logger,
            timeoutSeconds = 30,
            "shell",
            "getprop",
            "sys.boot_completed"
        )?.trim()
        val devBootCompleted = runAdbCommand(
            serial,
            logger,
            timeoutSeconds = 30,
            "shell",
            "getprop",
            "dev.bootcomplete"
        )?.trim()

        if (bootCompleted == "1" || devBootCompleted == "1") {
            return ensurePackageManagerReady(serial, logger)
        }

        if (attempt < bootCheckAttempts - 1) {
            logger.info(
                "Android device $serial has not reported sys.boot_completed=1 (attempt ${attempt + 1} of $bootCheckAttempts). Retrying before APK installation."
            )
            Thread.sleep(10_000)
        }
    }

    logger.warn(
        "Android device $serial did not report sys.boot_completed=1 before APK installation."
    )
    return false
}

private fun ensurePackageManagerReady(serial: String, logger: Logger): Boolean {
    val serviceCheckAttempts = 10
    repeat(serviceCheckAttempts) { attempt ->
        val output = runAdbCommand(
            serial,
            logger,
            timeoutSeconds = 30,
            "shell",
            "cmd",
            "package",
            "path",
            "android"
        )?.trim()

        val serviceUnavailable = output.isNullOrEmpty() ||
            output.contains("Can't find service: package", ignoreCase = true) ||
            output.contains("Exception occurred", ignoreCase = true)

        if (!serviceUnavailable) {
            disablePlayServicesAutoUpdates(serial, logger)
            return true
        }

        if (attempt < serviceCheckAttempts - 1) {
            logger.info(
                "Android device $serial does not have the package service ready yet (attempt ${attempt + 1} of $serviceCheckAttempts). Retrying before APK installation."
            )
            Thread.sleep(10_000)
        }
    }

    logger.warn(
        "Android device $serial did not expose a responsive package service before APK installation."
    )
    return false
}

fun computeConnectedDeviceCheck(): ConnectedDeviceCheck {
    val executable = adbExecutable
    if (executable == null) {
        return ConnectedDeviceCheck(
            hasDevice = false,
            readyDevices = emptyList(),
            warningMessage = "ADB executable not found. Skipping connected Android tests. " +
                "Set ANDROID_SDK_ROOT or create a local.properties with sdk.dir to enable instrumentation builds."
        )
    }

    if (!executable.exists()) {
        return ConnectedDeviceCheck(
            hasDevice = false,
            readyDevices = emptyList(),
            warningMessage = "ADB executable not found at ${executable.absolutePath}. Skipping connected Android tests."
        )
    }

    return runCatching {
        val process = ProcessBuilder(executable.absolutePath, "devices")
            .redirectErrorStream(true)
            .start()

        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            process.destroyForcibly()
            ConnectedDeviceCheck(
                hasDevice = false,
                readyDevices = emptyList(),
                warningMessage = "Timed out while checking for connected Android devices. Skipping connected Android tests."
            )
        } else {
            val readyDevices = process.inputStream.bufferedReader().useLines { lines ->
                lines
                    .drop(1)
                    .mapNotNull { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) {
                            return@mapNotNull null
                        }

                        val tokens = trimmed.split(Regex("\\s+"))
                        val serial = tokens.firstOrNull() ?: return@mapNotNull null
                        val state = tokens.getOrNull(1)?.lowercase()

                        if (state != null && state != "device") {
                            if (state == "offline") {
                                logger.warn("Skipping connected Android device $serial with state $state.")
                            } else {
                                logger.info("Skipping connected Android device $serial with state $state.")
                            }
                            return@mapNotNull null
                        }

                        val apiLevel = queryDeviceApiLevel(serial)
                        if (apiLevel == null) {
                            logger.warn("Connected Android device $serial did not report an API level. Skipping.")
                            return@mapNotNull null
                        }

                        val minimumApi = minimumConnectedTestApiLevel
                        if (apiLevel < minimumApi) {
                            logger.warn(
                                "Connected Android device $serial is API level $apiLevel which is below the minimum supported API level $minimumApi. Skipping."
                            )
                            return@mapNotNull null
                        }

                        serial
                    }
                    .toList()
            }

            if (readyDevices.isNotEmpty()) {
                ConnectedDeviceCheck(
                    hasDevice = true,
                    readyDevices = readyDevices,
                    warningMessage = null
                )
            } else {
                ConnectedDeviceCheck(
                    hasDevice = false,
                    readyDevices = emptyList(),
                    warningMessage = "No connected Android devices/emulators detected. Skipping connected Android tests."
                )
            }
        }
    }.getOrElse { error ->
        logger.warn(
            "Unable to query connected Android devices via adb. Skipping connected Android tests.",
            error
        )
        ConnectedDeviceCheck(
            hasDevice = false,
            readyDevices = emptyList(),
            warningMessage = "Unable to query connected Android devices via adb. Skipping connected Android tests."
        )
    }
}

val ddmlibTimeoutMs = 600_000

runCatching {
    if (DdmPreferences.getTimeOut() < ddmlibTimeoutMs) {
        DdmPreferences.setTimeOut(ddmlibTimeoutMs)
    }
}.onFailure { error ->
    logger.warn(
        "Unable to configure ddmlib timeouts for connected Android tests; continuing with defaults.",
        error
    )
}

val connectedDeviceCheck by lazy(LazyThreadSafetyMode.NONE) { computeConnectedDeviceCheck() }
val deviceWarningLogged = AtomicBoolean(false)
val skipWarningLogged = AtomicBoolean(false)
val virtualizationSkipLogged = AtomicBoolean(false)
val instrumentationSkipLogged = AtomicBoolean(false)

subprojects {
    configurations.all {
        resolutionStrategy {
            force("com.squareup:javapoet:1.13.0")
        }
    }

    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    pluginManager.apply("com.diffplug.spotless")
    pluginManager.apply("io.gitlab.arturbosch.detekt")

    fun configureAdbTimeout() {
        val extension = extensions.findByName("android")
        if (extension is BaseExtension) {
            val desiredTimeoutMs = 600_000
            if (extension.adbOptions.timeOutInMs < desiredTimeoutMs) {
                extension.adbOptions.timeOutInMs = desiredTimeoutMs
            }
        }
    }

    pluginManager.withPlugin("com.android.application") { configureAdbTimeout() }
    pluginManager.withPlugin("com.android.library") { configureAdbTimeout() }
    pluginManager.withPlugin("com.android.test") { configureAdbTimeout() }

    plugins.withId("com.android.application") {
        dependencies {
            add("androidTestImplementation", androidTestRunnerDependencyProvider.get())
        }
    }

    plugins.withId("com.android.library") {
        dependencies {
            add("androidTestImplementation", androidTestRunnerDependencyProvider.get())
        }
    }

    extensions.configure<KtlintExtension> {
        version.set(ktlintVersion)
        android.set(true)
        ignoreFailures.set(false)
        filter {
            include("**/src/main/**/*.kt")
            exclude("**/generated/**")
            exclude("**/build/**")
            exclude("**/*.kts")
        }
    }

    extensions.configure<SpotlessExtension> {
        val hasKotlinPlugin = pluginManager.hasPlugin("org.jetbrains.kotlin.android") ||
            pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") ||
            pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") ||
            pluginManager.hasPlugin("org.jetbrains.kotlin.kapt")

        if (hasKotlinPlugin) {
            kotlin {
                if (this@subprojects.name == "app") {
                    target("src/**/*.kt")
                    targetExclude("**/build/**/*.kt", "**/src/test/**/*.kt", "**/src/androidTest/**/*.kt")
                    ktlint(ktlintVersion)
                } else {
                    targetExclude("**/*.kt")
                }
            }
        }
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
        basePath = projectDir.absolutePath
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        exclude("**/build/**")
        reports {
            xml.required.set(false)
            html.required.set(false)
            txt.required.set(false)
            sarif.required.set(false)
        }
    }

    tasks.withType<KtLintCheckTask>().configureEach {
        onlyIf { !name.contains("AndroidTest") && !name.contains("KotlinScript") }
    }

    tasks.withType<KtLintFormatTask>().configureEach {
        onlyIf { !name.contains("AndroidTest") && !name.contains("KotlinScript") }
    }

    tasks.matching { task ->
        task.name.startsWith("ktlint") && (task.name.contains("AndroidTest") || task.name.contains("TestSourceSet") || task.name.contains("KotlinScript"))
    }.configureEach {
        enabled = false
    }

    if (name == "baselineprofile") {
        tasks.matching { it.name.startsWith("ktlint") }.configureEach {
            enabled = false
        }
    }

    tasks.matching { it.name == "check" }.configureEach {
        dependsOn("spotlessCheck", "detekt")
    }

    val connectedAndroidTestTasks = tasks.matching { task ->
        task.name.startsWith("connected", ignoreCase = true) && task.name.contains("AndroidTest")
    }

    when {
        virtualizationUnavailable -> connectedAndroidTestTasks.configureEach {
            onlyIf {
                if (virtualizationSkipLogged.compareAndSet(false, true)) {
                    logger.warn(
                        "Skipping task $name because the current environment does not support Android emulator virtualization. " +
                            "Unset ACTIONS_RUNNER_DISABLE_NESTED_VIRTUALIZATION or set ACTIONS_RUNNER_ACCELERATION_PREFERENCE to a hardware-accelerated mode to enable connected tests."
                    )
                }
                false
            }
        }

        skipConnectedTestsOnCi -> connectedAndroidTestTasks.configureEach {
            onlyIf {
                if (skipWarningLogged.compareAndSet(false, true)) {
                    logger.warn(
                        "Skipping task $name because connected Android tests are disabled on CI. " +
                            "Set novapdf.allowCiConnectedTests=true or NOVAPDF_ALLOW_CI_CONNECTED_TESTS=true to enable them."
                    )
                }
                false
            }
        }

        requireConnectedDevice != true -> connectedAndroidTestTasks.configureEach {
            onlyIf {
                val check = connectedDeviceCheck
                if (!check.hasDevice && deviceWarningLogged.compareAndSet(false, true)) {
                    val message = check.warningMessage
                    if (message != null) {
                        logger.warn(message)
                    } else {
                        logger.warn("No connected Android devices/emulators detected. Skipping task $name.")
                    }
                }
                check.hasDevice
            }
        }
    }

    @Suppress("UnstableApiUsage")
    tasks.withType(InstallVariantTask::class.java).configureEach {
        doFirst {
            if (virtualizationUnavailable) {
                if (virtualizationSkipLogged.compareAndSet(false, true)) {
                    logger.warn(
                        "Skipping task $name because the current environment does not support Android emulator virtualization."
                    )
                }
                throw StopExecutionException("Android emulator virtualization is unavailable.")
            }

            val executable = adbExecutable
            if (executable == null || !executable.exists()) {
                logger.warn(
                    "Skipping device readiness checks for task $name because the adb executable was not found."
                )
                return@doFirst
            }

            val devices = connectedDeviceCheck.readyDevices
            if (devices.isEmpty()) {
                val message = connectedDeviceCheck.warningMessage
                    ?: "Skipping task $name because no connected Android devices/emulators were detected."
                logger.warn(message)
                throw StopExecutionException("No responsive Android devices available for APK installation.")
            }

            devices.forEach { serial ->
                if (!ensureDeviceReadyForApkInstall(serial, logger)) {
                    throw StopExecutionException("Android device $serial is not ready for APK installation.")
                }
            }
        }
    }

    tasks.withType<DeviceProviderInstrumentTestTask>().configureEach {
        doFirst {
            if (requireConnectedDevice == true) {
                return@doFirst
            }

            if (virtualizationUnavailable) {
                if (virtualizationSkipLogged.compareAndSet(false, true)) {
                    logger.warn(
                        "Skipping task $name because the current environment does not support Android emulator virtualization."
                    )
                }
                throw StopExecutionException("Android emulator virtualization is unavailable.")
            }

            fun logSkip(message: String) {
                if (instrumentationSkipLogged.compareAndSet(false, true)) {
                    logger.warn(message)
                }
            }

            val devices = connectedDeviceCheck.readyDevices
            val executable = adbExecutable
            if (devices.isEmpty() || executable == null || !executable.exists()) {
                val message = connectedDeviceCheck.warningMessage
                if (message != null) {
                    logSkip(message)
                } else {
                    logSkip("Skipping task $name because no connected Android devices/emulators were detected.")
                }
                throw StopExecutionException("No responsive Android devices available for connected tests.")
            }

            val minimumApiLevel = minimumConnectedTestApiLevel
            var skipReason: String? = null

            val responsiveSerial = devices.firstOrNull { serial ->
                val apiLevel = queryDeviceApiLevel(serial)

                when {
                    apiLevel == null -> {
                        if (skipReason == null) {
                            skipReason =
                                "Skipping task $name because connected Android device $serial did not report an API level."
                        }
                        false
                    }

                    apiLevel < minimumApiLevel -> {
                        if (skipReason == null) {
                            skipReason =
                                "Skipping task $name because connected Android device $serial reports API level $apiLevel, but API level $minimumApiLevel or higher is required for connected tests."
                        }
                        false
                    }

                    else -> true
                }
            }

            if (responsiveSerial == null) {
                val message = skipReason
                    ?: "Skipping task $name because no responsive Android devices/emulators were detected. " +
                        "Ensure an emulator is fully booted before running connected tests."
                logSkip(message)
                throw StopExecutionException("No responsive Android devices available for connected tests.")
            }

            if (!ensureDeviceReadyForApkInstall(responsiveSerial, logger)) {
                val message =
                    "Skipping task $name because Android device $responsiveSerial is not ready for APK installation."
                logSkip(message)
                throw StopExecutionException("Android device $responsiveSerial is not ready for APK installation.")
            }

            runDeviceHealthChecksBeforeTests(responsiveSerial, logger)
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
