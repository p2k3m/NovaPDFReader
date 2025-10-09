import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.MetricType
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kover)
}

val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

android {
    namespace = "com.novapdf.reader.core.io"
    compileSdk = versionCatalog.findVersion("androidCompileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = versionCatalog.findVersion("androidMinSdk").get().requiredVersion.toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":domain:model"))
    api(project(":infra:logging"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.caffeine)
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.room.ktx)

    api(libs.androidx.room.runtime)
    api(libs.pdfium.android)
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.work.runtime)

    implementation(libs.androidx.room.common)
    implementation(libs.androidx.sqlite)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.checkerframework.qual)

    runtimeOnly(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.work)

    ksp(libs.androidx.room.compiler)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)
}

kotlin {
    jvmToolchain(17)
}

koverReport {
    defaults {
        verify {
            rule("Core IO line coverage") {
                bound {
                    minValue = 75
                    metric = MetricType.LINE
                    aggregation = AggregationType.COVERED_PERCENTAGE
                }
            }
            rule("Core IO branch coverage") {
                bound {
                    minValue = 55
                    metric = MetricType.BRANCH
                    aggregation = AggregationType.COVERED_PERCENTAGE
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}
