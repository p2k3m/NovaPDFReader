import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

android {
    namespace = "com.novapdf.reader.storage"
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
    implementation(project(":core:cache"))
    implementation(project(":domain:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.pdfium.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

kotlin {
    jvmToolchain(17)
}
