plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("androidx.baselineprofile") version "1.2.4" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
