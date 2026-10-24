plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.wamior.tvcast"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.wamior.tvcast"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    // Public sideload-only key (committed on purpose) so every build is signed the same and updates install over each other.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = "tvairplay"
            keyAlias = "tvairplay"
            keyPassword = "tvairplay"
        }
    }

    buildFeatures {
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.googlecode.plist:dd-plist:1.28")
    implementation("com.android.ndk.thirdparty:openssl:1.1.1q-beta-1")
}
