// Step 3 (AAudio Exclusive) — native side, scaffold (v1.1.38).
//
// Proves the NDK/CMake toolchain builds in CI, the .so is packaged into
// the APK, and JNI loads. The full AAudio output engine (open a stream
// at the track's native rate, write PCM frames, close, report the
// stream's ACTUAL rate/sharing/performance mode) comes in the next
// version; this file only exposes a trivial availability probe.
//
// AAudio is a stable NDK API since API 26 (our minSdk) — no Oboe
// dependency (Oboe is NOT on Maven Central, all versions 404).

#include <jni.h>
#include <aaudio/AAudio.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mio_player_data_audio_AaudioProbe_isAaudioAvailable(JNIEnv*, jclass) {
    // If this .so loaded, the AAudio NDK library linked and the JNI
    // bridge works. AAudio itself is guaranteed present on API 26+.
    return JNI_TRUE;
}
