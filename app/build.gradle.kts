plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release version comes from the git tag (vX.Y.Z) via -PappVersion; X.Y.Z → X*10000+Y*100+Z.
val appVersion = (findProperty("appVersion") as String?)?.removePrefix("v") ?: "0.0.1"
// A branch build in CI shows its commit hash instead, via -PappVersionName.
val appVersionName = findProperty("appVersionName") as String? ?: appVersion
val appVersionCode = appVersion.split(".").map { it.toInt() }.let { (x, y, z) -> x * 10000 + y * 100 + z }

android {
    namespace = "dev.glowcow.mnt"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    // The DjVu reader is DjVuLibre, built from app/src/main/cpp.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.glowcow.mnt"
        minSdk = 36
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // One APK per ABI: arm64-v8a for phones, x86_64 for the emulator.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            val store = System.getenv("ANDROID_KEYSTORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("ANDROID_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        compose = true
    }

    // Lists the bundled languages in the system per-app language menu.
    androidResources {
        generateLocaleConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)

    implementation(libs.datastore.preferences)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // The network places: SMB, FTP and NFS; S3 is spoken by the app itself.
    implementation(libs.smbj)
    implementation(libs.commons.net)
    implementation(libs.nfs.client)

    // The viewer: Markdown, and PDF through the system's sandboxed renderer.
    implementation(libs.exifinterface)
    implementation(libs.androidsvg)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tasks)
    implementation(libs.commonmark.autolink)
    implementation(libs.pdf.compose)
    implementation(libs.pdf.document.service)

    // The player of sound, kept going by a media session.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // The subtitles of a video, drawn as the player lays them out.
    implementation(libs.media3.ui)
    // A frame of a video of a server, read by the player's extractors round it.
    implementation(libs.media3.inspector)
    // FFmpeg's decoders, for what a phone's own cannot play (ALAC on Qualcomm's).
    implementation(libs.media3.ffmpeg)

    testImplementation(libs.junit)
    // The XML parser Android carries, for the tests of the document readers on the JVM.
    testImplementation(libs.kxml2)
}
