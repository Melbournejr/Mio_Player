package com.mio.player.data.audio
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** USB bit-perfect output via Android 14's preferred mixer attributes
 *  (v1.1.37 — step 2 of the hi-res plan).
 *
 *  The standard Android path mixes everything at 48 kHz. Android 14
 *  (API 34) lets an app ask the framework to run the mixer in
 *  BIT_PERFECT mode for a USB audio device: no mixing, no volume
 *  scaling, no effects — the data goes as-is to the HAL and down to
 *  the USB DAC. The API is USB-only ("Currently, only media usage
 *  over USB devices will be allowed"), so the internal DAC needs the
 *  AAudio exclusive path instead (step 3).
 *
 *  Contract (developer.android.com/media/platform/improve-audio-
 *  playback): the AudioTrack's format MUST match the mixer attributes
 *  format that was set. So we pick the attribute whose sample rate
 *  and encoding match the track being played (the track's real rate
 *  and bit depth come from the v1.1.36 scan — that is why step 1 was
 *  the foundation). Bit-perfect support itself is OPTIONAL in the HAL
 *  (vendors must add AUDIO_OUTPUT_FLAG_BIT_PERFECT), so we query
 *  getSupportedMixerAttributes() and only set it when a matching
 *  BIT_PERFECT entry exists; otherwise we fall back to the standard
 *  mixer and say so.
 *
 *  Bit-perfect means the signal is untouched, so while it is active
 *  the app bypasses every processor that would modify it: the
 *  Parametric EQ biquad, the Graphic AudioEffect chain, and
 *  ReplayGain (player.volume is forced to 1.0). The bypass is undone
 *  as soon as bit-perfect stops. The user chose this trade-off in the
 *  plan ("Hi-Res" keeps EQ at the native rate; "Bit-perfect" is
 *  untouched) — this build implements the Bit-perfect side for USB;
 *  the Hi-Res (EQ-kept) output is step 3's AAudio path.
 *
 *  The active state is reported through [state], driven by the
 *  framework's OnPreferredMixerAttributesChangedListener, so the UI
 *  shows what REALLY engaged, never a claim. */
@Suppress("NewApi")
class UsbBitPerfectManager(private val ctx: Context) {
    companion object {
        private const val TAG = "MioBitPerfect"
        val isApiSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        /** File bit depth -> AudioFormat encoding. 24-bit files are
         *  PCM_24BIT_PACKED (the FLAC decoder's native output); float
         *  is only for completeness, Mio decodes to integer PCM. */
        fun encodingForBitDepth(bitDepth: Int): Int = when (bitDepth) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            32 -> AudioFormat.ENCODING_PCM_32BIT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }
    }

    data class State(val active: Boolean, val sampleRateHz: Int = 0, val deviceName: String = "", val detail: String = "")
    private val _state = MutableStateFlow(State(false))
    val state: StateFlow<State> = _state.asStateFlow()

    private val audioManager: AudioManager? =
        ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mediaAttrs: AudioAttributes =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
    private var listenerRegistered = false
    private var lastDevice: AudioDeviceInfo? = null

    /** The connected USB audio device (USB DAC / USB headset), or null. */
    fun findUsbDevice(): AudioDeviceInfo? {
        val am = audioManager ?: return null
        return try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } catch (e: Exception) { Log.w(TAG, "getDevices failed", e); null }
    }

    /** BIT_PERFECT attribute for this device matching the track's
     *  sample rate and encoding, or null when the device / HAL does
     *  not offer one. Stereo is required (Mio plays stereo). */
    fun findBitPerfect(device: AudioDeviceInfo, sampleRateHz: Int, encoding: Int): AudioMixerAttributes? {
        if (!isApiSupported || sampleRateHz <= 0) return null
        return try {
            @Suppress("NewApi")
            val list = audioManager?.getSupportedMixerAttributes(device) ?: return null
            @Suppress("NewApi")
            list.firstOrNull { attr ->
                attr != null && attr.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    attr.format.sampleRate == sampleRateHz &&
                    attr.format.encoding == encoding &&
                    attr.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO
            }
        } catch (e: Exception) { Log.w(TAG, "getSupportedMixerAttributes failed", e); null }
    }

    /** Ask the framework to run this USB device bit-perfect with the
     *  given attributes. Returns true when the call was accepted (the
     *  listener confirms what actually engages). */
    fun setPreferred(device: AudioDeviceInfo, attrs: AudioMixerAttributes): Boolean {
        if (!isApiSupported) return false
        return try {
            @Suppress("NewApi")
            audioManager?.setPreferredMixerAttributes(mediaAttrs, device, attrs)
            lastDevice = device
            ensureListener()
            _state.value = State(true, attrs.format.sampleRate, device.productName?.toString() ?: "USB DAC", "Bit-perfect")
            Log.i(TAG, "setPreferredMixerAttributes BIT_PERFECT ${attrs.format.sampleRate}Hz on ${device.productName}")
            true
        } catch (e: Exception) { Log.w(TAG, "setPreferredMixerAttributes failed", e); false }
    }

    /** Release the preference (back to the standard mixer). */
    fun clearPreferred() {
        val device = lastDevice ?: return
        try {
            @Suppress("NewApi")
            audioManager?.clearPreferredMixerAttributes(mediaAttrs, device)
        } catch (e: Exception) { Log.w(TAG, "clearPreferredMixerAttributes failed", e) }
        lastDevice = null
        if (_state.value.active) _state.value = State(false)
    }

    /** The framework's own report of what is active — the source of
     *  truth for the UI. A null attributes value means bit-perfect is
     *  no longer active for this device. */
    private fun ensureListener() {
        if (listenerRegistered || !isApiSupported) return
        try {
            @Suppress("NewApi")
            audioManager?.addOnPreferredMixerAttributesChangedListener(
                Executors.newSingleThreadExecutor(),
                AudioManager.OnPreferredMixerAttributesChangedListener { _, device, mixerAttributes ->
                    val active = mixerAttributes != null &&
                        mixerAttributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                    _state.value = if (active) State(
                        true, mixerAttributes!!.format.sampleRate,
                        device.productName?.toString() ?: "USB DAC", "Bit-perfect"
                    ) else State(false)
                    Log.i(TAG, "mixer attributes changed: active=$active rate=${mixerAttributes?.format?.sampleRate}")
                }
            )
            listenerRegistered = true
        } catch (e: Exception) { Log.w(TAG, "addOnPreferredMixerAttributesChangedListener failed", e) }
    }
}
