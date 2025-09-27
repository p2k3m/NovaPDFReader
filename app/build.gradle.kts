
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.File
import java.math.BigDecimal
import java.util.Properties
import java.util.concurrent.TimeUnit

fun Project.resolveSigningCredential(name: String, default: String? = null): String =
    (findProperty(name) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: default?.takeIf { it.isNotBlank() }
        ?: throw GradleException(
            "Missing signing credential \"$name\". Provide it via gradle.properties or as an environment variable."
        )

fun Project.resolveReleaseKeystore(defaultPath: String = "signing/release.keystore"): File {
    val keystorePath = resolveSigningCredential("NOVAPDF_RELEASE_KEYSTORE", defaultPath)
    val candidate = File(keystorePath).let { path ->
        if (path.isAbsolute) path else rootProject.file(path)
    }
    if (!candidate.exists()) {
        throw GradleException(
            "Release keystore missing. Expected it at \"${candidate.absolutePath}\" or override NOVAPDF_RELEASE_KEYSTORE."
        )
    }
    return candidate
}

inline fun <reified T : Task> TaskContainer.namedOrNull(name: String): TaskProvider<T>? =
    try {
        named(name, T::class.java)
    } catch (_: UnknownTaskException) {
        null
    }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
    id("androidx.baselineprofile")
    id("jacoco")
}

