/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.decoder.ffmpeg;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;

/**
 * Mio Player v1.1.5 — startup probe for the native DSD path.
 *
 * <p>Runs one synthetic DSD packet through the exact same {@link FfmpegAudioDecoder} the
 * player uses (same JNI, same codec, same output encoding) and reports what ffmpegDecode
 * actually returned. This is the experiment that settles the v1.1.2–v1.1.4 mystery — every
 * real DSD packet returned "no output" and SimpleDecoder swallowed the result, so no log
 * ever said whether the decoder was handed INVALIDDATA (-1), was returning EAGAIN-forever
 * (0), or was producing output the sink dropped. The probe answers it with a packet whose
 * bytes are known good (DSD silence, 0x69, transformed exactly as DsdExtractor transforms
 * a real DFF packet):
 *
 * <ul>
 *   <li>{@code decodeResult > 0} and an output buffer arrived: the decoder works; any
 *       real-playback failure is in the extractor's packets or downstream of the decoder.
 *   <li>{@code decodeResult == 0}, no output buffer: the decoder accepts the packet but
 *       emits nothing (receive-side stall — e.g. the JNI never drains a frame).
 *   <li>{@code decodeResult == -1}, no output buffer: the decoder rejects the packet
 *       (send-side INVALIDDATA — wrong codec, wrong layout, bad init).
 *   <li>{@code error != null}: decoder init failed outright (avcodec_open2 refused the
 *       codec — e.g. the shipped .so lacks the createContext patch) or a hard decode
 *       error (-2).
 * </ul>
 *
 * <p>Must be called off the main thread: decoder init plus the output poll can block for
 * up to ~1 s. The probe creates and releases its own decoder instance, so it never
 * touches the player's renderer or sink.
 */
public final class DsdDecodeProbe {

  /** One probe run. All fields are plain values so the app can log them verbatim. */
  public static final class ProbeResult {
    /** MIME type the probe was run with ("audio/dsd" or "audio/x-dsd-dff"). */
    public final String mimeType;
    /** FFmpeg codec the MIME type resolved to (e.g. "dsd_lsbf_planar"). */
    public final @Nullable String codecName;
    /** Synthetic packet size handed to the decoder, in bytes. */
    public final int inputBytes;
    /**
     * Raw ffmpegDecode return: bytes of PCM written (> 0), 0 (no output), -1
     * (invalid data), -2 (hard error), or {@link Integer#MIN_VALUE} if decode never
     * ran (init failed or no input buffer).
     */
    public final int decodeResult;
    /** PCM bytes in the dequeued output buffer (0 unless an output buffer arrived). */
    public final int outputBytes;
    /** True when an output buffer was dequeued (i.e. the decoder really emitted). */
    public final boolean gotOutputBuffer;
    /** Decoder-reported output sample rate (0 until the first output frame). */
    public final int decoderSampleRate;
    /** Decoder-reported output channel count (0 until the first output frame). */
    public final int decoderChannelCount;
    /** Non-null when the probe could not run or the decoder failed (init / -2). */
    public final @Nullable String error;

    private ProbeResult(
        String mimeType,
        @Nullable String codecName,
        int inputBytes,
        int decodeResult,
        int outputBytes,
        boolean gotOutputBuffer,
        int decoderSampleRate,
        int decoderChannelCount,
        @Nullable String error) {
      this.mimeType = mimeType;
      this.codecName = codecName;
      this.inputBytes = inputBytes;
      this.decodeResult = decodeResult;
      this.outputBytes = outputBytes;
      this.gotOutputBuffer = gotOutputBuffer;
      this.decoderSampleRate = decoderSampleRate;
      this.decoderChannelCount = decoderChannelCount;
      this.error = error;
    }

    /** One log line carrying the whole verdict (the app logs this verbatim). */
    public String toLogLine() {
      return "DSD probe "
          + mimeType
          + ": codec="
          + codecName
          + " in="
          + inputBytes
          + "B decodeResult="
          + decodeResult
          + " out="
          + outputBytes
          + "B gotOutput="
          + gotOutputBuffer
          + " decSr="
          + decoderSampleRate
          + " decCh="
          + decoderChannelCount
          + (error != null ? " ERROR=" + error : "");
    }
  }

  private static final long OUTPUT_POLL_TIMEOUT_MS = 1000;
  private static final long OUTPUT_POLL_INTERVAL_MS = 10;

