plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val verifiedAndroidRuntime = providers.gradleProperty("verifiedAndroidRuntime").orNull?.let { file(it) }

android {
    namespace = "com.clashofmusic.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clashofmusic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.1.0"

        testInstrumentationRunner = if (providers.gradleProperty("optimizedDownloadProbe").orNull == "true")
            "com.clashofmusic.app.data.downloader.OptimizedDownloadInstrumentation"
        else "com.clashofmusic.app.data.downloader.DownloadSmokeInstrumentation"

    }

    splits {
        abi {
            isEnable = true
            reset()
            if (verifiedAndroidRuntime == null) {
                include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            } else {
                include("arm64-v8a")
            }
            isUniversalApk = false
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
        debug {
            isMinifyEnabled = false
        }
        // Daily-use local APK: same identity/signature, with release optimizations.
        // Keep debug available for diagnosis; never use a new applicationId (library data).
        create("optimized") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")

    // AndroidX & Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // OkHttp & Brotli
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-brotli:4.12.0")

    // NewPipeExtractor
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")

    if (verifiedAndroidRuntime == null) {
        implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
        implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
        implementation("io.github.junkfood02.youtubedl-android:aria2c:0.18.1")
    } else {
        val library = verifiedAndroidRuntime.resolve("library.aar")
        val ffmpeg = verifiedAndroidRuntime.resolve("ffmpeg.aar")
        check(library.isFile && ffmpeg.isFile) {
            "Generate library.aar and ffmpeg.aar with scripts/package-android-arm64-runtime.py"
        }
        implementation(files(library, ffmpeg))
        implementation("io.github.junkfood02.youtubedl-android:common:0.18.1")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.11.1")
        implementation("commons-io:commons-io:2.5")
    }

    // Media3 ExoPlayer & MediaSession
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media:media:1.7.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Real JSON implementation for JVM tests; the app uses Android's built-in org.json.
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
