package com.mio.player.ui.visualizer

import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * v1.1.5 — the four visualizer styles the user can choose between on
 * Now Playing. All four are driven ONLY by real audio data from
 * [com.mio.player.data.audio.WaveformTap] (FFT band magnitudes for
 * Bars / Circular / Mirror, the raw mono PCM frame for Wave); there is
 * no time-based animation and no progress colouring in any style.
 *
 * Persistence uses the app's existing mechanism — DataStore via
 * [com.mio.player.data.prefs.SettingsStore]'s generic `set` / `flow`
 * — with the key defined here, so no change to SettingsStore itself
 * was needed. The stored value is the style's [id]; an unknown or
 * missing value falls back to [BARS].
 */
enum class VisualizerStyle(val id: String, val label: String) {
    /** The current Now Playing wave (LiveWaveform): 32 vertical bars. */
    BARS("bars", "Cột"),

    /** Oscilloscope line from the tap's raw mono PCM frame. */
    WAVE("wave", "Sóng"),

    /** The 32 FFT bands arranged as bars radiating from a circle. */
    CIRCULAR("circular", "Tròn"),

    /** Spectrum bars mirrored symmetrically above / below a centre line. */
    MIRROR("mirror", "Gương");

    /** Next style in picker order, wrapping around (tap-to-cycle). */
    fun next(): VisualizerStyle {
        val all = values()
        return all[(ordinal + 1) % all.size]
    }

    companion object {
        /** DataStore key (same "mio_settings" store as every other setting). */
        val KEY = stringPreferencesKey("visualizer_style")

        fun fromId(id: String?): VisualizerStyle =
            values().firstOrNull { it.id == id } ?: BARS
    }
}
