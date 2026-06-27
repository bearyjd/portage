plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigningValues = listOf(
    "PORTAGE_KEYSTORE_PATH",
    "PORTAGE_KEYSTORE_PASSWORD",
    "PORTAGE_KEY_ALIAS",
    "PORTAGE_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }
val hasPartialReleaseSigning = releaseSigningValues.values.any { !it.isNullOrBlank() } && !hasReleaseSigning
require(!hasPartialReleaseSigning) {
    "Release signing is partially configured; set all PORTAGE_KEYSTORE_* variables or none."
}

android {
    namespace = "com.ventouxlabs.portage.send"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ventouxlabs.portage.send"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = providers.gradleProperty("portageVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("portageVersionName").orElse("0.1.0-dev").get()
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("PORTAGE_KEYSTORE_PATH")!!)
                storePassword = releaseSigningValues.getValue("PORTAGE_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("PORTAGE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("PORTAGE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Distribution flavors mirror app-recv (ADR-003 flavor split) so send + recv version together and
    // the play store listing has a matching pair. app-send carries NO privilege deps (no :adb-bridge,
    // no :wizard), so both flavors compile the same src/main — there is no source-set split here.
    flavorDimensions += "distribution"
    productFlavors {
        create("degoogle") {
            dimension = "distribution"
            isDefault = true
        }
        create("play") {
            dimension = "distribution"
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
    implementation(libs.zxing.core) // QR generation (sender displays the trust anchor)
    implementation(libs.zxing.android.embedded) // BarcodeEncoder bitmap rendering (no GMS)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
