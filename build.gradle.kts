// Root build script. Plugins are declared here with `apply false` and applied per-module.
// AGP 9.x provides built-in Kotlin for Android modules (the standalone kotlin-android plugin is
// gone). Pin the Kotlin Gradle plugin on the buildscript classpath so EVERY module — including
// Android modules that apply no Kotlin plugin alias (adb-bridge, wizard) — compiles with the same
// Kotlin. Keep this in sync with `kotlin` in the version catalog.
buildscript {
    dependencies {
        // SYNC: keep this version equal to `kotlin` in gradle/libs.versions.toml.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
