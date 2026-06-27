plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ventouxlabs.portage.recv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ventouxlabs.portage.recv"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // TODO: enable R8 + serialization keep rules before any release build.
            isMinifyEnabled = false
        }
    }

    // Distribution flavors (ADR-003 flavor split): degoogle ships full Tier-1 (the self-contained ADB
    // bridge); play ships Tier-0-only with NEITHER :adb-bridge NOR :wizard compiled in (a Google Play
    // policy surface — those modules bundle libadb / spake2 / conscrypt). The exclusion is a
    // compile-time source-set split, not a runtime no-op: see degoogleImplementation below.
    flavorDimensions += "distribution"
    productFlavors {
        create("degoogle") {
            dimension = "distribution"
            isDefault = true
            // Keeps the base applicationId (com.ventouxlabs.portage.recv).
        }
        create("play") {
            dimension = "distribution"
            // Distinct id so degoogle + play can coexist (com.ventouxlabs.portage.recv.play).
            applicationIdSuffix = ".play"
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core-model"))
    implementation(project(":core-transport"))
    implementation(project(":providers"))
    // degoogle ONLY (Tier-1): the self-contained ADB bridge (ADR-003) + the privilege bootstrap state
    // machine. degoogleImplementation keeps both — and their transitive libadb/spake2/conscrypt native
    // libs — OUT of the play binary at COMPILE time. src/main holds no :adb-bridge/:wizard type, so it
    // compiles for play; src/degoogle supplies the real privilege integration.
    "degoogleImplementation"(project(":adb-bridge"))
    "degoogleImplementation"(project(":wizard"))
    implementation(project(":settings-catalog"))  // safety-critical allowlist

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose) // collectAsStateWithLifecycle
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // QR scanning (receiver) via zxing-android-embedded — no GMS, no ML Kit.
    implementation(libs.zxing.android.embedded)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
