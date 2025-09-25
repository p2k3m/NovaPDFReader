
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import java.io.File

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

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
    id("androidx.baselineprofile")
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
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

val androidExtension = extensions.getByType<ApplicationExtension>()
val releaseSigningConfig = androidExtension.signingConfigs.getByName("release")

val targetProject = project

gradle.taskGraph.whenReady(Action { graph ->
    val needsReleaseSigning = graph.allTasks.any { task ->
        task.project == targetProject && task.name.contains("Release")
    }
    if (needsReleaseSigning) {
        val releaseKeystore = targetProject.resolveReleaseKeystore()
        releaseSigningConfig.apply {
            storeFile = releaseKeystore
            storePassword = targetProject.resolveSigningCredential("NOVAPDF_RELEASE_STORE_PASSWORD")
            keyAlias = targetProject.resolveSigningCredential("NOVAPDF_RELEASE_KEY_ALIAS")
            keyPassword = targetProject.resolveSigningCredential("NOVAPDF_RELEASE_KEY_PASSWORD")
        }
    }
})

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
    testImplementation("org.mockito:mockito-inline:5.11.0")
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
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    baselineProfile(project(":baselineprofile"))
}

kotlin {
    jvmToolchain(17)
}

afterEvaluate {
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")

    tasks.register<Test>("adaptiveFlowPerformance") {
        group = "performance"
        description = "Runs Adaptive Flow timing regression checks."
        testClassesDirs = debugUnitTest.get().testClassesDirs
        classpath = debugUnitTest.get().classpath
        useJUnitPlatform()
        filter {
            includeTestsMatching("com.novapdf.reader.AdaptiveFlowManagerTest.readingSpeedRespondsToPageChanges")
        }
    }

    tasks.register<Test>("frameMonitoringPerformance") {
        group = "performance"
        description = "Executes frame monitoring diagnostics for Adaptive Flow."
        testClassesDirs = debugUnitTest.get().testClassesDirs
        classpath = debugUnitTest.get().classpath
        useJUnitPlatform()
        filter {
            includeTestsMatching("com.novapdf.reader.AdaptiveFlowManagerTest.frameMetricsReactToJank")
        }
        shouldRunAfter("adaptiveFlowPerformance")
    }
}
