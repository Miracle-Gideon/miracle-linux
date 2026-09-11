plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.miracle.linux"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.miracle.linux"
        minSdk = 26
        targetSdk = 28
        versionCode = 7
        versionName = "0.1.6-GhostByte-targetsdk28"

        // We only ship an arm64 proot binary right now (real tablets/phones).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // CRITICAL: proot is bundled as a "native library" (see jniLibs/arm64-v8a/libproot.so)
    // purely as a packaging trick so Android extracts it to a real, executable file path
    // (applicationInfo.nativeLibraryDir) instead of leaving it compressed inside the APK.
    // Without this flag, modern Android Gradle Plugin defaults to NOT extracting native
    // libs to disk, and our PRoot binary would have no real path to execute from.
    // rootfs.tar.gz is already gzip-compressed — trying to compress it again
    // during packaging wastes memory for zero size benefit (and was the
    // actual cause of the CI build running out of heap space).
    androidResources {
        noCompress += "rootfsblob"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
}
