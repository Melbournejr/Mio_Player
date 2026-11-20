plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.google.devtools.ksp") }
android {
    namespace = "com.mio.player"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.mio.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.0.7"
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
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release { isMinifyEnabled = false; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    val media3 = "1.4.1"
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
    // DSD: FFmpeg decode to WAV cache (see data/audio/DsdSupport.kt).
    // Verified 2026-11-20 on Maven Central (HTTP 200):
    // dev.ffmpegkit-maintained:ffmpeg-kit-full:6.0.3 exists.
    // Do NOT use androidx.media3:media3-decoder-ffmpeg — Google does NOT
    // publish it to Google Maven or Maven Central (404 for every version,
    // including 1.4.1 — that was run #2's failure). Do NOT use
    // com.arthenica:ffmpeg-kit-* either — removed from Maven Central
    // in April 2025. The maintained fork below is the drop-in replacement
    // with the same com.arthenica.ffmpegkit API.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:6.0.3")
    // REQUIRED by ffmpeg-kit-full: its POM declares NO dependencies, so
    // the smart-exception library its Java API needs
    // (com.arthenica.smartexception.java.Exceptions) is never packaged.
    // DSD decode then crashed at runtime with NoClassDefFoundError
    // (proven by the 1.0.5 crash log + dex inspection of the shipped APK:
    // Exceptions is referenced but not defined in any classes*.dex).
    // 0.2.1 is the latest on Maven Central and contains Exceptions.class.
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
