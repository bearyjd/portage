plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "cc.grepon.portage.transport"
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
    implementation(project(":core-model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.cbor)
    // Noise handshake uses VENDORED noise-java (src/main/java/com/southernstorm/noise),
    // pinned at commit 49377b6, MIT. Pattern: NoisePSK_XX (ADR-002). No external dep.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
