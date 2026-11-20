// Vendored Media3 decoder_ffmpeg module (see README.md in this directory).
// The Maven artifact androidx.media3:media3-decoder-ffmpeg is NOT published by
// Google, so the module's Java sources are vendored here and the native
// libffmpegJNI.so is committed separately as a prebuilt binary.
plugins { id("com.android.library") }

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    // Media3 1.9.0 AARs declare minCompileSdk=35 — this module (and :app)
    // must use compileSdk 35 or AGP fails the build.
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        ndk {
            // The prebuilt .so is arm64-v8a only (see README.md).
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No CMake/NDK build in this module: the native library is built once
    // (separate FFmpeg build task) and committed at src/main/jniLibs/.
    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
        }
    }
}

dependencies {
    // Mirrors upstream libraries/decoder_ffmpeg/build.gradle (Media3 1.9.0),
    // with the in-tree project deps replaced by the published artifacts:
    //   lib-decoder   -> media3-decoder
    //   lib-exoplayer -> media3-exoplayer
    api("androidx.media3:media3-decoder:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-common:1.9.0")
    implementation("androidx.annotation:annotation:1.6.0")
    // FfmpegAudioRenderer uses com.google.common.base.Preconditions.
    // (Also a transitive compile dep of media3-common 1.9.0, declared here
    // explicitly so the module compiles even if that changes.)
    implementation("com.google.guava:guava:33.3.1-android")
    compileOnly("org.checkerframework:checker-qual:3.42.0")
}
