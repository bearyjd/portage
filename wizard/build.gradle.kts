plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "cc.grepon.portage.wizard"
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
    // The wizard drives the privilege bootstrap THROUGH the bridge interface — it never touches
    // the ADB wire protocol itself (ADR-003 boundary rule).
    implementation(project(":adb-bridge"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
