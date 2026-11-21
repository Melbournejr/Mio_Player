# media3-ffmpeg-decoder-dsd (vendored)

Vendored copy of Media3's `decoder_ffmpeg` module, patched for native DSD
playback. Google does **not** publish
`androidx.media3:media3-decoder-ffmpeg` to Google Maven / Maven Central
(404 for every version, including 1.9.0), so the module is vendored instead
of depending on the artifact.

## Source

- Upstream: https://github.com/androidx/media — **tag `1.9.0`**
  (Media3 moved out of `google/ExoPlayer`; there is no `r1.9.0` tag there —
  the ExoPlayer repo's tags stop at the 2.x line. Fetch from `androidx/media`.)
- Path upstream:
  `libraries/decoder_ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/`
- Files copied verbatim (byte-identical to upstream 1.9.0), same package
  `androidx.media3.decoder.ffmpeg` so `DefaultRenderersFactory` finds
  `FfmpegAudioRenderer` by reflection (`Class.forName(...)`):
  - `ExperimentalFfmpegVideoRenderer.java`
  - `FfmpegAudioDecoder.java`
  - `FfmpegAudioRenderer.java`
  - `FfmpegDecoderException.java`
  - `FfmpegLibrary.java` (**patched — see below**)
  - `package-info.java`
- `src/main/AndroidManifest.xml` is also copied verbatim from upstream
  (package `androidx.media3.decoder.ffmpeg`, no components).
- The JNI C sources are **not** vendored here. Upstream they live at
  `libraries/decoder_ffmpeg/src/main/jni/` (`ffmpeg_jni.cc`,
  `CMakeLists.txt`, `build_ffmpeg.sh`). The native library is built once by
  the separate FFmpeg build task and committed as a prebuilt binary at
  `src/main/jniLibs/arm64-v8a/libffmpegJNI.so`
  (`FfmpegLibrary` loads it via `System.loadLibrary("ffmpegJNI")`).
  Until that `.so` lands, `FfmpegLibrary.isAvailable()` returns false and
  the extension renderer reports itself unsupported — the app keeps working
  through its normal renderers / FFmpegKit transcode fallback.

## Patch applied (Mio Player, DSD)

In `FfmpegLibrary.getCodecName()` (verified against the 1.9.0 source:
`/* package */ static String getCodecName(String mimeType)`), added after
the `AUDIO_ALAC` case:

```java
case "audio/dsd":
  return "dsd_lsbf_planar";
case "audio/x-dsd-dff":
  return "dsd_lsbf_planar";
```

- `audio/dsd` → `dsd_lsbf_planar`: the mica-music patch
  (https://github.com/lecoix/mica-music, branch `exoplayer-only`,
  Apache 2.0, file
  `third_party/media3-ffmpeg-decoder/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegLibrary.java`,
  verified by diffing it against stock 1.9.0 — mica's only change in this
  file is this case plus an `audio/ape` case, which was **not** taken).
  DSF stores one block per channel, matching FFmpeg's planar decoder.
- `audio/x-dsd-dff` → `dsd_lsbf_planar` (v1.1.5, changed from `dsd_msbf`):
  DFF stores its bytes channel-interleaved, MSB-first. FFmpeg's own
  demuxer (libavformat/dsfdec.c) pairs DSDIFF with a *planar* decoder and
  de-interleaves in the demuxer; the raw-interleaved `dsd_msbf` mapping
  was never exercised by any proven reference and every real DSD packet
  returned "no output" through it (v1.1.2–v1.1.4). `DsdExtractor` now
  does the demuxer's job — de-interleave (all left bytes, then all
  right bytes) + bit-reverse every byte — so DFF arrives as planar LSBF
  and both containers share the one mica-proven decoder. The transform
  is exact (a permutation plus a per-byte bit reversal), so it cannot
  change the audio, only the layout. DST-compressed `.dff` is out of
  scope.
- These MIME strings must match `DsdExtractor.MIME_TYPE_DSF` /
  `MIME_TYPE_DFF` in `:app` (`"audio/dsd"` / `"audio/x-dsd-dff"`).

The FFmpeg build must enable the decoders `dsd_lsbf`, `dsd_msbf`,
`dsd_lsbf_planar`, `dsd_msbf_planar` or `FfmpegLibrary.supportsFormat()`
will log "No ... decoder available" and refuse the format at runtime.
(`dsd_msbf` / `dsd_msbf_planar` are no longer *used* by the app after
v1.1.5, but the build still enables all four so a future mapping change
never silently loses a decoder.)

## Diagnostics (v1.1.5)

- `FfmpegAudioDecoder.DsdDecodeListener` (set by `PlayerManager`): reports
  decoder init and the raw `ffmpegDecode` result of the first 8 DSD packets
  per decoder instance. SimpleDecoder swallows 0 / -1 results, so without
  this the log could never say whether the decoder rejected packets or
  just never emitted.
- `DsdDecodeProbe` (same package): feeds one synthetic DSD-silence packet
  through a fresh `FfmpegAudioDecoder` at startup and reports the raw
  decode result — the experiment that settles "decoder vs extractor vs
  sink". See its KDoc for how to read the result.
- `FfmpegLibrary.hasDecoderByName(String)`: public probe for one FFmpeg
  codec name (e.g. `"dsd_lsbf_planar"`).

## Build notes

- `compileSdk = 35`: Media3 1.9.0 AARs declare `minCompileSdk=35`
  (verified in each AAR's `aar-metadata.properties`); AGP rejects a
  consumer with a lower `compileSdk`. `:app` was bumped to 35 as well.
- Media3 1.9.0's POMs were verified HTTP 200 on Google Maven before the
  bump (media3-exoplayer/-session/-common/-ui/-decoder, plus
  -datasource/-extractor/-container/-effect).
- Media3 1.9.0 is built with Kotlin 2.0.20 (its POM depends on
  kotlin-stdlib 2.0.20) while this repo uses Kotlin 1.9.24. If the CI
  build fails with a Kotlin metadata-version error, bump the Kotlin
  plugin (and KSP / Compose compiler) to 2.0.20+ — see the vendoring
  report for details. Not changed here.