android {
    namespace = "com.novapdf.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novapdf.reader"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        create("release") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xcontext-receivers"
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
        animationsDisabled = false
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    adbOptions {
        installOptions.add("-t")

        val explicitNoStreaming = parseOptionalBoolean(
            (findProperty("novapdf.enableNoStreamingInstallOption") as? String)
                ?: System.getenv("NOVAPDF_ENABLE_NO_STREAMING")
        )
        val enableNoStreaming = explicitNoStreaming ?: false

        if (enableNoStreaming) {
            // Allow opting into legacy installs because some hosted emulators
            // intermittently fail session commits unless the legacy path is used.
            installOptions.add("--no-streaming")
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

jacoco {
    toolVersion = "0.8.11"
}

val androidExtension = extensions.getByType<ApplicationExtension>()
val releaseSigningConfig = androidExtension.signingConfigs.getByName("release")

val targetProject = project

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

val isCiEnvironment = parseOptionalBoolean(System.getenv("CI")) ?: false

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

if (requireConnectedDevice != true) {
    if (isCiEnvironment && allowCiConnectedTests != true) {
        afterEvaluate {
            tasks.matching { task ->
                task.name == "connectedAndroidTest" ||
                    (task.name.startsWith("connected") && task.name.endsWith("AndroidTest"))
            }.configureEach {
                onlyIf {
                    logger.warn(
                        "Skipping task $name because connected Android tests are disabled on CI. " +
                            "Set novapdf.allowCiConnectedTests=true or NOVAPDF_ALLOW_CI_CONNECTED_TESTS=true to enable them."
                    )
                    false
                }
            }
        }
    } else {
        val sdkDirectory = locateAndroidSdkDir()
        if (sdkDirectory == null) {
            afterEvaluate {
                tasks.namedOrNull<Task>("connectedAndroidTest")?.configure {
                    setDependsOn(emptyList<Any>())
                    doFirst {
                        logger.warn(
                            "Skipping connectedAndroidTest because the Android SDK was not found. " +
                                "Set ANDROID_SDK_ROOT or create a local.properties with sdk.dir to enable instrumentation builds."
                        )
                    }
                }
            }
        } else {
            val platformTools = File(sdkDirectory, "platform-tools")
            val adbExecutable = sequenceOf("adb", "adb.exe")
                .map { executable -> File(platformTools, executable) }
                .firstOrNull { it.exists() }

            val hasConnectedDevice by lazy {
                when {
                    adbExecutable == null -> {
                        logger.warn("ADB executable not found. Skipping connected Android tests.")
                        false
                    }
                    !adbExecutable.exists() -> {
                        logger.warn(
                            "ADB executable not found at ${adbExecutable.absolutePath}. Skipping connected Android tests."
                        )
                        false
                    }
                    else -> {
                        runCatching {
                            val process = ProcessBuilder(adbExecutable.absolutePath, "devices")
                                .redirectErrorStream(true)
                                .start()
                            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                                process.destroy()
                                logger.warn(
                                    "Timed out while checking for connected Android devices. Skipping connected Android tests."
                                )
                                false
                            } else {
                                process.inputStream.bufferedReader().useLines { lines ->
                                    lines.drop(1).any { line ->
                                        val trimmed = line.trim()
                                        val isDeviceListing = '\t' in line
                                        isDeviceListing &&
                                            trimmed.isNotEmpty() &&
                                            !trimmed.endsWith("offline", ignoreCase = true)
                                    }
                                }
                            }
                        }.getOrElse { error ->
                            logger.warn(
                                "Unable to query connected Android devices via adb. Skipping connected Android tests.",
                                error
                            )
                            false
                        }
                    }
                }
            }

            tasks.matching { task ->
                task.name == "connectedAndroidTest" ||
                    (task.name.startsWith("connected") && task.name.endsWith("AndroidTest"))
            }.configureEach {
                onlyIf {
                    val hasDevice = hasConnectedDevice
                    if (!hasDevice) {
                        logger.warn("No connected Android devices/emulators detected. Skipping task $name.")
                    }
                    hasDevice
                }
            }

            tasks.matching { task ->
                task.name.contains("Benchmark") &&
                    (task.name.startsWith("assemble", ignoreCase = true) ||
                        task.name.startsWith("bundle", ignoreCase = true) ||
                        task.name.startsWith("package", ignoreCase = true))
            }.configureEach {
                onlyIf {
                    val hasDevice = hasConnectedDevice
                    if (!hasDevice) {
                        logger.warn("Skipping task $name because no connected Android devices/emulators were detected.")
                    }
                    hasDevice
                }
            }
        }
    }
}

val signingTaskPrefixes = listOf(
    "assemble",
    "bundle",
    "install",
    "lintVital",
    "package",
    "publish",
    "sign",
    "upload"
)

fun String.taskNameFromPath(): String = substringAfterLast(":")

fun String.targetsReleaseLikeVariant(): Boolean =
    contains("Release", ignoreCase = true) || contains("Benchmark", ignoreCase = true)

fun String.isPackagingOrPublishingTask(): Boolean =
    signingTaskPrefixes.any { prefix -> startsWith(prefix, ignoreCase = true) }

val needsReleaseSigning = gradle.startParameter.taskNames.any { taskPath ->
    val taskName = taskPath.taskNameFromPath()
    taskName.targetsReleaseLikeVariant() && taskName.isPackagingOrPublishingTask()
}

var releaseSigningConfigured = false

fun configureReleaseSigning() {
    if (releaseSigningConfigured) {
        return
    }
    releaseSigningConfigured = true

    val releaseKeystoreResult = runCatching { targetProject.resolveReleaseKeystore() }
    if (releaseKeystoreResult.isSuccess) {
        val releaseKeystore = releaseKeystoreResult.getOrThrow()
        releaseSigningConfig.apply {
            storeFile = releaseKeystore
            storePassword = targetProject.resolveSigningCredential("NOVAPDF_RELEASE_STORE_PASSWORD")
            keyAlias = targetProject.resolveSigningCredential("NOVAPDF_RELEASE_KEY_ALIAS")
            keyPassword = targetProject.resolveSigningCredential("NOVAPDF_RELEASE_KEY_PASSWORD")
        }
    } else {
        val debugSigningConfig = androidExtension.signingConfigs.getByName("debug")
        releaseSigningConfig.initWith(debugSigningConfig)
        releaseSigningConfig.apply {
            // Ensure modern signature schemes stay enabled after initWith.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
        targetProject.logger.warn(
            "Release keystore unavailable (${releaseKeystoreResult.exceptionOrNull()?.message}). " +
                "Falling back to the debug signing configuration."
        )
    }
}

if (needsReleaseSigning) {
    configureReleaseSigning()
} else {
    gradle.taskGraph.whenReady(
        object : Action<TaskExecutionGraph> {
            override fun execute(taskGraph: TaskExecutionGraph) {
                if (taskGraph.allTasks.any { task ->
                        val taskName = task.name
                        taskName.targetsReleaseLikeVariant() && taskName.isPackagingOrPublishingTask()
                    }
                ) {
                    configureReleaseSigning()
                }
            }
        }
    )
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("com.google.errorprone:error_prone_annotations:2.26.1")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout-compose:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.robolectric:robolectric:4.12.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.work:work-testing:2.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestUtil("androidx.test:orchestrator:1.4.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    baselineProfile(project(":baselineprofile"))
}

kotlin {
    jvmToolchain(17)
}

afterEvaluate {
    val releaseUnitTest = tasks.namedOrNull<Test>("testReleaseUnitTest")
    val jacocoReport = tasks.namedOrNull<JacocoReport>("jacocoTestReleaseUnitTestReport")
    val jacocoVerification = tasks.namedOrNull<JacocoCoverageVerification>("jacocoTestReleaseUnitTestCoverageVerification")

    releaseUnitTest?.configure {
        jacocoReport?.let { finalizedBy(it) }
        jacocoVerification?.let { finalizedBy(it) }
    }

    jacocoReport?.configure {
        releaseUnitTest?.let { dependsOn(it) }
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    jacocoVerification?.configure {
        releaseUnitTest?.let { dependsOn(it) }
        violationRules {
            rule {
                limit {
                    minimum = BigDecimal("0.80")
                }
            }
        }
    }

    releaseUnitTest?.let { releaseTest ->
        tasks.register<Test>("adaptiveFlowPerformance") {
            group = "performance"
            description = "Runs Adaptive Flow timing regression checks."
            val releaseUnitTestTask = releaseTest.get()
            testClassesDirs = releaseUnitTestTask.testClassesDirs
            classpath = releaseUnitTestTask.classpath
            useJUnitPlatform()
            filter {
                includeTestsMatching("com.novapdf.reader.AdaptiveFlowManagerTest.readingSpeedRespondsToPageChanges")
            }
        }

        tasks.register<Test>("frameMonitoringPerformance") {
            group = "performance"
            description = "Executes frame monitoring diagnostics for Adaptive Flow."
            val releaseUnitTestTask = releaseTest.get()
            testClassesDirs = releaseUnitTestTask.testClassesDirs
            classpath = releaseUnitTestTask.classpath
            useJUnitPlatform()
            filter {
                includeTestsMatching("com.novapdf.reader.AdaptiveFlowManagerTest.frameMetricsReactToJank")
            }
            shouldRunAfter("adaptiveFlowPerformance")
        }
    }
}
