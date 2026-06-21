plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pin the toolchain so a stray JDK-21 `java` on PATH can't compile classes the rest of the
// build (jvmToolchain(17) everywhere else) then rejects with UnsupportedClassVersionError.
kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.cbor)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
