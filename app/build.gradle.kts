import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Single source of truth for every pinned upstream version/URL (proot,
// Debian rootfs, code-server, apt snapshot pin, Node.js major version) --
// see scripts/versions.json for the values and rationale. This task turns
// it into a generated dev.codebian.app.RemoteAssets.kt, so Kotlin code and
// scripts/fetch-assets.{ps1,sh} (which read the same JSON directly) can
// never drift out of sync on a version bump.
val versionsJsonFile = rootProject.file("scripts/versions.json")
val generatedRemoteAssetsDir = layout.buildDirectory.dir("generated/source/remoteAssets/main")

val generateRemoteAssets by tasks.registering {
    inputs.file(versionsJsonFile)
    val outputDirProvider = generatedRemoteAssetsDir
    outputs.dir(outputDirProvider)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(versionsJsonFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val proot = json["proot"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val rootfs = json["debianRootfs"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val codeServer = json["codeServer"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val aptSnapshot = json["aptSnapshot"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val nodejs = json["nodejs"] as Map<String, Any?>

        val outFile = outputDirProvider.get().file("dev/codebian/app/RemoteAssets.kt").asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |// GENERATED FILE -- do not edit by hand.
            |// Regenerated at build time from scripts/versions.json by the
            |// :app:generateRemoteAssets Gradle task (see app/build.gradle.kts).
            |// Bump versions there -- it is the single source of truth shared
            |// with scripts/fetch-assets.ps1 and scripts/fetch-assets.sh.
            |package dev.codebian.app
            |
            |object RemoteAssets {
            |    const val PROOT_VERSION: String = "${proot["version"]}"
            |    const val PROOT_DEB_URL: String = "${proot["debUrl"]}"
            |
            |    const val DEBIAN_REPOSITORY: String = "${rootfs["repository"]}"
            |    const val DEBIAN_TAG: String = "${rootfs["tag"]}"
            |    const val DEBIAN_ARCH: String = "${rootfs["arch"]}"
            |    const val DEBIAN_VARIANT: String = "${rootfs["variant"]}"
            |
            |    const val CODE_SERVER_VERSION: String = "${codeServer["version"]}"
            |    const val CODE_SERVER_DEB_URL: String = "${codeServer["debUrl"]}"
            |    const val CODE_SERVER_DEB_SHA256: String = "${codeServer["sha256"]}"
            |
            |    const val APT_SNAPSHOT_TIMESTAMP: String = "${aptSnapshot["timestamp"]}"
            |    const val NODEJS_MAJOR_VERSION: String = "${nodejs["majorVersion"]}"
            |
            |    /**
            |     * Bundled rootfs asset filename under app/src/main/assets/, if
            |     * scripts/fetch-assets.* fetched one -- see
            |     * BootstrapService.obtainRootfsArchive(). Deliberately NOT named
            |     * "*.tar.gz": AGP's asset packaging silently gunzips any asset
            |     * ending in ".gz" and strips the extension while merging assets
            |     * (a build-time optimisation so an already-gzipped asset isn't
            |     * pointlessly re-deflated by the outer APK zip), which would
            |     * both break assets.open(ROOTFS_ASSET_NAME) (file not found under
            |     * that name at runtime) and invalidate the accompanying
            |     * ROOTFS_ASSET_NAME + ".sha256" digest (computed over the
            |     * original gzip bytes, not the silently-decompressed tar AGP
            |     * would actually ship). The ".tar.gz.packed" extension is inert
            |     * as far as AGP's asset pipeline is concerned, so the file
            |     * survives byte-for-byte into the APK.
            |     */
            |    const val ROOTFS_ASSET_NAME: String = "rootfs-${rootfs["tag"]}-${rootfs["arch"]}.tar.gz.packed"
            |
            |    /** Bundled code-server .deb asset filename under app/src/main/assets/, if scripts/fetch-assets.* fetched one -- see BootstrapService.installBundledCodeServer(). */
            |    const val CODE_SERVER_ASSET_NAME: String = "code-server-${codeServer["version"]}-arm64.deb"
            |}
            |
            """.trimMargin()
        )
    }
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

    signingConfigs {
        release {
            storeFile file(System.getenv("ANDROID_KEYSTORE_PATH"))
            storePassword System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias System.getenv("ANDROID_KEY_ALIAS")
            keyPassword System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    sourceSets {
        getByName("main") {
            // dev.codebian.app.RemoteAssets.kt is generated at build time by
            // :app:generateRemoteAssets from scripts/versions.json (see top
            // of this file) -- there is deliberately no hand-written copy
            // under src/main/java to avoid the two drifting apart.
            java.srcDir(generatedRemoteAssetsDir)
        }
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

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateRemoteAssets)
}
tasks.named("preBuild") {
    dependsOn(generateRemoteAssets)
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
