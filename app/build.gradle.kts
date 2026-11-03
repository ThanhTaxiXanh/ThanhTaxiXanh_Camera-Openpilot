plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "vn.thanhtaxixanh.camera.ai"
    compileSdk = 36
    defaultConfig {
        applicationId = "vn.thanhtaxixanh.camera.ai"
        minSdk = 29
        targetSdk = 35
        versionCode = 22
        versionName = "1.0.2-playback-lite"
        ndk {
            // Modern A500S Android test device: one CPU architecture only.
            abiFilters += "arm64-v8a"
        }
    }
    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
dependencies {
    // Public LGPL IJKPlayer build. 0.0.26 is NOT proven identical to the
    // library inside the official 70mai 4.5.0 APK.
    implementation("io.github.jdpxiaoming:ijkplayer-java:0.0.26")
    implementation("io.github.jdpxiaoming:ijkplayer-arm64:0.0.26")
    testImplementation("junit:junit:4.13.2")
}
