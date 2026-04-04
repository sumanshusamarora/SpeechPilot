plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.speechpilot.transcription"
    compileSdk = 35

    // Pin NDK version for reproducible native builds.
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a: all 64-bit Android devices (2017+).
        // x86_64: emulator builds for development convenience.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // Whisper.cpp JNI bridge.
    // Sources are in src/main/cpp/; whisper.cpp itself is fetched from GitHub
    // by CMake's FetchContent on first build — no manual setup required.
    externalNativeBuild {
        cmake {
            path = "src/main/cpp/CMakeLists.txt"
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":audio"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Vosk on-device STT — primary transcription backend.
    // vosk-android AAR bundles JNI native libs; jna is its required companion.
    implementation("com.alphacephei:vosk-android:${libs.versions.vosk.get()}@aar")
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