  private DsdDecodeProbe() {}

  /**
   * Feeds one synthetic packet to a fresh decoder for {@code mimeType}.
   *
   * @param mimeType "audio/dsd" (DSF) or "audio/x-dsd-dff" (DFF); the codec is resolved
   *     exactly as the player resolves it.
   * @param packet the packet bytes, already in the layout the decoder expects (for DFF:
   *     de-interleaved + bit-reversed by the caller, exactly as DsdExtractor delivers).
   * @param sampleRate the PCM output rate the extractor reports for this stream
   *     (DSD rate / 8, e.g. 705600 for DSD128).
   * @param channelCount channel count of the stream.
   * @param outputFloat false: the production int16 path (mica config); true is only for
   *     diagnosis and is not what the player uses.
   */
  public static ProbeResult probe(
      String mimeType, byte[] packet, int sampleRate, int channelCount, boolean outputFloat) {
    if (!FfmpegLibrary.isAvailable()) {
      return new ProbeResult(
          mimeType, null, packet.length, Integer.MIN_VALUE, 0, false, 0, 0,
          "ffmpeg library not available (libffmpegJNI.so did not load)");
    }
    @Nullable String codecName = FfmpegLibrary.getCodecName(mimeType);
    if (codecName == null) {
      return new ProbeResult(
          mimeType, null, packet.length, Integer.MIN_VALUE, 0, false, 0, 0,
          "no codec mapped for MIME type");
    }
    Format format =
        new Format.Builder()
            .setSampleMimeType(mimeType)
            .setSampleRate(sampleRate)
            .setChannelCount(channelCount)
            .setMaxInputSize(packet.length)
            .build();
    FfmpegAudioDecoder decoder = null;
    try {
      decoder = new FfmpegAudioDecoder(format, 2, 2, packet.length, outputFloat);
    } catch (FfmpegDecoderException e) {
      return new ProbeResult(
          mimeType, codecName, packet.length, Integer.MIN_VALUE, 0, false, 0, 0,
          "decoder init failed: " + e.getMessage());
    }
    try {
      DecoderInputBuffer inputBuffer = decoder.dequeueInputBuffer();
      if (inputBuffer == null) {
        return new ProbeResult(
            mimeType, codecName, packet.length, Integer.MIN_VALUE, 0, false, 0, 0,
            "no input buffer available");
      }
      inputBuffer.clear();
      inputBuffer.ensureSpaceForWrite(packet.length);
      inputBuffer.data.put(packet);
      inputBuffer.flip();
      inputBuffer.timeUs = 0;
      decoder.queueInputBuffer(inputBuffer);
      // SimpleDecoder decodes on queue; poll briefly in case the decode lands on
      // its own thread instead of inline. A positive result always queues an
      // output buffer, so keep polling while the result is still unknown or
      // positive; a 0 / -1 result queues nothing, so stop at once.
      boolean gotOutput = false;
      int outputBytes = 0;
      long deadlineMs = System.currentTimeMillis() + OUTPUT_POLL_TIMEOUT_MS;
      while (System.currentTimeMillis() < deadlineMs) {
        SimpleDecoderOutputBuffer outputBuffer = decoder.dequeueOutputBuffer();
        if (outputBuffer != null) {
          gotOutput = true;
          outputBytes = outputBuffer.data != null ? outputBuffer.data.remaining() : 0;
          decoder.releaseProbeOutputBuffer(outputBuffer);
          break;
        }
        int lastResult = decoder.getLastDecodeResult();
        if (lastResult != Integer.MIN_VALUE && lastResult <= 0) {
          break;
        }
        try {
          Thread.sleep(OUTPUT_POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      return new ProbeResult(
          mimeType,
          codecName,
          packet.length,
          decoder.getLastDecodeResult(),
          outputBytes,
          gotOutput,
          decoder.getSampleRate(),
          decoder.getChannelCount(),
          null);
    } catch (FfmpegDecoderException e) {
      // Hard decode error (-2 surfaces here): the raw result is recorded too.
      return new ProbeResult(
          mimeType,
          codecName,
          packet.length,
          decoder.getLastDecodeResult(),
          0,
          false,
          decoder.getSampleRate(),
          decoder.getChannelCount(),
          "decode failed: " + e.getMessage());
    } finally {
      if (decoder != null) {
        decoder.release();
      }
    }
  }
}
