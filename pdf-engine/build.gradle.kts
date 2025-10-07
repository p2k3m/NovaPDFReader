import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.MetricType
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kover)
}

val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

android {
    namespace = "com.novapdf.reader.pdf.engine"
    compileSdk = versionCatalog.findVersion("androidCompileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = versionCatalog.findVersion("androidMinSdk").get().requiredVersion.toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("int", "LOW_END_MIN_PPM", "8")
        buildConfigField("int", "LOW_END_MAX_PPM", "90")
        buildConfigField("int", "HIGH_END_MIN_PPM", "12")
        buildConfigField("int", "HIGH_END_MAX_PPM", "210")
        buildConfigField("float", "ADAPTIVE_FLOW_JANK_FRAME_THRESHOLD_MS", "16.0f")
        buildConfigField("long", "ADAPTIVE_FLOW_PRELOAD_COOLDOWN_MS", "750L")
    }

    buildFeatures {
        buildConfig = true
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
    implementation(project(":core-model"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.kotlinx.coroutines.android)
}

kotlin {
    jvmToolchain(17)
}

koverReport {
    defaults {
        verify {
            rule("PDF engine line coverage") {
                bound {
                    minValue = 70
                    metric = MetricType.LINE
                    aggregation = AggregationType.COVERED_PERCENTAGE
                }
            }
            rule("PDF engine branch coverage") {
                bound {
                    minValue = 50
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
