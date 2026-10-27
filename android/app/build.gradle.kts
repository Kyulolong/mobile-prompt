import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Upload-key signing. keystore.properties is git-ignored; see android/README.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.teaminpact.mobileprompt"
    // Compose 1.12 (BOM 2026.09) compiles against API 37; targetSdk stays at 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.teaminpact.mobileprompt"
        minSdk = 29
        targetSdk = 36
        versionCode = 2 // 1 was burned by the first 1.0.0 upload to Play
        // Kept in step with iOS (1.3.0) so both stores ship the same feature set under one number.
        versionName = "1.3.0"
        // 64-bit only: the Korean speech model alone is 120 MB in memory,
        // which 32-bit-era devices cannot run acceptably. x86_64 is for the
        // emulator and is split out of per-device downloads by Play anyway.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Speech models (~170 MB) ship as an install-time asset pack so the base
    // module stays far under Play's 200 MB cap. See :speechmodels.
    assetPacks += listOf(":speechmodels")

    bundle {
        // The app has an in-app language picker (ko/en). With language splits,
        // a phone set to English only ever receives the English strings and
        // the picker silently does nothing. Two tiny string tables: ship both.
        language {
            enableSplit = false
        }
    }

    signingConfigs {
        create("upload") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("upload")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // On-device streaming speech recognition (k2-fsa/sherpa-onnx, Apache-2.0).
    implementation(files("libs/sherpa-onnx-2.0.0.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.core:core-ktx:1.19.1")

    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar's is a stub).
    testImplementation("org.json:json:20260814")
}
