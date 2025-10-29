import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val versionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

android {
    namespace = "com.novapdf.reader.integration.aws"
    compileSdk = versionCatalog.findVersion("androidCompileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = versionCatalog.findVersion("androidMinSdk").get().requiredVersion.toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val accessKeyId = project.resolveAwsCredential("AWS_ACCESS_KEY_ID")
        val secretAccessKey = project.resolveAwsCredential("AWS_SECRET_ACCESS_KEY")
        val sessionToken = project.resolveOptionalAwsCredential("AWS_SESSION_TOKEN")
        val region = project.resolveAwsRegion()

        buildConfigField("String", "AWS_ACCESS_KEY_ID", "\"${accessKeyId.asJavaStringLiteral()}\"")
        buildConfigField("String", "AWS_SECRET_ACCESS_KEY", "\"${secretAccessKey.asJavaStringLiteral()}\"")
        buildConfigField("String", "AWS_REGION", "\"${region.asJavaStringLiteral()}\"")
        buildConfigField(
            "String",
            "AWS_SESSION_TOKEN",
            sessionToken?.let { "\"${it.asJavaStringLiteral()}\"" } ?: "\"\""
        )
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
    api(project(":infra:storage"))
    implementation(project(":core:cache"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}

kotlin {
    jvmToolchain(17)
}

private fun Project.resolveAwsCredential(name: String): String =
    resolveOptionalAwsCredential(name)
        ?: run {
            logger.warn(
                "Missing AWS credential \"$name\". Using an empty placeholder. " +
                    "Provide the credential via gradle.properties or as an environment variable for production builds."
            )
            ""
        }

private fun Project.resolveOptionalAwsCredential(name: String): String? =
    (findProperty(name) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

private fun Project.resolveAwsRegion(): String =
    listOf(
        (findProperty("AWS_REGION") as? String),
        System.getenv("AWS_REGION"),
        System.getenv("AWS_DEFAULT_REGION"),
    ).firstOrNull { !it.isNullOrBlank() }?.trim()
        ?: "us-east-1"

private fun String.asJavaStringLiteral(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")
