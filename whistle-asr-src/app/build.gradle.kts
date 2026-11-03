plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.qnnwhistle"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.qnnwhistle"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // Needle's android-arm64 engine only ships an arm64 static lib.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // FLIR first-party key — same key used across all earhart apps.
        // Loaded from ~/.gradle/keystore.properties (set up by the
        // 'Create Earhart Local Setup' CI workflow, or copy manually).
        // Overridable via Gradle project properties or env vars so this
        // file stays credential-free in version control.
        create("flir") {
            storeFile = file(
                project.findProperty("flir.keystore") as String?
                    ?: System.getenv("FLIR_KEYSTORE")
                    ?: "${System.getProperty("user.home")}/Documents/Earhart/android-earhart/keystores/apksign.jks"
            )
            storePassword = project.findProperty("flir.storePassword") as String?
                ?: System.getenv("FLIR_STORE_PASSWORD")
                ?: "dummypass"
            keyAlias = project.findProperty("flir.keyAlias") as String?
                ?: System.getenv("FLIR_KEY_ALIAS")
                ?: "firstparty"
            keyPassword = project.findProperty("flir.keyPassword") as String?
                ?: System.getenv("FLIR_KEY_PASSWORD")
                ?: "dummypass"
            // v3 only — matches FLIR earhart signing policy.
            enableV1Signing = false
            enableV2Signing = false
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("flir")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("flir")
        }
    }

    buildFeatures { viewBinding = true }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        // Keep model archives uncompressed so they can be mmap'd / loaded
        // directly after being copied to internal storage.
        noCompress += listOf("cact", "json", "onnx")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.1.12297006"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Moonshine v2 "streaming tiny" alternate engine runs as plain float32
    // ONNX Runtime CPU graphs (no QNN/HTP quantization) — see MoonshineV2Decoder.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
