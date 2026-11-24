package com.mio.player.data.prefs
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
val Context.dataStore by preferencesDataStore("mio_settings")
class SettingsStore(private val ctx: Context) {
    companion object {
        val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        val KEY_GENRES = stringSetPreferencesKey("genres")
        val KEY_USER_NAME = stringPreferencesKey("user_name")
        val KEY_GAPLESS = booleanPreferencesKey("gapless")
        val KEY_CROSSFADE_MS = intPreferencesKey("crossfade_ms")
        val KEY_REPLAYGAIN = stringPreferencesKey("replaygain") // off/track/album
        val KEY_EQ_PRESET = stringPreferencesKey("eq_preset")
        val KEY_EQ_BANDS = stringPreferencesKey("eq_bands") // csv dB -15..15
        val KEY_EQ_MODE = stringPreferencesKey("eq_mode") // v1.1.32: "graphic" (default) | "parametric"
        val KEY_EQ_PARAMETRIC = stringPreferencesKey("eq_parametric") // v1.1.32: "PEAK,31,0.0,1.41;..." (see ParametricEqDefaults.encode)
        val KEY_PREAMP = floatPreferencesKey("preamp_db")
        val KEY_HEADPHONE_MODEL = stringPreferencesKey("headphone_model") // v1.1.13: active headphone-correction model, "" = none
        val KEY_CUSTOM_PRESETS = stringPreferencesKey("eq_custom_presets") // v1.1.13: JSON {"name": [10 band dB]} — bands only, no preamp
        val KEY_BASS = intPreferencesKey("bass_boost") // 0..1000
        val KEY_VIRTUALIZER = intPreferencesKey("virtualizer") // 0..1000
        val KEY_LOUDNESS = intPreferencesKey("loudness_db") // 0..? target mB
        val KEY_BALANCE = floatPreferencesKey("balance") // -1 left .. +1 right
        val KEY_SPEED = floatPreferencesKey("speed")
        val KEY_SHUFFLE = booleanPreferencesKey("shuffle")
        val KEY_REPEAT = intPreferencesKey("repeat_mode") // Player.REPEAT_MODE_*
        val KEY_SLEEP_MIN = intPreferencesKey("sleep_min")
        val KEY_FOLDERS = stringSetPreferencesKey("music_folders")
        val KEY_FOLLOWED_ARTISTS = stringSetPreferencesKey("followed_artists")
        val KEY_AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_ms")
        val KEY_THEME = stringPreferencesKey("theme") // deep_wave | sunset | system
        val KEY_AUTO_METADATA = booleanPreferencesKey("auto_metadata") // v1.1.10: auto-fetch missing artist/album/cover
        val KEY_BIT_PERFECT_USB = booleanPreferencesKey("bit_perfect_usb") // v1.1.37: Android 14+ mixer BIT_PERFECT for a USB DAC; EQ/ReplayGain bypassed while active
        val KEY_HIRES_AAUDIO = booleanPreferencesKey("hires_aaudio") // v1.1.39: AAudio exclusive (MMAP) output at the file's native rate for the internal DAC; only Parametric EQ applies (no AudioTrack session for the Graphic/AudioEffect path)
    }
    val onboarded: Flow<Boolean> = ctx.dataStore.data.map { it[KEY_ONBOARDED] ?: false }
    val eqPreset: Flow<String> = ctx.dataStore.data.map { it[KEY_EQ_PRESET] ?: "Flat" }
    /** Theme mode from the design system: Deep Wave is the default on
     *  first install; Sunset is the light alternative; "system" follows
     *  the device dark/light setting. */
    val themeMode: Flow<String> = ctx.dataStore.data.map { it[KEY_THEME] ?: "deep_wave" }
    suspend fun setThemeMode(v: String) { ctx.dataStore.edit { it[KEY_THEME] = v } }
    suspend fun setOnboarded(v: Boolean) { ctx.dataStore.edit { it[KEY_ONBOARDED]=v } }
    suspend fun <T> set(key: Preferences.Key<T>, value: T) { ctx.dataStore.edit { it[key]=value } }
    fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> = ctx.dataStore.data.map { it[key] ?: default }
}
