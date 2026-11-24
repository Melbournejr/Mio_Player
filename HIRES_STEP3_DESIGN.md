# Step 3 — AAudio Exclusive for the internal DAC (design note, 2026-11-24)

Goal: output at the file's native rate (up to 192 kHz) through the phone's
internal DAC, bypassing the 48 kHz mixer. The mixer-attributes API (step 2)
is USB-only, so the internal DAC needs the AAudio path.

## Confirmed approach (researched 2026-11-24)

- **Media3 hook**: `DefaultAudioSink.Builder.setAudioOutputProvider(AudioOutputProvider)`
  (Media3 1.9, replaces the deprecated `setAudioTrackProvider`). Supply a
  custom `AudioOutputProvider` that returns an AAudio-backed `AudioOutput`
  when hi-res mode is on, else the default `AudioTrackAudioOutputProvider`.
  The sink keeps handling the processor chain, timing and position
  smoothing; only the final output device is swapped.
- **No Oboe dependency**: Oboe is NOT on Maven Central (`com.google.oboe:oboe`
  1.6.1/1.7.0/1.8.0 all 404). Use the **raw AAudio NDK API**
  (`<aaudio/AAudio.h>`, stable since API 26, in the NDK) — no extra
  dependency, no GitHub fetch in CI.
- **Stream config**: `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY` +
  `AAUDIO_SHARING_MODE_EXCLUSIVE` (MMAP, writes straight to the DSP
  buffer). Request the track's native sample rate (96/192 kHz) and the
  matching format. **Read back what the stream actually reports**
  (sample rate, sharing mode, performance mode) after opening — the
  request is not always granted (Google's low-latency guide: the native
  rate is "almost always 48000 Hz"; a non-native rate may fall back to a
  shared/resampled path). Display the REAL values, never the request.
- **Fallback**: if the AAudio stream can't open at the requested rate, or
  exclusive is refused, fall back to the default AudioTrack sink
  (standard mixer) and say so in the UI.

## Build changes needed

- `app/build.gradle.kts`: `ndkVersion = "26.3.11579264"` (NDK r26d, the
  exact version the ffmpeg workflow uses — `26.3.115792071` does NOT exist
  and makes sdkmanager fail the whole release job, proven 2026-11-24) +
  `externalNativeBuild { cmake { ... } }`, `minSdk 26` already satisfies
  AAudio (API 26+).
- `.github/workflows/release.yml`: add `"ndk;26.3.11579264"` +
  `"cmake;3.22.1"` to the sdkmanager install list (the ffmpeg workflow
  already proves NDK r26d works on this runner).
- `app/src/main/cpp/CMakeLists.txt` + `mio_aaudio.cpp` (JNI): open stream
  at rate/format, write PCM frames, close, report actual rate/mode.
- Kotlin: `AaudioOutputProvider` / `AaudioAudioOutput` implementing
  `androidx.media3.exoplayer.audio.AudioOutput` (UnstableApi), wired in
  `PlayerManager.buildAudioSink` via `setAudioOutputProvider` when the
  hi-res mode is on.
- Settings: a "Hi-Res (AAudio)" toggle + status display (actual rate,
  exclusive/shared, fallback reason). EQ: the "Hi-Res" mode keeps EQ at
  the native rate (unlike Bit-perfect, which bypasses it) — the EQ runs
  in the processor chain before the sink, so it still applies.

## Risks / unknowns (device testing required)

- Whether the Xiaomi 14 Pro's HAL grants an exclusive MMAP stream at
  96/192 kHz (vs. only at the native 48 kHz). If it refuses, the
  fallback is the mixer and step 3 cannot help that device — the user
  must know this is device-dependent.
- The `AudioOutput` position/timing contract: Media3 uses the output's
  position for playback position. Getting `getCurrentPositionUs` wrong
  breaks seek/progress. Must mirror `AudioTrackAudioOutput`'s approach
  (frames written + stream timestamp).
- NDK build in CI is the first gate: scaffold (CMake + a trivial JNI
  that reports AAudio availability) and verify it builds before writing
  the full AudioOutput.

## Sequence

1. v1.1.37 (step 2) must be verified first — do not push step 3 on top
   of an unverified build.
2. Scaffold v1.1.38: NDK/CMake + trivial JNI, verify CI builds.
3. Full AudioOutput + integration + UI, verify CI builds, deliver for
   device testing with the actual-rate display.
