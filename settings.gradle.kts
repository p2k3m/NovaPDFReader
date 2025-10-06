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
    versionCatalogs {
        create("libs") {
            val compileSdk = providers.gradleProperty("android.compileSdk").map(String::trim).get()
            val minSdk = providers.gradleProperty("android.minSdk").map(String::trim).get()
            val targetSdk = providers.gradleProperty("android.targetSdk").map(String::trim).get()

            version("androidCompileSdk", compileSdk)
            version("androidMinSdk", minSdk)
            version("androidTargetSdk", targetSdk)
        }
    }
}

rootProject.name = "NovaPDFReader"
include(":app")

fun isSdkDownloadEnabled(rootDir: File): Boolean {
    val gradlePropertiesFile = File(rootDir, "gradle.properties")
    if (!gradlePropertiesFile.exists()) {
        return false
    }

    val properties = Properties().apply {
        gradlePropertiesFile.inputStream().use { load(it) }
    }

    val raw = properties.getProperty("android.experimental.sdkDownload")?.trim()?.lowercase()
    return raw == "true" || raw == "1" || raw == "yes"
}

fun ensureManagedSdkLicenses(managedSdkDirectory: File) {
    val licensesDir = managedSdkDirectory.resolve("licenses").apply { mkdirs() }

    fun File.ensureHashes(vararg hashes: String) {
        val existing = if (exists()) {
            readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        } else {
            mutableSetOf()
        }

        var updated = false
        for (hash in hashes) {
            if (existing.add(hash)) {
                updated = true
            }
        }

        if (updated || !exists()) {
            writeText((existing.joinToString(System.lineSeparator())) + System.lineSeparator())
        }
    }

    licensesDir.resolve("android-sdk-license").ensureHashes(
        "d56f5187479451eabf01fb78af6dfcb131a6481e",
        "24333f8a63b6825ea9c5514f83c2829b004d1fee",
        "e9acab5b5fbb560a8e5d531e0f0d5797189a202a",
        "7b5f888b0de5cd1284a08f9f4168c1b724f8a513"
    )

    licensesDir.resolve("android-sdk-preview-license").ensureHashes(
        "84831b9409646a918e30573bab4c9c91346d8abd",
        "504667f4c0de7af1a06de9f4b1727b84351f2910"
    )
}

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

    val managedSdkDirectory = File(rootDir, ".gradle/android-sdk")

    val discovered = candidates.firstOrNull { it.exists() }
    if (discovered != null) {
        if (discovered.absoluteFile == managedSdkDirectory.absoluteFile) {
            ensureManagedSdkLicenses(managedSdkDirectory)
        }
        return discovered
    }

    if (!isSdkDownloadEnabled(rootDir)) {
        return null
    }

    if (!managedSdkDirectory.exists()) {
        managedSdkDirectory.mkdirs()
    }
    ensureManagedSdkLicenses(managedSdkDirectory)
    return managedSdkDirectory
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
