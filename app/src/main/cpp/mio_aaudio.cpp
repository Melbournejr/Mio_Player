// Step 3 (AAudio Exclusive) — native AAudio output engine (v1.1.39).
//
// A thin JNI layer over the raw AAudio NDK API (stable since API 26, our
// minSdk). No Oboe dependency (Oboe is NOT on Maven Central, all versions
// 404). The Kotlin side (AaudioEngine) owns the stream lifecycle; this
// file only opens / writes / controls one output stream and reports its
// ACTUAL parameters. The Kotlin side decides whether to accept the stream
// (actual rate must equal the requested rate AND sharing must be
// EXCLUSIVE) or fall back to the standard AudioTrack mixer path.
//
// Stream model: blocking writes are NOT used — write() is non-blocking
// (timeout 0), matching Media3's AudioOutput.write contract (return
// false / retry when the buffer is full). Position is derived on the
// Kotlin side from AAudioStream_getFramesRead() plus the presentation
// timestamp of the first buffer written after open/flush.

#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>

#define LOG_TAG "MioAAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static AAudioStream* toStream(jlong handle) {
    return reinterpret_cast<AAudioStream*>(handle);
}

// Scaffold probe (v1.1.38, kept): proves the .so loads and JNI works.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mio_player_data_audio_AaudioProbe_isAaudioAvailable(JNIEnv*, jclass) {
    return JNI_TRUE;
}

// Opens an output stream at the requested rate (stereo, PCM 16-bit,
// EXCLUSIVE sharing, LOW_LATENCY performance). Returns the native handle,
// or 0 on failure. The caller MUST read back the actual parameters with
// the getters below and accept the stream only if the actual sample rate
// equals the requested rate and sharing is EXCLUSIVE — otherwise the
// stream is shared/resampled and the caller falls back to AudioTrack.
extern "C" JNIEXPORT jlong JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeOpenStream(
    JNIEnv*, jclass, jint sampleRate, jint channelCount) {
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || builder == nullptr) {
        LOGW("createStreamBuilder failed: %d", (int)result);
        return 0;
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);

    AAudioStream* stream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || stream == nullptr) {
        LOGW("openStream(%d Hz) failed: %d", (int)sampleRate, (int)result);
        return 0;
    }
    LOGI("openStream: requested %d Hz, actual %d Hz, sharing=%d, perf=%d, ch=%d, bufFrames=%d",
         (int)sampleRate, AAudioStream_getSampleRate(stream),
         (int)AAudioStream_getSharingMode(stream),
         (int)AAudioStream_getPerformanceMode(stream),
         AAudioStream_getChannelCount(stream),
         AAudioStream_getBufferSizeInFrames(stream));
    return reinterpret_cast<jlong>(stream);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeCloseStream(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream != nullptr) {
        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeStartStream(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return JNI_FALSE;
    return AAudioStream_requestStart(stream) == AAUDIO_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativePauseStream(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return JNI_FALSE;
    return AAudioStream_requestPause(stream) == AAUDIO_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeStopStream(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return JNI_FALSE;
    return AAudioStream_requestStop(stream) == AAUDIO_OK ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeFlushStream(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return JNI_FALSE;
    return AAudioStream_requestFlush(stream) == AAUDIO_OK ? JNI_TRUE : JNI_FALSE;
}

// Non-blocking write (timeout 0). `buffer` must be a direct ByteBuffer.
// Returns the number of bytes written, 0 when the stream buffer is full
// (caller retries), or a negative aaudio_result_t on error.
extern "C" JNIEXPORT jint JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeWrite(
    JNIEnv* env, jclass, jlong handle, jobject buffer, jint byteOffset, jint byteLength) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr || buffer == nullptr || byteLength <= 0) return 0;
    void* base = env->GetDirectBufferAddress(buffer);
    if (base == nullptr) return -1;
    const int32_t bytesPerFrame = AAudioStream_getChannelCount(stream) * 2; // PCM_I16
    const int32_t framesRequested = byteLength / bytesPerFrame;
    if (framesRequested <= 0) return 0;
    const char* data = static_cast<const char*>(base) + byteOffset;
    aaudio_result_t written = AAudioStream_write(stream, data, framesRequested, 0);
    if (written < 0) {
        LOGW("write failed: %d", (int)written);
        return (jint)written;
    }
    return (jint)(written * bytesPerFrame);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeGetFramesRead(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return 0;
    return (jlong)AAudioStream_getFramesRead(stream);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeGetFramesWritten(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return 0;
    return (jlong)AAudioStream_getFramesWritten(stream);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeGetSampleRate(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return 0;
    return AAudioStream_getSampleRate(stream);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeGetSharingMode(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return 0;
    return (jint)AAudioStream_getSharingMode(stream);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeGetPerformanceMode(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return 0;
    return (jint)AAudioStream_getPerformanceMode(stream);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeGetBufferSizeInFrames(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return 0;
    return AAudioStream_getBufferSizeInFrames(stream);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mio_player_data_audio_AaudioEngine_nativeGetChannelCount(
    JNIEnv*, jclass, jlong handle) {
    AAudioStream* stream = toStream(handle);
    if (stream == nullptr) return 0;
    return AAudioStream_getChannelCount(stream);
}
