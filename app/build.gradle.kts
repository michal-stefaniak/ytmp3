plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

android {
    namespace = "com.ytmp3"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file("/home/pc-linux/Documents/ytmp3-release.jks")
            storePassword = "ytmp3release"
            keyAlias = "ytmp3"
            keyPassword = "ytmp3release"
        }
    }

    defaultConfig {
        applicationId = "com.ytmp3"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.13.11")
}

/** Guards the native runtime dependencies required by FFmpeg's extracted libraries. */
tasks.register("verifyDebugCxxRuntime") {
    dependsOn("assembleDebug")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        val requiredEntries = abis.map { "lib/$it/libc++_shared.so" } +
            abis.flatMap { abi ->
                listOf(
                    "libandroid-posix-semaphore.so",
                    "libandroid-support.so",
                    "libcrypto.so.3",
                    "libexpat.so.1"
                ).map { library -> "assets/ffmpeg-runtime/$abi/$library" }
            }
        ZipFile(apk).use { zip ->
            val missing = requiredEntries.filter { zip.getEntry(it) == null }
            check(missing.isEmpty()) {
                "Debug APK is missing FFmpeg native runtime libraries: ${missing.joinToString()}"
            }
        }
    }
}

/** Android 14+ requires WorkManager's foreground service to declare its service type. */
tasks.register("verifyDebugForegroundServiceType") {
    dependsOn("processDebugMainManifest")
    doLast {
        val manifest = layout.buildDirectory.file(
            "intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
        ).get().asFile
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val service = (0 until document.getElementsByTagName("service").length)
            .map { document.getElementsByTagName("service").item(it) as org.w3c.dom.Element }
            .firstOrNull {
                it.getAttributeNS(androidNamespace, "name") ==
                    "androidx.work.impl.foreground.SystemForegroundService"
            }
        check(service?.getAttributeNS(androidNamespace, "foregroundServiceType") == "dataSync") {
            "WorkManager SystemForegroundService must declare android:foregroundServiceType=\"dataSync\""
        }
        val permissions = (0 until document.getElementsByTagName("uses-permission").length)
            .map { document.getElementsByTagName("uses-permission").item(it) as org.w3c.dom.Element }
            .map { it.getAttributeNS(androidNamespace, "name") }
            .toSet()
        check("android.permission.FOREGROUND_SERVICE" in permissions) {
            "Missing android.permission.FOREGROUND_SERVICE"
        }
        check("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in permissions) {
            "Missing android.permission.FOREGROUND_SERVICE_DATA_SYNC"
        }
    }
}
