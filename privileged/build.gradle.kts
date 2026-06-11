plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "cc.grepon.portage.privileged"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Keep rules for the reflectively-instantiated Shizuku UserService propagate to the app.
        consumerProguardFiles("consumer-rules.pro")
    }

    // The Shizuku UserService binder contract (IPrivilegedService.aidl).
    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Canonical modern toolchain selection; CI/dev provisions JDK 17.
kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Shizuku binder bridge — see docs/prp/ADR-001-privilege-feasibility.md.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
