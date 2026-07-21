plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.codebian.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.codebian.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // proot must ship as arm64-v8a only for now (Samsung One UI 8.5/9 devices
    // are all arm64). Renamed to lib*.so under jniLibs so the PackageManager
    // extracts it into applicationInfo.nativeLibraryDir at install time --
    // that directory is the one guaranteed to be mounted exec on Android
    // 10+ (W^X / SELinux block exec from regular app-private files dirs).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // DocumentFile wraps SAF tree/single-document Uris (ACTION_OPEN_DOCUMENT_TREE
    // / ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT) with a File-like API --
    // used by SafActions for the folder-workspace sync and single-file
    // import/export quick actions, without ever requesting
    // MANAGE_EXTERNAL_STORAGE (see SafActions KDoc).
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Pure-Java tar + xz extraction for the Debian rootfs archive (no native
    // dependency needed, keeps the bootstrap self-contained).
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
}
