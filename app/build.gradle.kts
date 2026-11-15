plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.sensitune"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.example.sensitune"
        minSdk = 26
        targetSdk = 34
        // Bump versionCode on every AIDL/native change: Shizuku only recreates the
        // shell UserService when the version changes (otherwise the old process
        // with the old ISensiService transaction order keeps running).
        versionCode = 4
        versionName = "1.0.3"

        ndk {
            // arm64-v8a is the primary target. armeabi-v7a is built as well.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fstack-protector-strong", "-fexceptions")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // MUST keep native libs extracted on disk: the Shizuku UserService runs in
            // the shell process and loads them with System.load(nativeLibraryDir/...).
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Shizuku is the only third-party dependency, as required.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
