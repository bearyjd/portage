plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    // Noise handshake: VERIFY_FIRST #8 — wire a vetted Noise (XXpsk3) lib here, or fall
    // back to NNpsk0. See docs/prp/PROTOCOL.md §2. Crypto stays inside this module.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
