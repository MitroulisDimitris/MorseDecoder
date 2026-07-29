// :app — the Android shell. Everything decodable lives in :core; this module owns
// only the platform-bound pieces: AudioRecord, SAF/MediaCodec import, the foreground
// service, permissions, and (in M4) the full UI.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.morsedecoder.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.morsedecoder.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
}
