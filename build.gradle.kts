import com.diffplug.gradle.spotless.SpotlessExtension
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask
import org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.dependency.analysis) apply false
}

buildscript {
    dependencies {
        classpath("com.squareup:javapoet:1.13.0")
    }
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val ktlintVersion = versionCatalog.findVersion("ktlint").get().requiredVersion

val dependencyAnalysisRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("dependency", ignoreCase = true) ||
        taskName.contains("buildHealth", ignoreCase = true)
}

val dependencyAnalysisEnabled = providers.gradleProperty("novapdf.enableDependencyAnalysis")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

if (dependencyAnalysisRequested || dependencyAnalysisEnabled.get()) {
    pluginManager.apply("com.autonomousapps.dependency-analysis")
}

subprojects {
    configurations.all {
        resolutionStrategy {
            force("com.squareup:javapoet:1.13.0")
        }
    }

    pluginManager.apply("org.jlleitschuh.gradle.ktlint")
    pluginManager.apply("com.diffplug.spotless")
    pluginManager.apply("io.gitlab.arturbosch.detekt")

    extensions.configure<KtlintExtension> {
        version.set(ktlintVersion)
        android.set(true)
        ignoreFailures.set(false)
        filter {
            include("**/src/main/**/*.kt")
            exclude("**/generated/**")
            exclude("**/build/**")
            exclude("**/*.kts")
        }
    }

    extensions.configure<SpotlessExtension> {
        val hasKotlinPlugin = pluginManager.hasPlugin("org.jetbrains.kotlin.android") ||
            pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") ||
            pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") ||
            pluginManager.hasPlugin("org.jetbrains.kotlin.kapt")

        if (hasKotlinPlugin) {
            kotlin {
                if (this@subprojects.name == "app") {
                    target("src/**/*.kt")
                    targetExclude("**/build/**/*.kt", "**/src/test/**/*.kt", "**/src/androidTest/**/*.kt")
                    ktlint(ktlintVersion)
                } else {
                    targetExclude("**/*.kt")
                }
            }
        }
    }

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom(files("${rootProject.projectDir}/config/detekt/detekt.yml"))
        basePath = projectDir.absolutePath
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
        exclude("**/build/**")
        reports {
            xml.required.set(false)
            html.required.set(false)
            txt.required.set(false)
            sarif.required.set(false)
        }
    }

    tasks.withType<KtLintCheckTask>().configureEach {
        onlyIf { !name.contains("AndroidTest") && !name.contains("KotlinScript") }
    }

    tasks.withType<KtLintFormatTask>().configureEach {
        onlyIf { !name.contains("AndroidTest") && !name.contains("KotlinScript") }
    }

    tasks.matching { task ->
        task.name.startsWith("ktlint") && (task.name.contains("AndroidTest") || task.name.contains("TestSourceSet") || task.name.contains("KotlinScript"))
    }.configureEach {
        enabled = false
    }

    if (name == "baselineprofile") {
        tasks.matching { it.name.startsWith("ktlint") }.configureEach {
            enabled = false
        }
    }

    tasks.matching { it.name == "check" }.configureEach {
        dependsOn("spotlessCheck", "detekt")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
