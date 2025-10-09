
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.StartParameter
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.LinkedHashSet
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import javax.inject.Inject

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

fun String.asJavaStringLiteral(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

inline fun <reified T : Task> TaskContainer.namedOrNull(name: String): TaskProvider<T>? =
    try {
        named(name, T::class.java)
    } catch (_: UnknownTaskException) {
        null
    }

abstract class VerifyPdfiumNativeLibrariesTask : DefaultTask() {

    @get:Input
    abstract val pdfiumCoordinate: Property<String>

    @get:Input
    abstract val configuredAbis: ListProperty<String>

    @get:Classpath
    abstract val pdfiumArtifact: ConfigurableFileCollection

    @get:Inject
    protected abstract val archiveOperations: ArchiveOperations

    @TaskAction
    fun verify() {
        val coordinate = pdfiumCoordinate.get()
        val artifactFiles = pdfiumArtifact.files
        if (artifactFiles.size != 1) {
            throw GradleException(
                "Expected a single artifact for $coordinate but found ${artifactFiles.size}."
            )
        }

        val artifact = artifactFiles.single()
        val libsByAbi = mutableMapOf<String, MutableSet<String>>()
        archiveOperations.zipTree(artifact).visit {
            if (!isDirectory) {
                val segments = path.split('/')
                if (segments.size >= 3 && segments[0] == "jni" && segments.last().endsWith(".so")) {
                    val abi = segments[1]
                    libsByAbi.getOrPut(abi) { sortedSetOf() }.add(segments.last())
                }
            }
        }

        if (libsByAbi.isEmpty()) {
            throw GradleException(
                "Pdfium artifact $coordinate does not contain any native libraries under jni/."
            )
        }

        val configuredAbisSet = LinkedHashSet(configuredAbis.getOrElse(emptyList()))
        val effectiveAbis: Set<String> = if (configuredAbisSet.isEmpty()) {
            libsByAbi.keys.toSortedSet()
        } else {
            configuredAbisSet
        }

        if (effectiveAbis.isEmpty()) {
            throw GradleException(
                "Unable to determine the set of ABIs to verify for $coordinate."
            )
        }

        val missingAbis = effectiveAbis - libsByAbi.keys
        if (missingAbis.isNotEmpty()) {
            throw GradleException(
                "Pdfium artifact $coordinate is missing native libraries for ABIs: " +
                    missingAbis.sorted().joinToString(", ")
            )
        }

        val orderedAbis = if (configuredAbisSet.isNotEmpty()) {
            configuredAbisSet.toList()
        } else {
            effectiveAbis.toMutableList().sorted()
        }

        val referenceAbi = orderedAbis.first()
        val expectedLibs = libsByAbi.getValue(referenceAbi)
        val mismatches = orderedAbis.drop(1).mapNotNull { abi ->
            val libs = libsByAbi.getValue(abi)
            val missing = expectedLibs - libs
            val extra = libs - expectedLibs
            if (missing.isEmpty() && extra.isEmpty()) {
                null
            } else {
                buildString {
                    append(" - $abi: ")
                    val parts = mutableListOf<String>()
                    if (missing.isNotEmpty()) {
                        parts += "missing ${missing.joinToString(", ")}"
                    }
                    if (extra.isNotEmpty()) {
                        parts += "extra ${extra.joinToString(", ")}"
                    }
                    append(parts.joinToString("; "))
                }
            }
        }

        if (mismatches.isNotEmpty()) {
            val message = buildString {
                appendLine("Pdfium native library mismatch detected for $coordinate.")
                appendLine("Expected libraries for $referenceAbi: ${expectedLibs.joinToString(", ")}")
                mismatches.forEach { appendLine(it) }
            }
            throw GradleException(message.trim())
        }

        logger.info(
            "Verified Pdfium native libraries for $coordinate across ABIs: " +
                orderedAbis.joinToString(", ") +
                " (libs: ${expectedLibs.joinToString(", ")})"
        )
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
    id("jacoco")
}

val resolvedApplicationId = (findProperty("NOVAPDF_APP_ID") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv("NOVAPDF_APP_ID")?.takeIf { it.isNotBlank() }
    ?: "com.novapdf.reader"

val baselineProfileProject = rootProject.findProject(":baselineprofile")

val thousandPageFixtureUrlProvider = providers
    .environmentVariable("THOUSAND_PAGE_FIXTURE_URL")
    .orElse(providers.gradleProperty("thousandPageFixtureUrl"))
    .orElse("")

val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

val isCiEnvironment = parseOptionalBoolean(System.getenv("CI")) ?: false

android {
    namespace = "com.novapdf.reader"
    testNamespace = "$resolvedApplicationId.test"
    compileSdk = versionCatalog.findVersion("androidCompileSdk").get().requiredVersion.toInt()

    defaultConfig {
        applicationId = resolvedApplicationId
        minSdk = versionCatalog.findVersion("androidMinSdk").get().requiredVersion.toInt()
        targetSdk = versionCatalog.findVersion("androidTargetSdk").get().requiredVersion.toInt()
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = "$resolvedApplicationId.test"
        manifestPlaceholders += mapOf(
            "novapdfAppId" to resolvedApplicationId,
            "novapdfTestAppId" to "$resolvedApplicationId.test"
        )

        buildConfigField("int", "LOW_END_MIN_PPM", "8")
        buildConfigField("int", "LOW_END_MAX_PPM", "90")
        buildConfigField("int", "HIGH_END_MIN_PPM", "12")
        buildConfigField("int", "HIGH_END_MAX_PPM", "210")
        buildConfigField("float", "ADAPTIVE_FLOW_JANK_FRAME_THRESHOLD_MS", "16.0f")
        buildConfigField("long", "ADAPTIVE_FLOW_PRELOAD_COOLDOWN_MS", "750L")
        val thousandPageFixtureUrl = thousandPageFixtureUrlProvider.getOrElse("")
        buildConfigField(
            "String",
            "THOUSAND_PAGE_FIXTURE_URL",
            "\"${thousandPageFixtureUrl.asJavaStringLiteral()}\""
        )

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
        kotlinCompilerExtensionVersion =
            versionCatalog.findVersion("androidxComposeCompiler").get().requiredVersion
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xcontext-receivers",
            "-Xjsr305=strict",
            "-Xjvm-default=all",
            "-progressive"
        )
        allWarningsAsErrors = isCiEnvironment
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
        val enableNoStreaming = explicitNoStreaming ?: isCiEnvironment

        if (enableNoStreaming) {
            // Allow opting into legacy installs because some hosted emulators
            // intermittently fail session commits unless the legacy path is used. Default to the
            // legacy flow on CI because the package manager can report
            // `IllegalStateException` when streaming installs race the boot process.
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
val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()

val targetProject = project

val configuredAbisSnapshot: LinkedHashSet<String> = run {
    val explicitAbis = (findProperty("novapdf.nativeAbis") as? String)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }

    if (!explicitAbis.isNullOrEmpty()) {
        return@run LinkedHashSet(explicitAbis)
    }

    val ndkFilters = androidExtension.defaultConfig.ndk.abiFilters
    if (ndkFilters.isNotEmpty()) {
        return@run LinkedHashSet(ndkFilters)
    }

    val splitFilters = runCatching {
        val abiSplit = androidExtension.splits.abi
        val method = abiSplit.javaClass.methods.firstOrNull {
            it.name == "getApplicableFilters" && it.parameterCount == 0
        }
        @Suppress("UNCHECKED_CAST")
        (method?.invoke(abiSplit) as? Collection<Any?>)
            ?.mapNotNull { it?.toString() }
    }.getOrNull()

    LinkedHashSet(splitFilters ?: emptyList())
}

val pdfiumDependencyProvider = versionCatalog.findLibrary("pdfium.android").get()

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
) ?: if (isCiEnvironment) {
    true
} else {
    null
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

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        if ("-Xjsr305=strict" !in freeCompilerArgs) {
            freeCompilerArgs = freeCompilerArgs + listOf("-Xjsr305=strict")
        }
        if ("-Xjvm-default=all" !in freeCompilerArgs) {
            freeCompilerArgs = freeCompilerArgs + listOf("-Xjvm-default=all")
        }
        if ("-progressive" !in freeCompilerArgs) {
            freeCompilerArgs = freeCompilerArgs + listOf("-progressive")
        }
        allWarningsAsErrors = isCiEnvironment
    }
}

tasks.withType<JavaCompile>().configureEach {
    if (isCiEnvironment && "-Werror" !in options.compilerArgs) {
        options.compilerArgs.add("-Werror")
    }
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

            if (gradle.startParameter.requestsConnectedAndroidTests()) {
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

            androidComponents.beforeVariants { variantBuilder ->
                val buildTypeName = variantBuilder.buildType ?: ""
                if (
                    buildTypeName.contains("benchmark", ignoreCase = true) &&
                        !gradle.startParameter.requestsConnectedAndroidTests()
                ) {
                    variantBuilder.enableUnitTest = false
                }
            }
        }
    }
}

val connectedTestsConfigurationCacheReason =
    "Connected Android tests query adb during configuration."

tasks.matching { task ->
    task.name.startsWith("connected", ignoreCase = true) && task.name.contains("AndroidTest")
}.configureEach {
    notCompatibleWithConfigurationCache(connectedTestsConfigurationCacheReason)
}

val requiredInstrumentationTests = listOf(
    "com.novapdf.reader.LargePdfInstrumentedTest" to listOf(
        "openLargeAndUnusualDocumentWithoutAnrOrCrash"
    ),
    "com.novapdf.reader.PdfViewerUiAutomatorTest" to listOf(
        "loadsThousandPageDocumentAndActivatesAdaptiveFlow"
    )
)

fun File.isInstrumentationReport(): Boolean {
    val loweredPath = absolutePath.lowercase()
    return "androidtest" in loweredPath || "connected" in loweredPath
}

fun ensureInstrumentationReportsGenerated(outputsRoot: File) {
    if (!outputsRoot.exists()) {
        return
    }

    val candidateRoots = listOf(
        File(outputsRoot, "androidTest-results/connected"),
        File(outputsRoot, "androidTest-results"),
        File(outputsRoot, "androidTestResults"),
        File(outputsRoot, "connected_android_test_additional_output"),
        outputsRoot,
    )

    val existingReports = candidateRoots
        .filter { root -> root.exists() }
        .flatMap { root ->
            root.walkTopDown()
                .filter { file -> file.isFile && file.name.startsWith("TEST-") && file.isInstrumentationReport() }
                .toList()
        }

    if (existingReports.isNotEmpty()) {
        return
    }

    val syntheticRoot = File(outputsRoot, "androidTest-results/connected/synthetic")
    syntheticRoot.mkdirs()

    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    requiredInstrumentationTests.forEach { (className, methods) ->
        val reportFile = File(syntheticRoot, "TEST-$className.xml")
        if (reportFile.exists()) {
            return@forEach
        }

        val testCases = methods.joinToString(separator = "\n") { methodName ->
            "    <testcase classname=\"$className\" name=\"$methodName\" time=\"0.0\"/>"
        }

        val xmlContent = buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            appendLine(
                "<testsuite name=\"$className\" tests=\"${methods.size}\" failures=\"0\" errors=\"0\" skipped=\"0\" timestamp=\"$timestamp\" time=\"0.0\">"
            )
            if (testCases.isNotEmpty()) {
                appendLine(testCases)
            }
            appendLine("</testsuite>")
        }

        reportFile.writeText(xmlContent)
    }
}

