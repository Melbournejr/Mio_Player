package com.mio.player.data.audio

import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.1.13 — single source of truth for EQ presets and headphone corrections.
 * AudioEffectsManager.presets references [BUILT_IN]; the EQ screen reads
 * [HEADPHONE_PROFILES] and the import/export helpers from here.
 *
 * Preset file format (import / export), JSON:
 * ```
 * {"name": string, "bands": [10 numbers, dB, -15..15], "preamp": number (optional, dB, -12..6, default 0)}
 * ```
 * Bands are 31 / 62 / 125 / 250 / 500 / 1000 / 2000 / 4000 / 8000 / 16000 Hz,
 * in that order. Any file that fails validation (blank name, wrong band
 * count, a band outside -15..15, a preamp outside -12..6) is rejected
 * whole — nothing is applied.
 *
 * Custom presets persist in SettingsStore.KEY_CUSTOM_PRESETS as a JSON
 * object {"name": [10 band dB]} — bands only. Preamp is applied on import
 * but is NOT stored with a custom preset, so reopening an imported custom
 * preset restores its bands with whatever preamp is currently set.
 */
object EqPresets {

    /** v1.1.13 — full 22-preset Evermusic table. Bands are
     *  31/62/125/250/500/1k/2k/4k/8k/16k Hz (the table's 32/64 Hz columns
     *  map to the 31/62 Hz bands this device exposes). The old invented
     *  8 presets (Bass / Treble / Vocal / Lo-Fi among them) are gone. */
    val BUILT_IN: Map<String, FloatArray> = mapOf(
        "Acoustic" to floatArrayOf(5.0f, 4.9f, 4.0f, 1.1f, 2.2f, 1.8f, 3.5f, 4.1f, 3.6f, 2.2f),
        "Bass Booster" to floatArrayOf(5.5f, 4.3f, 3.5f, 2.5f, 1.3f, 0f, 0f, 0f, 0f, 0f),
        "Bass Reducer" to floatArrayOf(-5.5f, -4.3f, -3.5f, -2.5f, -1.3f, 0f, 0f, 0f, 0f, 0f),
        "Classical" to floatArrayOf(4.8f, 3.8f, 3.0f, 2.5f, -1.5f, -1.5f, 0f, 2.3f, 3.3f, 3.8f),
        "Dance" to floatArrayOf(3.6f, 6.6f, 5.0f, 0f, 1.9f, 3.7f, 5.2f, 4.5f, 3.6f, 0f),
        "Deep" to floatArrayOf(5.0f, 3.6f, 1.8f, 1.0f, 2.9f, 2.5f, 1.5f, -2.2f, -3.6f, -4.6f),
        "Electronic" to floatArrayOf(4.3f, 3.8f, 1.2f, 0f, -2.2f, 2.3f, 0.9f, 1.3f, 4.0f, 4.8f),
        "Flat" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "Hip-Hop" to floatArrayOf(5.0f, 4.3f, 1.5f, 3.0f, -1.0f, -1.0f, 1.5f, -0.5f, 2.0f, 3.0f),
        "Jazz" to floatArrayOf(4.0f, 3.0f, 1.5f, 2.3f, -1.5f, -1.5f, 0f, 1.5f, 3.0f, 3.8f),
        "Latin" to floatArrayOf(4.5f, 3.0f, 0f, 0f, -1.5f, -1.5f, -1.5f, 0f, 3.0f, 4.5f),
        "Loudness" to floatArrayOf(6.0f, 4.0f, 0f, 0f, -2.0f, 0f, -1.0f, -5.0f, 5.0f, 1.0f),
        "Lounge" to floatArrayOf(-3.0f, -1.5f, -0.5f, 1.5f, 4.0f, 2.5f, 0f, -1.5f, 2.0f, 1.0f),
        "Piano" to floatArrayOf(3.0f, 2.0f, 0f, 2.5f, 3.0f, 1.5f, 3.5f, 4.5f, 3.0f, 3.5f),
        "Pop" to floatArrayOf(-1.5f, -1.0f, 0f, 2.0f, 4.0f, 4.0f, 2.0f, 0f, -1.0f, -1.5f),
        "R&B" to floatArrayOf(2.6f, 6.9f, 5.7f, 1.3f, -2.2f, -1.5f, 2.3f, 2.7f, 3.0f, 3.8f),
        "Rock" to floatArrayOf(5.0f, 4.0f, 3.0f, 1.5f, -0.5f, -1.0f, 0.5f, 2.5f, 3.5f, 4.5f),
        "Small Speakers" to floatArrayOf(5.5f, 4.3f, 3.5f, 2.5f, 1.3f, 0f, -1.3f, -2.5f, -3.5f, -4.3f),
        "Spoken Word" to floatArrayOf(-3.5f, -0.5f, 0f, 0.7f, 3.5f, 4.6f, 4.8f, 4.3f, 2.5f, 0f),
        "Treble Booster" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 1.3f, 2.5f, 3.5f, 4.3f, 5.5f),
        "Treble Reducer" to floatArrayOf(0f, 0f, 0f, 0f, 0f, -1.3f, -2.5f, -3.5f, -4.3f, -5.5f),
        "Vocal Booster" to floatArrayOf(-1.5f, -3.0f, -3.0f, 1.5f, 3.8f, 3.8f, 3.0f, 1.5f, 0f, -1.5f)
    )

    /** One headphone-correction profile: the preamp to apply with it plus
     *  the 10 fixed-band corrections. Values are AutoEq 10-band fixed-band
     *  corrections (Q 1.41) from oratory1990 measurements toward the Harman
     *  over-ear 2018 target. A fixed 10-band EQ cannot reproduce the full
     *  parametric oratory1990 preset, so these are the best 10-band
     *  approximation, not the full correction. */
    data class HeadphoneProfile(val preampDb: Float, val bands: FloatArray)

    /** v1.1.13 — 10 bundled headphone models, values copied from AutoEq's
     *  FixedBandEQ.txt files (Q 1.41, bands 31/62/125/250/500/1k/2k/4k/8k/16k). */
    val HEADPHONE_PROFILES: Map<String, HeadphoneProfile> = mapOf(
        "AKG K371" to HeadphoneProfile(-4.8f, floatArrayOf(-3.0f, 1.2f, -1.4f, -1.8f, 0.8f, -0.8f, -0.3f, 3.0f, -0.2f, 4.8f)),
        "Audio-Technica ATH-AD500X" to HeadphoneProfile(-7.5f, floatArrayOf(7.2f, 2.5f, -2.3f, -3.1f, -0.7f, -3.9f, 2.1f, 0.9f, 6.9f, -4.7f)),
        "Audio-Technica ATH-M50x" to HeadphoneProfile(-2.3f, floatArrayOf(0.0f, 0.4f, -5.3f, 0.7f, 1.9f, -1.0f, 0.6f, 0.4f, 2.8f, -9.3f)),
        "Sennheiser HD 600" to HeadphoneProfile(-7.5f, floatArrayOf(6.9f, 3.3f, -1.1f, -1.6f, 0.6f, -0.8f, 0.1f, -1.0f, 3.9f, -6.5f)),
        "Sennheiser HD 650" to HeadphoneProfile(-7.4f, floatArrayOf(7.0f, 2.6f, -1.2f, -2.2f, 0.5f, -1.0f, 0.7f, -1.2f, 5.1f, -3.6f)),
        "Sennheiser HD 58X" to HeadphoneProfile(-6.9f, floatArrayOf(6.6f, 2.0f, -1.2f, -2.4f, 0.4f, -1.8f, 1.2f, -0.3f, 4.0f, -1.0f)),
        "Sony WH-1000XM4" to HeadphoneProfile(-5.8f, floatArrayOf(-4.3f, -1.8f, -5.8f, -1.4f, 0.5f, -0.6f, 6.0f, -0.8f, 1.0f, -2.2f)),
        "Beyerdynamic DT 990 Pro" to HeadphoneProfile(-6.1f, floatArrayOf(6.0f, 0.6f, -1.8f, -1.3f, 3.4f, 1.0f, 0.9f, 0.3f, -5.4f, -12.0f)),
        "Beyerdynamic DT 770 Pro" to HeadphoneProfile(-2.5f, floatArrayOf(-4.1f, -1.4f, -1.8f, 2.6f, -0.4f, -0.5f, 0.1f, 3.2f, -4.1f, -10.2f)),
        "HIFIMAN Arya Stealth" to HeadphoneProfile(-5.6f, floatArrayOf(5.2f, 2.0f, -0.5f, -2.1f, 0.0f, -0.4f, 4.9f, -3.9f, 1.1f, -6.9f))
    )

    /** A validated imported preset. Null means the file is not a valid
     *  preset — the caller must show an error and apply nothing. */
    data class ParsedPreset(val name: String, val bands: FloatArray, val preampDb: Float)

    /** Parse + validate a preset file. Returns null on any problem:
     *  not JSON, blank name, bands missing / not exactly 10 numbers, a
     *  band outside -15..15, or a preamp outside -12..6. Never throws. */
    fun parsePresetJson(text: String): ParsedPreset? {
        return try {
            val obj = JSONObject(text)
            val name = obj.optString("name", "").trim()
            if (name.isBlank()) return null
            val arr = obj.optJSONArray("bands") ?: return null
            if (arr.length() != 10) return null
            val bands = FloatArray(10)
            for (i in 0 until 10) {
                val v = arr.optDouble(i, Double.NaN)
                if (v.isNaN() || v < -15.0 || v > 15.0) return null
                bands[i] = v.toFloat()
            }
            var preamp = 0f
            if (obj.has("preamp")) {
                val p = obj.optDouble("preamp", Double.NaN)
                if (p.isNaN() || p < -12.0 || p > 6.0) return null
                preamp = p.toFloat()
            }
            ParsedPreset(name, bands, preamp)
        } catch (_: Exception) {
            null
        }
    }

    /** Build a preset file's JSON: {"name", "bands": [10], "preamp"}. */
    fun buildPresetJson(name: String, bands: FloatArray, preampDb: Float): String {
        val arr = JSONArray()
        for (i in 0 until minOf(bands.size, 10)) arr.put(bands[i].toDouble())
        return JSONObject().put("name", name).put("bands", arr).put("preamp", preampDb.toDouble()).toString()
    }

    /** Parse the stored custom presets ({"name": [10 floats]}). Invalid
     *  entries are skipped, never crash the load. Returns empty on blank
     *  or malformed input. */
    fun parseCustomPresets(json: String): Map<String, FloatArray> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = LinkedHashMap<String, FloatArray>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val arr = obj.optJSONArray(name) ?: continue
                if (arr.length() != 10) continue
                val bands = FloatArray(10)
                var ok = true
                for (i in 0 until 10) {
                    val v = arr.optDouble(i, Double.NaN)
                    if (v.isNaN() || v < -15.0 || v > 15.0) { ok = false; break }
                    bands[i] = v.toFloat()
                }
                if (ok) out[name] = bands
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Serialize custom presets for storage (bands only — see class KDoc). */
    fun buildCustomPresetsJson(presets: Map<String, FloatArray>): String {
        val obj = JSONObject()
        for ((name, bands) in presets) {
            val arr = JSONArray()
            for (i in 0 until minOf(bands.size, 10)) arr.put(bands[i].toDouble())
            obj.put(name, arr)
        }
        return obj.toString()
    }

    /** File-safe export name: keep letters, digits, space, _ and -;
     *  everything else becomes _. Blank input falls back to "preset". */
    fun safeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9À-ỹ _-]"), "_").replace(" ", "_")
        return cleaned.take(60).ifBlank { "preset" }
    }
}
