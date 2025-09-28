import java.io.File
import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NovaPDFReader"
include(":app")

fun discoverAndroidSdk(rootDir: File): File? {
    val localPropertiesFile = File(rootDir, "local.properties")
    val localPropertiesSdk = if (localPropertiesFile.exists()) {
        runCatching {
            Properties().apply {
                localPropertiesFile.inputStream().use { load(it) }
            }.getProperty("sdk.dir")
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        null
    }

    val candidates = listOfNotNull(
        System.getenv("ANDROID_SDK_ROOT"),
        System.getenv("ANDROID_HOME"),
        localPropertiesSdk,
        "/usr/local/lib/android/sdk"
    )
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { File(it) }

    return candidates.firstOrNull { it.exists() }
}

val discoveredAndroidSdk = discoverAndroidSdk(rootDir)

if (discoveredAndroidSdk != null) {
    val localPropertiesFile = File(rootDir, "local.properties")
    if (!localPropertiesFile.exists()) {
        val properties = Properties().apply {
            setProperty("sdk.dir", discoveredAndroidSdk.absolutePath.replace("\\", "\\\\"))
        }
        localPropertiesFile.outputStream().use { output ->
            properties.store(
                output,
                "Auto-generated to help Gradle locate the Android SDK on environments without local.properties"
            )
        }
        println("Created local.properties with sdk.dir=${discoveredAndroidSdk.absolutePath}")
    }

    include(":baselineprofile")
} else {
    println("Skipping :baselineprofile because no Android SDK was detected. Set ANDROID_SDK_ROOT/ANDROID_HOME or local.properties sdk.dir to enable it.")
}
