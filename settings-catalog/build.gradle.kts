plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-JVM on purpose: keys are encoded as strings + enums with NO android dependency, so
// the safety-critical classification is unit-testable without an emulator.
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
