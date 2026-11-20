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
        val KEY_PREAMP = floatPreferencesKey("preamp_db")
        val KEY_BASS = intPreferencesKey("bass_boost") // 0..1000
        val KEY_VIRTUALIZER = intPreferencesKey("virtualizer") // 0..1000
        val KEY_LOUDNESS = intPreferencesKey("loudness_db") // 0..? target mB
        val KEY_BALANCE = floatPreferencesKey("balance") // -1 left .. +1 right
        val KEY_SPEED = floatPreferencesKey("speed")
        val KEY_SHUFFLE = booleanPreferencesKey("shuffle")
        val KEY_REPEAT = intPreferencesKey("repeat_mode") // Player.REPEAT_MODE_*
        val KEY_SLEEP_MIN = intPreferencesKey("sleep_min")
        val KEY_FOLDERS = stringSetPreferencesKey("music_folders")
        val KEY_AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_ms")
    }
    val onboarded: Flow<Boolean> = ctx.dataStore.data.map { it[KEY_ONBOARDED] ?: false }
    val eqPreset: Flow<String> = ctx.dataStore.data.map { it[KEY_EQ_PRESET] ?: "Flat" }
    suspend fun setOnboarded(v: Boolean) { ctx.dataStore.edit { it[KEY_ONBOARDED]=v } }
    suspend fun <T> set(key: Preferences.Key<T>, value: T) { ctx.dataStore.edit { it[key]=value } }
    fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> = ctx.dataStore.data.map { it[key] ?: default }
}
