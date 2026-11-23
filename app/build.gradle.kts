plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Builds are signed with one fixed sideload key so `adb install -r` updates work. The key is never
// committed: CI decodes it from the SIGNING_KEYSTORE_B64 secret into keystore/explorer.jks.
// Pass -PsigningStore=... (or SIGNING_STORE) to point at another file.
val signingFile = rootProject.file(
    (findProperty("signingStore") as String?) ?: System.getenv("SIGNING_STORE") ?: "keystore/explorer.jks"
)
val signingPass = (findProperty("signingPassword") as String?) ?: System.getenv("SIGNING_PASSWORD") ?: "explorer-sideload"
val signingAlias = (findProperty("signingAlias") as String?) ?: System.getenv("SIGNING_ALIAS") ?: "explorer"

android {
    namespace = "dev.hamza.explorer"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hamza.explorer"
        minSdk = 30 // The Chinese OPPO Watch X2 runs ColorOS Watch on Android 11 (API 30).
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.3"
    }

    signingConfigs {
        if (signingFile.exists()) {
            create("sideload") {
                storeFile = signingFile
                storePassword = signingPass
                keyAlias = signingAlias
                keyPassword = signingPass
            }
        }
    }

    buildTypes {
        val key = signingConfigs.findByName("sideload")
        debug {
            if (key != null) signingConfig = key
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the sideload key, fall back to the local debug key (fine for a test build,
            // but it won't install over a build signed with the real key).
            signingConfig = key ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // Fail the release build on any API newer than the watch's Android 11 (lintVital checks fatal issues).
    lint { fatal += "NewApi" }

    packaging {
        resources {
            excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation(project(":filecore"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear.compose:compose-navigation:1.4.0")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
