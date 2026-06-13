plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cc.grepon.portage.adbbridge"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // The self-contained ADB stack (pairing + TLS connect) — ADR-003. NO other module may
    // depend on these; AdbBridge is the only privileged entry point.
    implementation(libs.libadb.android)
    // Public-API Conscrypt for the pairing EKM export AND the TLS connect path; without it
    // libadb-android falls back to a hidden platform Conscrypt class (ADR-003 §risks).
    implementation(libs.conscrypt.android)
    // Self-signed X.509 for the ADB identity (AdbKeyStore); bcprov rides in transitively.
    implementation(libs.bouncycastle.bcpkix)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
