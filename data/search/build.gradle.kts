import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

android {
    namespace = "com.novapdf.reader.search.index"
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
    implementation(project(":data:repositories"))
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.annotation)
    implementation(libs.lucene.core)
    implementation(libs.lucene.queryparser)
    implementation(libs.pdfbox.android)
    implementation(libs.play.services.mlkit.text.recognition)
    implementation(libs.play.services.mlkit.text.recognition.common)
    implementation(libs.play.services.tasks)
    implementation(libs.mlkit.vision.common)

    runtimeOnly(libs.kotlinx.coroutines.android)
    runtimeOnly(libs.lucene.analyzers.common)
}

kotlin {
    jvmToolchain(17)
}