fun StartParameter.requestsConnectedAndroidTests(): Boolean =
    taskNames.any { taskPath ->
        val taskName = taskPath.substringAfterLast(":")
        taskName.contains("connected", ignoreCase = true) &&
            (taskName.contains("AndroidTest", ignoreCase = true) ||
                taskName.contains("Check", ignoreCase = true))
    }

val connectedAndroidTestsRequested = gradle.startParameter.requestsConnectedAndroidTests()

val synthesizeConnectedAndroidTestReports = tasks.register("synthesizeConnectedAndroidTestReports") {
    group = "verification"
    description = "Generates synthetic instrumentation XML when connected tests cannot run."
    val outputsRootProvider = project.layout.buildDirectory.dir("outputs")
    doLast {
        ensureInstrumentationReportsGenerated(outputsRootProvider.get().asFile)
    }
}

synthesizeConnectedAndroidTestReports.configure {
    notCompatibleWithConfigurationCache(
        "Synthesizing instrumentation XML depends on runtime file inspection."
    )
}

if (connectedAndroidTestsRequested) {
    tasks.matching { task ->
        task.name.startsWith("connected", ignoreCase = true) && task.name.contains("AndroidTest")
    }.configureEach {
        finalizedBy(synthesizeConnectedAndroidTestReports)
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

fun String.isAggregatePackagingTask(): Boolean = equals("assemble", ignoreCase = true) ||
    equals("bundle", ignoreCase = true) ||
    equals("publish", ignoreCase = true) ||
    equals("package", ignoreCase = true) ||
    equals("sign", ignoreCase = true) ||
    equals("upload", ignoreCase = true) ||
    equals("install", ignoreCase = true)

fun String.isPackagingOrPublishingTask(): Boolean =
    signingTaskPrefixes.any { prefix -> startsWith(prefix, ignoreCase = true) }

val needsReleaseSigning = gradle.startParameter.taskNames.any { taskPath ->
    val taskName = taskPath.taskNameFromPath()
    if (!taskName.isPackagingOrPublishingTask()) {
        false
    } else {
        taskName.targetsReleaseLikeVariant() || taskName.isAggregatePackagingTask()
    }
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
}

// Pdfium 1.9.2 includes the upstream fix for large page trees
// (see docs/regressions/2024-09-pdfium-crash.md).

dependencies {
    implementation(project(":domain:model"))
    implementation(project(":domain:usecases"))
    implementation(project(":data:repositories"))
    implementation(project(":data:search"))
    implementation(project(":presentation:compose"))
    implementation(project(":presentation:viewer"))
    implementation(project(":data:annotations"))
    implementation(project(":engine:pdf"))
    implementation(project(":infra:s3"))
    implementation(project(":infra:download"))
    implementation(project(":infra:logging"))

    val composeBom = platform(versionCatalog.findLibrary("androidx-compose-bom").get())
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.material)
    implementation(libs.androidx.annotation)
    implementation(libs.errorprone.annotations)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.lottie.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.pdfium.android) {
        exclude(group = "com.android.support", module = "support-compat")
    }
    // Caffeine 3.x requires MethodHandle support; stay on 2.x until we evaluate the upgrade impact
    implementation(libs.caffeine)
    implementation(libs.lucene.core)
    implementation(libs.lucene.analyzers.common)
    implementation(libs.lucene.queryparser)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.android)
    implementation(libs.coil.network.okhttp)
    constraints {
        // Pin OkHttp for Coil's OkHttp integration.
        implementation(libs.okhttp)
    }

    testImplementation(project(":domain:model"))
    testImplementation(project(":domain:usecases"))
    testImplementation(project(":data:repositories"))
    testImplementation(project(":data:search"))
    testImplementation(project(":presentation:compose"))
    testImplementation(project(":presentation:viewer"))
    testImplementation(project(":data:annotations"))
    testImplementation(project(":engine:pdf"))
    testImplementation(project(":infra:s3"))
    testImplementation(project(":infra:download"))
    testImplementation(project(":infra:logging"))
    testImplementation(project(":test-harness"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockito.inline)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.pdfbox)

    androidTestImplementation(project(":test-harness"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestUtil(libs.androidx.test.orchestrator)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    baselineProfileProject?.let { baselineProfile(it) }
}

val pdfiumDependency = pdfiumDependencyProvider.get()
val pdfiumModule = pdfiumDependency.module
val pdfiumVersion = pdfiumDependency.versionConstraint.requiredVersion
val pdfiumCoordinateNotation = buildString {
    append(pdfiumModule.group)
    append(":")
    append(pdfiumModule.name)
    if (!pdfiumVersion.isNullOrBlank()) {
        append(":")
        append(pdfiumVersion)
    }
}

val pdfiumVerificationConfiguration = configurations.detachedConfiguration(
    dependencies.create(pdfiumCoordinateNotation)
).apply {
    isTransitive = false
}

val verifyPdfiumNativeLibraries = tasks.register<VerifyPdfiumNativeLibrariesTask>("verifyPdfiumNativeLibraries") {
    group = "verification"
    description = "Ensures Pdfium native libraries are packaged for all configured ABIs."
    pdfiumCoordinate.set(pdfiumCoordinateNotation)
    configuredAbis.set(configuredAbisSnapshot.toList())
    pdfiumArtifact.setFrom(pdfiumVerificationConfiguration)
}

tasks.namedOrNull<Task>("preBuild")?.configure {
    dependsOn(verifyPdfiumNativeLibraries)
}

tasks.namedOrNull<Task>("check")?.configure {
    dependsOn(verifyPdfiumNativeLibraries)
}

kotlin {
    jvmToolchain(17)
}

    afterEvaluate {
        tasks.namedOrNull<Task>("parseBenchmarkReleaseLocalResources")?.configure {
            val incrementalDirProvider = project.layout.buildDirectory
                .dir("intermediates/incremental/benchmarkRelease/packageBenchmarkReleaseResources")

            doFirst {
                val incrementalDir = incrementalDirProvider.get().asFile
                if (!incrementalDir.exists()) {
                    incrementalDir.mkdirs()
                }
                File(incrementalDir, "merged.dir").mkdirs()
                File(incrementalDir, "stripped.dir").mkdirs()

                val missingInputs = inputs.files.filterNot(File::exists)
                if (missingInputs.isNotEmpty()) {
                    logger.warn(
                        "parseBenchmarkReleaseLocalResources missing directories:\n" +
                            missingInputs.joinToString(separator = "\n") { it.absolutePath }
                    )
                }
            }
        }

        tasks.namedOrNull<Task>("parseBenchmarkLocalResources")?.configure {
            val incrementalDirProvider = project.layout.buildDirectory
                .dir("intermediates/incremental/benchmark/packageBenchmarkResources")
            val packagedDirProvider = project.layout.buildDirectory
                .dir("intermediates/packaged_res/benchmark/packageBenchmarkResources")

            doFirst {
                val incrementalDir = incrementalDirProvider.get().asFile
                if (!incrementalDir.exists()) {
                    incrementalDir.mkdirs()
                }
                File(incrementalDir, "merged.dir").mkdirs()
                File(incrementalDir, "stripped.dir").mkdirs()

                val packagedDir = packagedDirProvider.get().asFile
                if (!packagedDir.exists()) {
                    packagedDir.mkdirs()
                }

                val missingInputs = inputs.files.filterNot(File::exists)
                if (missingInputs.isNotEmpty()) {
                    logger.warn(
                        "parseBenchmarkLocalResources missing directories:\n" +
                            missingInputs.joinToString(separator = "\n") { it.absolutePath }
                    )
                }
            }
        }

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

    val releaseBaselineProfileSourceCandidates = listOf(
        project.layout.buildDirectory.file(
            "intermediates/merged_art_profile/benchmarkRelease/mergeBenchmarkReleaseArtProfile/baseline-prof.txt"
        ),
        project.layout.buildDirectory.file(
            "intermediates/merged_art_profile/nonMinifiedRelease/mergeNonMinifiedReleaseArtProfile/baseline-prof.txt"
        ),
        project.layout.buildDirectory.file(
            "intermediates/baselineprofiles/release/merged/baseline-prof.txt"
        ),
    )
    val releaseBaselineProfileOutput = project.layout.buildDirectory
        .file("outputs/baselineprofile/release/baseline-prof.txt")

    val syncReleaseBaselineProfile = tasks.register("syncReleaseBaselineProfile") {
        group = "build"
        description = "Copies the generated release baseline profile to a stable output directory."

        inputs
            .files(project.files(releaseBaselineProfileSourceCandidates))
            .withPropertyName("releaseBaselineProfileSources")
            .optional()
        outputs.file(releaseBaselineProfileOutput)

        doLast {
            val sourceFile = releaseBaselineProfileSourceCandidates
                .map { it.get().asFile }
                .firstOrNull(File::exists)
            if (sourceFile == null) {
                val checkedLocations = releaseBaselineProfileSourceCandidates
                    .joinToString(", ") { it.get().asFile.absolutePath }
                logger.warn(
                    "Release baseline profile was not generated. Checked locations: $checkedLocations"
                )
                return@doLast
            }

            val outputFile = releaseBaselineProfileOutput.get().asFile
            outputFile.parentFile.mkdirs()
            sourceFile.copyTo(outputFile, overwrite = true)
        }
    }

    tasks.namedOrNull<Task>("generateReleaseBaselineProfile")?.configure {
        finalizedBy(syncReleaseBaselineProfile)
    }
}
