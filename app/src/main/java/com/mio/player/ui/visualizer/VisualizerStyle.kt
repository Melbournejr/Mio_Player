package com.mio.player.ui.visualizer

import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * v1.1.5 — the five visualizer styles the user can choose between on
 * Now Playing (a fifth, Droplet, was added in v1.1.7 and removed in
 * v1.1.8 at the user's request — "bỏ cái giọt nước đi vì xấu quá";
 * Ferrofluid took the fifth slot in v1.1.12).
 * All five are driven ONLY by real audio data from
 * [com.mio.player.data.audio.WaveformTap] (FFT band magnitudes for
 * Bars / Circular / Mirror / Ferrofluid, the raw mono PCM frame for
 * Wave); there is no time-based animation and no progress colouring
 * in any style.
 *
 * Persistence uses the app's existing mechanism — DataStore via
 * [com.mio.player.data.prefs.SettingsStore]'s generic `set` / `flow`
 * — with the key defined here, so no change to SettingsStore itself
 * was needed. The stored value is the style's [id]; an unknown or
 * missing value falls back to [BARS] — so a stored "droplet" id from
 * v1.1.7 resolves to Bars, never crashes and never shows a blank
 * visualizer.
 */
enum class VisualizerStyle(val id: String, val label: String) {
    /** The current Now Playing wave (native Canvas bars with peak caps): 32 vertical bars. */
    BARS("bars", "Cột"),

    /** Oscilloscope line from the tap's raw mono PCM frame. */
    WAVE("wave", "Sóng"),

    /** The 32 FFT bands arranged as bars radiating from a circle. */
    CIRCULAR("circular", "Tròn"),

    /** Spectrum bars mirrored symmetrically above / below a centre line. */
    MIRROR("mirror", "Gương"),

    /** Ferrofluid (magnetic liquid): dark glossy columns with sharp
     *  spikes that rise and dance with the FFT levels, inspired by a
     *  ferrofluid speaker. Added in v1.1.12. */
    FERROFLUID("ferrofluid", "Chất lỏng từ tính");

    /** Next style in picker order, wrapping around (tap-to-cycle). */
    fun next(): VisualizerStyle {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }

    /** Previous style in picker order, wrapping around (swipe right). */
    fun previous(): VisualizerStyle {
        val all = values()
        return all[(ordinal - 1 + all.size) % all.size]
    }

    companion object {
        /** DataStore key (same "mio_settings" store as every other setting). */
        val KEY = stringPreferencesKey("visualizer_style")

        /**
         * v1.1.13 — DataStore key for the Ferrofluid frequency range
         * (see [FerrofluidFreq]). New key, so nothing stored before
         * changes meaning; a missing value falls back to [FerrofluidFreq.ALL].
         */
        val KEY_FERROFLUID_FREQ = stringPreferencesKey("ferrofluid_freq")

        fun fromId(id: String?): VisualizerStyle =
            values().firstOrNull { it.id == id } ?: BARS
    }
}

/**
 * v1.1.13 — which frequency range the Ferrofluid spikes follow
 * (Dakd Jung's dial, as a segmented control — shown only while
 * Ferrofluid is the active style, see VisualizerPanel). Bass =
 * low bands only (0..11, ~20–220 Hz), Tất cả = the full spectrum,
 * Treble = high bands only (23..31, ~2–12 kHz). The centre tower
 * always follows bass energy, whatever the range. Persisted under
 * [VisualizerStyle.KEY_FERROFLUID_FREQ] ("ferrofluid_freq"); an
 * unknown or missing value falls back to [ALL].
 */
enum class FerrofluidFreq(val id: String, val label: String) {
    /** Low bands only (0..11, ~20–220 Hz). */
    BASS("bass", "Bass"),

    /** The full spectrum (0..31). The default. */
    ALL("all", "Tất cả"),

    /** High bands only (23..31, ~2–12 kHz). */
    TREBLE("treble", "Treble");

    companion object {
        fun fromId(id: String?): FerrofluidFreq =
            values().firstOrNull { it.id == id } ?: ALL
    }
}
