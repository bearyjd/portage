plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pin the toolchain so a stray JDK-21 `java` on PATH can't compile classes the rest of the
// build (jvmToolchain(17) everywhere else) then rejects with UnsupportedClassVersionError.
kotlin { jvmToolchain(17) }

// Pure-JVM on purpose: keys are encoded as strings + enums with NO android dependency, so
// the safety-critical classification is unit-testable without an emulator.
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
