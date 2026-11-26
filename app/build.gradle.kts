plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.devtools.ksp") }
android {
    namespace = "com.mio.player"
    // compileSdk 35 is REQUIRED by Media3 1.9.0: its AARs declare
    // minCompileSdk=35 (targetSdk stays 34).
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mio.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 62
        versionName = "1.1.49"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        // Fixed debug keystore committed in the repo (debug.keystore,
        // standard debug credentials: android / androiddebugkey).
        // GitHub runners generate a fresh random debug key on every run,
        // so without this each release has a different signature and OTA
        // updates fail with a signature mismatch. A debug keystore is
        // not a secret, so committing it is the standard fix.
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }
    // Step 3 (AAudio Exclusive, v1.1.38 scaffold): NDK r26d, the same
    // version the ffmpeg workflow uses to build libffmpegJNI.so
    // (26.3.11579264 — NOT 115792071, which sdkmanager cannot find).
    // The CMake version is the one r26d ships. externalNativeBuild wires
    // app/src/main/cpp/CMakeLists.txt (mio_aaudio .so, raw AAudio NDK
    // API — no Oboe dependency, Oboe is NOT on Maven Central).
    ndkVersion = "26.3.11579264"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // v1.1.38 Step 3: adding NDK/CMake to the app module enabled
        // :app:stripDebugDebugSymbols, which strips the PREBUILT
        // libffmpegJNI.so (5,970,720 B -> 1,489,784 B) and breaks the
        // release gate's SHA-256 check (59245f9c... -> 919a023d...).
        // keepDebugSymbols exempts it so it reaches the APK
        // byte-identical. (AGP 8.0.2+, ours is 8.5.2.)
        jniLibs { keepDebugSymbols += "**/libffmpegJNI.so" }
    }
}
dependencies {
    val media3 = "1.9.0"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.ui:ui:1.7.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.2")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.2")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // v1.1.20 — legacy androidx.media (NOT media3): the media
    // notification's building blocks live here and nowhere else —
    // PlaybackStateCompat + MediaButtonReceiver.buildMediaButtonPendingIntent
    // (androidx.media.session) and NotificationCompat.MediaStyle +
    // MediaSessionCompat.Token (androidx.media.app / androidx.media.session).
    // Media3's own MediaButtonReceiver has no buildMediaButtonPendingIntent,
    // and androidx.core.app.NotificationCompat has no MediaStyle — the
    // 1.1.20 first push failed compile on exactly those three references.
    // Version 1.7.0 (Nov 2023, developer.android.com release notes) is
    // the stable release Media3 1.9 pairs with; it is deprecated in
    // favour of media3 but still functional, and only used for the
    // notification — playback stays on Media3.
    implementation("androidx.media:media:1.7.0")
    // Native DSD: the vendored Media3 FFmpeg decoder module
    // (third_party/media3-ffmpeg-decoder-dsd) — Google does NOT publish
    // androidx.media3:media3-decoder-ffmpeg to Maven, so the 1.9.0 module
    // sources are vendored with a DSD patch (see its README.md) and the
    // native libffmpegJNI.so is committed there as a prebuilt binary.
    // DefaultRenderersFactory finds FfmpegAudioRenderer by reflection when
    // EXTENSION_RENDERER_MODE_PREFER is set (see PlayerManager).
    implementation(project(":third_party:media3-ffmpeg-decoder-dsd"))
    // DSD: FFmpeg decode to WAV cache (see data/audio/DsdSupport.kt).
    // Verified 2026-11-20 on Maven Central (HTTP 200 for both POM and AAR):
    // dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9 is the LATEST
    // (FFmpeg 8.1, SDK 35, 16 KB page-size). The 6.0.3 used before was
    // simply the newest when the app was first built — nobody checked
    // for a newer release since. The fork's API is a drop-in replacement
    // for the old com.arthenica.ffmpegkit API, so no code changes needed.
    // Do NOT add androidx.media3:media3-decoder-ffmpeg as a Maven
    // dependency — Google does NOT publish it to Google Maven or Maven
    // Central (404 for every version, including 1.4.1 — that was run #2's
    // failure). The decoder is vendored instead, as a source module at
    // third_party/media3-ffmpeg-decoder-dsd (see its README.md). Do NOT use
    // com.arthenica:ffmpeg-kit-* either — removed from Maven Central
    // in April 2025.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")
    // smart-exception is now declared by ffmpeg-kit-full 8.1.9's own POM
    // (it was missing in 6.0.3's POM — that gap crashed DSD decode with
    // NoClassDefFoundError in 1.0.5). Kept explicit as a safety net: same
    // version 0.2.1, so it can never drift out of the APK again.
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.2")
}
