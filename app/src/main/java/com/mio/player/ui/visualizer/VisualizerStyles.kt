package com.mio.player.ui.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.mio.player.ui.components.AlbumArt
import com.mio.player.ui.theme.LocalMioColors
import com.mio.player.ui.theme.mioGradientBackground
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.maxOf
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v1.1.5 — the five visualizer styles, each a self-contained
 * @Composable driven ONLY by real audio data:
 *  - Bars / Circular / Mirror / Ferrofluid read `levels` (the 32
 *    log-spaced FFT band magnitudes, 20 Hz–12 kHz, from WaveformTap).
 *  - Wave reads `pcmFrame` (the newest 1024 mono PCM samples, also from
 *    WaveformTap, published with each FFT frame at ~60 Hz, v1.1.18 —
 *    was ~30 Hz, v1.1.6).
 * Nothing here depends on time, playback position, duration or
 * progress. When there is no live signal (paused / silence) every
 * style sits still at a low, fixed, index-determined shape.
 *
 * v1.1.8 — Droplet removed entirely at the user's request ("bỏ cái
 * giọt nước đi vì xấu quá"); its composable, teardrop path helper and
 * KDoc are gone, no dead code left behind. Wave was reworked in the
 * same release: the v1.1.7 screenshot showed it as a thin, nearly
 * flat line, so it now gets a signed perceptual lift (gain 2.5 +
 * sqrt) and a thicker 3 dp stroke — see its KDoc for the math.
 *
 * v1.1.12 — Ferrofluid added as the fifth style (see
 * [VisualizerFerrofluid]); the chip picker is gone — styles are now
 * switched by tapping / swiping the visualizer itself (see
 * VisualizerStylePicker.kt / [VisualizerPanel]). Row height reduced
 * 112 dp -> 88 dp so the lyrics area on Now Playing gains 24 dp.
 *
 * v1.1.14 — the demo page ("Visualizer & EQ Presets") stages the
 * visualizers on a ~300 dp canvas, but the app squeezed all five
 * styles into one 88 dp row — that height gap is why the app never
 * looked like the demo. Ferrofluid / Circular now get a taller
 * 140 dp row (see [VIS_ROW_TALL_DP]); Bars / Wave / Mirror keep the
 * 88 dp row so the lyrics area loses as little as possible (~1 fewer
 * visible lyric line only while Ferrofluid / Circular are active).
 * Ferrofluid's blob is brightened (it was near-black on a near-black
 * background — invisible), its spikes are fewer, thicker and drawn
 * with their own brighter brush, and the style-name pill moves out
 * of the centre (it covered Circular's art disc) to the top-left.
 * Bars / Mirror peak caps are now 3 dp white with a bigger gap;
 * Wave gets a thicker line, stronger glow and a stronger lift.
 * Every style is reworked in this release, each with its own
 * "v1.1.13 — ..." note: Bars gets Monstercat-style peak caps,
 * Wave gets a soft glow, Circular rings the album art, Mirror gets
 * caps + a bright centre line + a vertically mirrored gradient, and
 * Ferrofluid is redrawn as one glossy liquid blob (the v1.1.12
 * columns read as cheap thin bars — the user called it "phèn") with
 * a frequency selector (Bass / Tất cả / Treble, see
 * [FerrofluidFreq]).
 *
 * v1.1.16 — THOROUGH REDO, measured from the user's 22.9 s test
 * video ("...Baby One More Time", MP3) + the demo screenshot, not
 * guessed: the user rejected ALL five styles ("Tao ko ưng đc cái
 * nào"), and the video proves them right. Three root causes, all
 * with numbers: (1) FERROFLUID GRADIENT ANCHORING — the body
 * gradient had no startY/endY, so it spanned the whole 140 dp row
 * while the dome occupies only its bottom 24–80 dp; the dome
 * rendered at ~#1B1B1F (1.06:1 — measured pixel by pixel), and the
 * spike brush faded to #141419 (1.03:1), so only spike tips + rim
 * lines showed (the comb). Fixed by anchoring the body gradient
 * to the dome itself with brighter stops (rendered dome 4.27:1 at
 * the surface, 2.66:1 mid-dome) and solid bright spike bodies.
 * (2) CIRCULAR RAY THICKNESS — rays were 80% of the arc pitch
 * (~8.2 dp wide, ~1.4 dp gaps; each ray spans ~10° of the 11.25°
 * pitch, measured on the user's screenshot), so the ring read as
 * solid petals; the demo's rays are ~1 dp thin lines with clear
 * gaps. Fixed: 25% of pitch (~2.7 dp), disc 68 → 78 dp.
 * (3) LIFT SATURATION — sqrt(level×4) saturates at level 0.25,
 * so loud pop made every bar/spike/ray the same height (a flat
 * wall). Fixed: shared gain 4 → 2 (saturates at 0.5), Mirror 6 →
 * 2, Wave 3.5 → 2.5. Row heights UNCHANGED — the demo stage
 * measures ~130–144 dp, about the same as the app's 140 dp tall
 * row, so the "~300 dp demo stage" claim in the v1.1.14 note
 * above was wrong and height was never the problem.
 *
 * v1.1.18 — Circular: disc smaller, rays more/longer/faster.
 * The user (v1.1.17 screenshots, 21:54 portrait + 21:55
 * landscape, "...Baby One More Time") wants the art disc SMALLER
 * and the rays MORE, LONGER, FASTER/SMOOTHER, and the landscape
 * bugs fixed — nothing else. Disc 56% -> 40% of minDim, rays
 * 56 -> 96 (resampled), release 0.75 -> 0.85/frame, shared
 * publish rate 30 Hz -> ~60 Hz (WaveformTap). Landscape: the
 * visualizer now fills the whole left column (the large cover
 * is gone, matching portrait v1.1.17), the right column gets a
 * compact title row + lyrics room + a full-size transport.
 *
 * Sizing / colours: Bars / Wave / Mirror share the 88 dp slot and
 * Ferrofluid / Circular use the 140 dp slot (v1.1.14), the same
 * gain (2, was 4 — v1.1.16, see VIS_GAIN) for the FFT styles, and the same theme gradient (Deep
 * Wave teal #14B8A6 -> cyan #22D3EE; Sunset coral -> orange -> amber
 * via the theme tokens — no hardcoded colours), except Ferrofluid
 * whose liquid body is deliberately near-black in both themes (that
 * is what ferrofluid looks like) with the theme gradient used only
 * for its sheen / spike highlights / tip dots.
 */

// Scaling — the v1.1.4 calibration (see the old LiveWaveform KDoc
// for the math): only a level >= 0.25 reaches full height.
// v1.1.12: row height 112 dp -> 88 dp so the lyrics area on Now
// Playing gains 24 dp.
// v1.1.16: the "~300 dp demo stage" claim above was WRONG — the
// demo stage is ~130–144 dp, about the same as the app's 140 dp
// tall row (measured from the user's demo screenshot: the whole
// Circular visual is ~100 dp across). Row height was never the
// problem; the three real causes are in the file header KDoc.
private const val VIS_ROW_HEIGHT_DP = 88f
private const val VIS_ROW_TALL_DP = 140f
// v1.1.17 — hero canvas: the visualizer is the protagonist on Now
// Playing (was a 140 dp band under a 120 dp cover). The hero canvas
// is 320 dp tall; the full-screen mode fills the screen. Public so
// NowPlaying can pass it to VisualizerPanel.
const val VIS_HERO_HEIGHT_DP = 320f
// v1.1.17 — Circular ray count 32 -> 56 (the demo has ~56 thin
// rays; 32 rays at 11.25° pitch read as a sparse halo). The 32 FFT
// bands are resampled to 56 rays (see resampleLevels).
// v1.1.18 — 56 -> 96 rays: the user wants many more, longer rays
// ("số lượng cột âm phải tăng nhiều hơn, dài hơn"). 96 is nearly
// double the demo's ~56; the 32 FFT bands are resampled to 96
// rays (see resampleLevels). Bar width stays at the 1.5 dp floor
// (pitch 7.4 dp on the hero, 3.2 dp on the 140 dp row), so the
// ring reads as a dense halo of thin rays, not a solid flower.
private const val CIRC_RAY_COUNT = 96
private const val VIS_MIN_HEIGHT_DP = 10f
// v1.1.16 — shared FFT lift gain 4 -> 2. The sqrt lift saturated
// at level 0.25 (sqrt(0.25*4) = 1), so on a loud pop chorus most
// bands sat at 0.75–1.0 and every style read as a flat wall /
// uniform comb — no dynamic range. With gain 2 the curve keeps
// its shape to level 0.5: level 0.04 -> 0.28, 0.10 -> 0.45,
// 0.15 -> 0.55, 0.25 -> 0.71, 0.40 -> 0.89, 0.50 -> 1.0.
// Quiet bands still read (the per-style floors are unchanged).
private const val VIS_GAIN = 2f
private const val VIS_LIVE_THRESHOLD = 0.005f
// v1.1.6 — Mirror-only lift: the mirrored halves are each half the row,
// so the same level reads half as tall as in Bars, and at levels
// ~0.04-0.05 the halves were short rounded pills that read as two rows
// of static dots. Mirror therefore uses a higher gain (6 vs 4) and a
// taller minimum half (12 dp vs 5 dp, v1.1.8 — was 10 dp) so quiet bars
// still read as bars.
// At level 0.04: sqrt(0.04*6) = 0.49 (was 0.40); at 0.01: 0.245 (was
// 0.20); the minimum visible half is 12 - 2 (gap) = 10 dp per side,
// 20 dp total, instead of 3 dp per side / 6 dp total.
// v1.1.16 — gain 6 -> 2 (same reason as VIS_GAIN): gain 6 saturated
// at level 0.17, so Mirror's halves were maxed out on any loud
// passage and read as two solid rows. The 12 dp minimum half stays,
// so quiet bands still read as bars.
private const val VIS_MIRROR_GAIN = 2f
private const val VIS_MIRROR_MIN_HALF_DP = 12f

// v1.1.8 — Wave lift: the v1.1.7 screenshot showed Wave as a thin,
// nearly-flat line because raw PCM is linear — a typical passage at
// |x| ~ 0.1 only reached 10% of the half-height. The lift is a
// signed perceptual curve, sign(x) * sqrt(min(|x| * GAIN, 1)):
// quiet passages are boosted hard (|x| 0.05 -> 0.35, 0.10 -> 0.50,
// 0.20 -> 0.71) while loud peaks saturate gracefully at full height
// instead of hard-clipping into a flat-topped block. Zero crossings
// and sample order are preserved — the shape is still exactly the
// real PCM frame, only its visible amplitude is expanded.
// v1.1.14 — gain 2.5 -> 3.5: on a soft ballad the line was still
// faint (|x| 0.05 -> 0.42, 0.10 -> 0.59, 0.20 -> 0.84), and the
// stroke thickens 3 dp -> 4.5 dp with a stronger glow (see
// VisualizerWave).
// v1.1.16 — gain 3.5 -> 2.5: gain 3.5 saturates at |x| 0.29, so on
// loud pop the whole waveform clipped into a flat-topped block
// (|x| 0.30 -> 1.0). At 2.5 the peaks still reach full height
// (|x| 0.40 -> 1.0) but the body keeps its shape (|x| 0.05 ->
// 0.35, 0.10 -> 0.50, 0.20 -> 0.71); the 4.5 dp stroke + glow
// from 1.1.14 stay, so soft ballads stay visible.
private const val VIS_WAVE_GAIN = 2.5f

// v1.1.13 — peak caps (Bars / Mirror / Ferrofluid): a cap holds its
// height for ~12 published frames (~0.2 s at ~60 Hz, v1.1.18 — was
// ~0.4 s at ~30 Hz) after a new peak, then falls 0.02 of full height
// per published frame (~0.8 s for a full fall). Updated per published
// FFT frame only — no clock, no animation.
private const val PEAK_HOLD_FRAMES = 12
private const val PEAK_FALL_PER_FRAME = 0.02f
// v1.1.13 — chase (displayed-value) release per published frame:
// Bars 0.30 and Mirror 0.35 fall to ~10% of a step in ~7 frames
// (~0.12 s at ~60 Hz, v1.1.18 — was ~0.25 s at ~30 Hz); attack is
// always instant. Ferrofluid is more viscous (0.50) so the liquid
// lags the beat slightly instead of snapping.
private const val VIS_BARS_RELEASE = 0.30f
private const val VIS_MIRROR_RELEASE = 0.35f
private const val VIS_FERRO_RELEASE = 0.50f

// v1.1.13 — Ferrofluid frequency selection. The 32 bands are
// log-spaced 20 Hz–12 kHz, ratio 600^(1/32) ≈ 1.221 per band, so:
// Bass = bands 0..11 (~20–220 Hz), Treble = bands 23..31 (~2–12 kHz).
private const val FERRO_BASS_BAND_COUNT = 12
private const val FERRO_TREBLE_START_BAND = 23
// v1.1.13 — Ferrofluid blob: the liquid pool is 13 dp deep at the
// row edges and domes to 38 dp at the centre (Rosensweig-style dome).
// v1.1.14 — the row is 140 dp now, so the dome grows with it: 16 dp
// at the edges, 56 dp at the centre (centre surface at 84 dp from
// the top). Spikes drop from 32 thin slots to 16 thick ones (the
// demo shows ~14–18 uneven spikes, not a 32-tooth comb); each spike
// slot resamples the selected band range, so the blob stays full
// whatever the frequency selector says.
// v1.1.15 — WHY 1.1.14 STILL READ AS A COMB (measured from the
// user's video of "...Baby One More Time", MP3): the spikes maxed
// at 40 dp on a 140 dp row and the dome only 56 dp deep, so the
// whole silhouette filled just the bottom ~85 dp; the dome bulk
// (#1E1E22 → #0B0B0D) was 1.08–1.3:1 against the background —
// invisible — so only the bright spike tips showed, a thin tooth
// row. The sqrt lift also saturates norms (~0.45–0.7 for most
// bands), so all 16 spikes came out the same height, and the tower
// was barely taller than its neighbours. Fixes: dome 24 dp at the
// edges / 80 dp at the centre (centre surface 60 dp from the top);
// spikes 6 + norm*50 dp (max 56), tower 12 + towerNorm*48 (max
// 60) with a 1.4x prominence floor over the median spike; a
// deterministic per-slot factor (0.75–1.3) so spikes are uneven
// even when norms saturate; body gradient brightened so the whole
// dome reads as a mass, not just its top edge.
private const val FERRO_MIN_POOL_DP = 24f
private const val FERRO_MAX_POOL_DP = 80f
private const val FERRO_SPIKE_COUNT = 16
private const val FERRO_MAX_DROPLETS = 8
// v1.1.15 — deterministic per-slot height factors: the sqrt lift
// saturates most norms, so without this every spike comes out the
// same height (the comb look). Centre slot (index 8) is the tower
// and keeps factor 1.0 — its prominence comes from the tower
// formula, not from this table.
private val FERRO_SLOT_FACTOR = floatArrayOf(
    0.75f, 1.15f, 0.9f, 1.3f, 0.8f, 1.05f, 1.25f, 0.85f,
    1.0f, 1.2f, 0.8f, 1.1f, 0.95f, 1.3f, 0.75f, 1.05f
)

private fun waveLift(sample: Float): Float {
    val s = (sample * VIS_WAVE_GAIN).coerceIn(-1f, 1f)
    return if (s >= 0f) sqrt(s) else -sqrt(-s)
}

/** Perceptual lift of one raw FFT band level, 0..1. */
private fun scaledNorm(level: Float): Float =
    sqrt((level * VIS_GAIN).coerceIn(0f, 1f))

/**
 * v1.1.17 — linearly resample `levels` to `count` values (used to
 * ring 96 rays from the 32 FFT bands, v1.1.18 — was 56). Neighbouring
 * rays share interpolated band values, so the ring stays smooth
 * instead of duplicating whole bands.
 */
private fun resampleLevels(levels: FloatArray, count: Int): FloatArray {
    if (levels.isEmpty()) return FloatArray(count)
    if (count == levels.size) return levels.copyOf()
    val out = FloatArray(count)
    for (i in 0 until count) {
        val pos = i.toFloat() * (levels.size - 1) / (count - 1).coerceAtLeast(1)
        val lo = pos.toInt().coerceIn(0, levels.size - 1)
        val hi = (lo + 1).coerceAtMost(levels.size - 1)
        val frac = pos - lo
        out[i] = levels[lo] * (1f - frac) + levels[hi] * frac
    }
    return out
}

private fun isLive(levels: FloatArray): Boolean =
    levels.any { it > VIS_LIVE_THRESHOLD }

/** Fixed per-bar idle shape (values in -1..1, symmetric) — the same
 *  array the old LiveWaveform used, so the idle state looks identical
 *  in every style. Deterministic from the bar index only. */
private val STATIC_IDLE = floatArrayOf(
    0.0f, 0.6f, 1.0f, 0.4f, -0.3f, -0.8f, -0.2f, 0.7f,
    0.9f, 0.1f, -0.6f, -1.0f, -0.4f, 0.5f, 0.8f, 0.0f
)

/** Idle bar height in dp (18..30), from the bar index only. */
private fun idleHeightDp(i: Int): Float =
    18f + 12f * (0.5f + 0.5f * STATIC_IDLE[i % STATIC_IDLE.size])

/**
 * v1.1.13 — peak-hold caps (Monstercat-style). Remembers each band's
 * recent peak in 0..1 norm units. Updated per published FFT frame
 * only — no clock: a new high takes the peak instantly and restarts
 * the hold; while held the cap does not move; after the hold it
 * falls at [PEAK_FALL_PER_FRAME] per frame until it meets the live
 * value. When idle the peaks are dropped, so the next live frame
 * starts from zero instead of showing stale caps.
 */
private class PeakCaps(val n: Int) {
    val peaks = FloatArray(n)
    val hold = IntArray(n)

    fun update(targets: FloatArray, live: Boolean) {
        if (!live) {
            peaks.fill(0f)
            hold.fill(0)
            return
        }
        for (i in 0 until n) {
            val t = targets[i].coerceIn(0f, 1f)
            if (t >= peaks[i]) {
                peaks[i] = t
                hold[i] = PEAK_HOLD_FRAMES
            } else if (hold[i] > 0) {
                hold[i]--
            } else {
                peaks[i] = (peaks[i] - PEAK_FALL_PER_FRAME).coerceAtLeast(t)
            }
        }
    }
}

/**
 * v1.1.13 — per-bar displayed value: instant attack, slow release.
 * Called in composition from the newest published levels only — no
 * clock, no animation. A rising target is taken instantly; a falling
 * target is chased at `release` per frame, so the bars hug the
 * published levels on the way up and fall smoothly instead of
 * snapping down with them. When idle the state is dropped.
 */
private fun chaseDisplayed(displayed: FloatArray, levels: FloatArray, live: Boolean, release: Float) {
    if (!live) {
        displayed.fill(0f)
        return
    }
    for (i in displayed.indices) {
        val target = levels[i]
        displayed[i] = if (target > displayed[i]) target
        else displayed[i] + (target - displayed[i]) * release
    }
}

/**
 * v1.1.13 — one droplet detached from a ferrofluid spike tip.
 * Position is in dp of the 140 dp row; `age` counts published FFT
 * frames since detachment — no clock. The droplet rises ~2.4 dp per
 * frame and is removed after 12 frames.
 */
private class FerroDroplet(var xDp: Float, var yDp: Float, var age: Int)

/** Dispatcher — the single entry point for Now Playing.
 * v1.1.17: `heightDp` overrides the per-style default row height
 * (88 dp Bars/Wave/Mirror, 140 dp Circular/Ferrofluid). The hero
 * canvas passes 320 dp and the full-screen mode passes the screen
 * height; null keeps the default. */
@Composable
fun MioVisualizer(
    style: VisualizerStyle,
    levels: FloatArray,
    pcmFrame: FloatArray,
    playing: Boolean,
    albumId: Long = 0L,
    coverPath: String? = null,
    ferrofluidFreq: FerrofluidFreq = FerrofluidFreq.ALL,
    modifier: Modifier = Modifier,
    heightDp: Float? = null
) {
    when (style) {
        VisualizerStyle.BARS -> VisualizerBars(levels, playing, modifier, heightDp ?: VIS_ROW_HEIGHT_DP)
        VisualizerStyle.WAVE -> VisualizerWave(pcmFrame, playing, modifier, heightDp ?: VIS_ROW_HEIGHT_DP)
        VisualizerStyle.CIRCULAR -> VisualizerCircular(levels, playing, albumId, coverPath, modifier, heightDp ?: VIS_ROW_TALL_DP)
        VisualizerStyle.MIRROR -> VisualizerMirror(levels, playing, modifier, heightDp ?: VIS_ROW_HEIGHT_DP)
        VisualizerStyle.FERROFLUID -> VisualizerFerrofluid(levels, playing, ferrofluidFreq, modifier, heightDp ?: VIS_ROW_TALL_DP)
    }
}

/**
 * Style 1 — Bars (the default). v1.1.13: Monstercat-style rework,
 * now native in this file — the old LiveWaveform (Row of 5 dp Boxes
 * in Common.kt) is deleted; it was only ever used by Bars, verified
 * by grep, so there is no dead code and no second implementation to
 * drift. Same calibration as before: gain 4 (level 0.25 = full
 * height, see the v1.1.4 math in the old LiveWaveform KDoc), same
 * index-determined idle shape, same vertical theme gradient, same
 * 88 dp slot — only the rendering changes: one Canvas (bar widths
 * now fill the row instead of stopping at 32 × 5 dp + spacing),
 * fast attack / slow release per bar (instant rise, 0.30/frame
 * fall), and a peak cap above each bar — a small bright cap line at
 * the bar's recent peak that holds ~0.4 s then falls slowly (see
 * [PeakCaps]). With no signal the bars sit at the fixed idle shape
 * with no caps.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VisualizerBars(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier, heightDp: Float = VIS_ROW_HEIGHT_DP) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val live = isLive(levels)
    val n = levels.size
    val displayed = remember(n) { FloatArray(n) }
    val norms = remember(n) { FloatArray(n) }
    val caps = remember(n) { PeakCaps(n) }
    chaseDisplayed(displayed, levels, live, VIS_BARS_RELEASE)
    for (i in 0 until n) norms[i] = if (live) scaledNorm(displayed[i]) else 0f
    caps.update(norms, live)
    // v1.1.17: bar heights are fractions of the canvas height, so a
    // taller hero/full-screen canvas makes the bars taller for free.
    Canvas(modifier.height(heightDp.dp).fillMaxWidth()) {
        val spacing = 3.dp.toPx()
        val barW = ((size.width - (n - 1).coerceAtLeast(0) * spacing) / n.coerceAtLeast(1)).coerceAtLeast(1.dp.toPx())
        val bottom = size.height
        val minH = VIS_MIN_HEIGHT_DP.dp.toPx()
        val maxH = size.height
        val brush: Brush = if (live) Brush.verticalGradient(colors) else SolidColor(accent.copy(alpha = 0.4f))
        val radius = CornerRadius(barW / 2f, barW / 2f)
        for (i in 0 until n) {
            val h = if (live) {
                minH + norms[i] * (maxH - minH)
            } else {
                idleHeightDp(i).dp.toPx().coerceAtMost(maxH)
            }
            val x = i * (barW + spacing)
            drawRoundRect(
                brush,
                topLeft = Offset(x, bottom - h),
                size = Size(barW, h),
                cornerRadius = radius
            )
            // Peak cap: a bright line floating just above the bar at
            // its recent peak. Only live, and only once the peak is
            // meaningfully above the minimum stub.
            // v1.1.14: 2 dp accent -> 3 dp WHITE with an 8 dp gap
            // (was 5 dp) — the 1.1.13 caps were too small and faint
            // to see on device; a white cap reads against any bar.
            if (live && caps.peaks[i] > 0.03f) {
                val peakH = minH + caps.peaks[i] * (maxH - minH)
                val capY = (bottom - peakH - 8.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(
                    SolidColor(Color.White),
                    topLeft = Offset(x, capY),
                    size = Size(barW, 3.dp.toPx()),
                    cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                )
            }
        }
    }
}

/**
 * Style 2 — Wave / oscilloscope line. Draws the raw mono PCM frame
 * (1024 samples) as one continuous polyline, stroked with the theme
 * gradient and a faint fill underneath. The frame refreshes at the
 * FFT cadence (~60 Hz, v1.1.18 — was ~30 Hz); between frames the line
 * is simply redrawn from the same real samples — nothing is
 * interpolated or animated, deliberately: interpolation would invent
 * samples that were never played, and at ~60 Hz fresh real samples
 * arrive every 16 ms, so the higher rate alone makes the line move
 * smoothly. With no signal the line is a flat, still centre line.
 *
 * v1.1.8 — amplitude rework (the v1.1.7 screenshot showed a thin,
 * nearly-flat line: linear mapping put a typical |x| ~ 0.1 passage
 * at 10% of the half-height). The samples now go through the signed
 * perceptual lift waveLift() — sign(x) * sqrt(min(|x| * 2.5, 1)) —
 * so |x| 0.05 reads at 35% of half-height, 0.10 at 50%, 0.20 at 71%,
 * and loud peaks saturate at full height. The stroke also thickened
 * 2 dp -> 3 dp and the fill 0.10 -> 0.16 alpha, so the wave is
 * clearly visible and lively at any normal listening level.
 * Still real samples only: no smoothing, no fake motion.
 *
 * v1.1.13 — glow polish (foobar2000 Oscilloscope / Wallpaper Engine
 * Blue Waveform style): the same real-PCM line, now drawn as a soft
 * glow — a wide low-alpha accent stroke underneath (9 dp, 0.22
 * alpha) with the thin full-alpha gradient stroke on top — and a
 * slightly brighter fill (0.16 -> 0.22 alpha), so it reads as one
 * clean glowing waveform on the background instead of a thin faint
 * line. No new data, no smoothing: the glow is only paint.
 *
 * v1.1.14 — still too faint on a soft ballad (the user's video:
 * "Anh Mo" barely moves the line): stroke 3 dp -> 4.5 dp, glow
 * 9 dp / 0.22 -> 12 dp / 0.35 alpha, fill 0.22 -> 0.30 alpha, and
 * the lift gain 2.5 -> 3.5 (see VIS_WAVE_GAIN). Same real samples.
 */
@Composable
fun VisualizerWave(pcmFrame: FloatArray, playing: Boolean, modifier: Modifier = Modifier, heightDp: Float = VIS_ROW_HEIGHT_DP) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val divider = LocalMioColors.current.divider
    // |x| > 0.02 anywhere in the frame counts as signal; a frame of
    // near-silence (leading/trailing) is a flat line, not noise.
    val hasSignal = playing && pcmFrame.any { it * it > 0.0004f }
    // v1.1.17: amplitude is a fraction of the half-height, so a
    // taller hero/full-screen canvas makes the wave taller for free.
    Canvas(modifier.height(heightDp.dp).fillMaxWidth()) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        // Faint centre line, always on (the oscilloscope baseline).
        drawLine(divider, Offset(0f, cy), Offset(w, cy), strokeWidth = 1.dp.toPx())
        if (!hasSignal) {
            drawLine(
                accent.copy(alpha = 0.4f),
                Offset(0f, cy), Offset(w, cy),
                strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
            )
            return@Canvas
        }
        // One plotted point per ~2 px of width (the 1024-sample frame
        // is downsampled, never upsampled or smoothed).
        val points = (w / 2.dp.toPx()).toInt().coerceAtLeast(2)
        val n = pcmFrame.size
        val amp = h / 2f - 4.dp.toPx()
        val linePath = Path()
        val fillPath = Path()
        for (p in 0 until points) {
            val idx = (p * (n - 1) / (points - 1)).coerceIn(0, n - 1)
            val x = p.toFloat() / (points - 1) * w
            val y = cy - waveLift(pcmFrame[idx]) * amp
            if (p == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, cy)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(w, cy)
        fillPath.close()
        // v1.1.14: fill 0.22 -> 0.30 alpha, glow 9 dp / 0.22 -> 12 dp
        // / 0.35 alpha, main stroke 3 dp -> 4.5 dp.
        drawPath(fillPath, SolidColor(accent.copy(alpha = 0.30f)))
        // v1.1.14: soft glow — a wide low-alpha stroke underneath
        // the thin full-alpha gradient stroke on top.
        drawPath(
            linePath,
            SolidColor(accent.copy(alpha = 0.35f)),
            style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
        )
        drawPath(
            linePath,
            brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(w, 0f)),
            style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Style 3 — Circular / radial bars, Avee / Wallpaper Engine
 * "Rainbow Rings" style. The 32 FFT bands radiate outwards from the
 * album art in the centre (first band at the top, clockwise), with
 * the 32 radial bars ringing around it. Bar length uses Circular's
 * own lift (v1.1.11, see below), steeper than the shared
 * scaledNorm() the Bars style uses, so the same level change reads
 * as a bigger radial move; bar colour blends along the theme
 * gradient by band index (low = first gradient stop, high = last).
 * With no signal every band is a short fixed stub, and the art disc
 * still shows.
 *
 * v1.1.7 redesign (user asked for a smaller ring, bigger bars): the
 * inner circle shrank from 0.30 to 0.18 × minDim, the bars grew from
 * 0.19 to 0.30 × minDim and thickened from 60% to 80% of the arc
 * pitch — the ring now reads as a small hub with long, chunky rays.
 * At full scale the bar tip just touches the slot edge (the same
 * class of edge-touch the old geometry already had at max level).
 *
 * v1.1.11 — responsiveness rework (user: "visualizer tròn chậm quá").
 * Why it read as slow, measured from the code:
 *  - Circular reads the same smoothed `levels` as Bars, but its bars
 *    only travel maxLen = 0.30 × 88 dp = ~26 dp, while a Bars bar
 *    travels up to ~102 dp on the same row — the same fractional level
 *    change moves ~3x fewer dp here, so motion is harder to see.
 *  - The shared lift sqrt(level * 4) saturates at level 0.25, so loud
 *    bands sit pinned at full length and only the quiet ones move.
 *  - Upstream lag Circular cannot remove: the 8192-sample FFT window
 *    is 186 ms at 44.1 kHz (MP3) with ~82% overlap between frames
 *    (~93 ms group delay), and WaveformTap smooths with attack 0.7
 *    (tau ~27 ms) / release 0.4 (tau ~65 ms, ~150 ms to fall to 10%).
 * What changed, Circular only: each bar keeps a displayed value
 * (remember) that jumps instantly to a rising target and chases a
 * falling target at 0.75/frame (tau ~24 ms, ~55 ms to 10% of the
 * published value — the chase hugs the published levels, it cannot
 * beat the tap's own fall); and the lift is now (level * 4)^0.75
 * instead of sqrt(level * 4), which widens the visible swing per
 * doubling of level by ~10% at 0.05 -> 0.10, ~31% at 0.10 -> 0.20
 * and ~46% at 0.20 -> 0.40, at the cost of slightly less lift below
 * 0.05 (bars already near the 6 dp floor there). Layout unchanged
 * (innerR, maxLen, bar width, 6 dp floor), still driven only by the
 * real FFT levels — no clock, no time-based animation.
 *
 * v1.1.13 — album art in the middle. The hub is now the current
 * track's cover: a 44 dp circle-clipped disc (the existing AlbumArt
 * composable — downloaded cover file wins over MediaStore, same as
 * everywhere else) with the 32 radial bars ringing around it, so the
 * whole 88 dp slot reads as a record with a spectrum ring. The disc
 * sits inside the slot by construction: disc radius 22 dp + 3 dp
 * gap = innerR 25 dp, and the radial budget is the remaining
 * 44 − 25 − 2 = 17 dp to the slot edge, so no bar tip can clip.
 * With no art (no cover file and albumId <= 0) the disc falls back
 * to a theme-gradient disc with a white music note — never a blank
 * hole. The v1.1.11 per-bar chase + lift are kept unchanged; only
 * the geometry shrank to make room for the disc (the old divider
 * inner ring is gone — the disc edge is the hub now — and the idle
 * stubs are scaled to the smaller budget).
 *
 * v1.1.14 — the 44 dp disc on a ~360 dp screen read as a dot (the
 * user's video: the whole circle is 88 dp tall). The row is now
 * 140 dp and the disc is 68 dp (radius 34 dp + 3 dp gap = innerR
 * 37 dp), leaving a 70 − 37 − 2 = 31 dp radial budget — the disc
 * is the focal point, the way the demo stages it. Geometry is
 * otherwise unchanged; the style-name pill no longer sits in the
 * centre covering the disc (see VisualizerPanel — it moved to the
 * top-left corner).
 *
 * v1.1.16 — WHY 68 dp STILL READ AS A SMALL FLOWER (user's video +
 * screenshot, "...Baby One More Time", measured): the disc was
 * never the problem — the RAYS were. At 80% of the arc pitch each
 * ray is ~8.2 dp wide with ~1.4 dp gaps (measured: each ray spans
 * ~10° of the 11.25° pitch), so the 32 rays merge into a solid
 * petal ring that visually swallows the disc; the demo's rays are
 * ~1 dp thin lines with clear gaps. Fixes: rays 80% → 25% of
 * pitch (~2.7 dp), disc 68 → 78 dp (innerR 42 dp, budget 26 dp —
 * the demo's disc/total proportion ≈ 0.60, preserved at the full
 * row height). The lift is now the shared sqrt(level×2) curve
 * (gain 4 → 2, power 0.75 → 0.5): the old curve saturated at
 * level 0.25, so every ray hit full length on a loud chorus. The
 * 0.75/frame release (v1.1.11 speed fix) is unchanged.
 *
 * v1.1.18 — user wants the art disc SMALLER and the rays MORE,
 * LONGER, FASTER/SMOOTHER ("vòng tròn chứa hình bài hát nhỏ
 * lại, còn số lượng cột âm phải tăng nhiều hơn, dài hơn, thay
 * đổi nhanh mượt"). Disc 56% -> 40% of minDim (hero 180 dp ->
 * 128 dp, radial budget 65 dp -> 91 dp); rays 56 -> 96
 * (resampled from the 32 FFT bands, bar width stays at the
 * 1.5 dp floor); release 0.75 -> 0.85/frame AND the shared
 * publish rate goes 30 Hz -> ~60 Hz (see WaveformTap), so the
 * rays track the beat in ~half the wall-clock time and move at
 * display rate. Measured on the user's v1.1.17 screenshots
 * (21:54 portrait, 21:55 landscape): portrait hero disc 179 dp
 * (as coded), landscape disc ~75 dp on a 140 dp panel — the
 * "small again" is the 140 dp panel, fixed by the landscape
 * restructure in NowPlayingLandscape.kt (the visualizer now
 * fills the column).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VisualizerCircular(
    levels: FloatArray,
    playing: Boolean,
    albumId: Long = 0L,
    coverPath: String? = null,
    modifier: Modifier = Modifier,
    heightDp: Float = VIS_ROW_TALL_DP
) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val live = isLive(levels)
    // v1.1.17 — 56 rays (the demo has ~56 thin rays; 32 rays at
    // 11.25° pitch read as a sparse halo). The 32 FFT bands are
    // resampled to 56 ray values (see resampleLevels).
    // v1.1.18 — 96 rays (user: many more, longer rays). Same
    // resample, just a larger count (see CIRC_RAY_COUNT).
    val rayCount = CIRC_RAY_COUNT
    val rayLevels = resampleLevels(levels, rayCount)
    // v1.1.11 — per-bar displayed lengths (Circular-only constants,
    // kept local so no shared constant is touched). Updated here, in
    // composition, from the newest published levels only — no clock,
    // no animation: a rising target is taken instantly, a falling
    // target is chased at 0.85/frame, so the bars hug the published
    // levels within ~1 frame instead of adding a second layer of
    // smoothing on top of WaveformTap's. When idle the chase state is
    // dropped, so the next live frame attacks instantly from zero
    // instead of decaying from stale bar lengths.
    // v1.1.16 — lift gain 4 -> 2, power 0.75 -> 0.5: this is now
    // exactly the shared sqrt(level * 2) curve (see scaledNorm).
    // The old (level*4)^0.75 saturated at level 0.25, so on a loud
    // chorus every ray hit full length and the ring read as a solid
    // flower.
    // v1.1.18 — release 0.75 -> 0.85/frame (faster falls, still
    // smooth) AND the shared publish rate goes 30 Hz -> ~60 Hz
    // (see WaveformTap FFT_INTERVAL_MS), so the chase runs twice
    // as often: the wall-clock fall time constant roughly halves
    // again. Together the rays track the beat "nhanh mượt" (fast
    // and smooth) as the user asked.
    val liftGain = 2f // same gain as the shared sqrt lift; only the exponent changes
    val liftPower = 0.5f
    val releaseWeight = 0.85f
    val displayed = remember(rayCount) { FloatArray(rayCount) }
    if (live) {
        for (i in 0 until rayCount) {
            val target = ((rayLevels[i] * liftGain).coerceIn(0f, 1f)).pow(liftPower)
            displayed[i] = if (target > displayed[i]) target
            else displayed[i] + (target - displayed[i]) * releaseWeight
        }
    } else {
        displayed.fill(0f)
    }
    // v1.1.13: the disc shows real art exactly when AlbumArt would
    // find some — a downloaded cover file, or a MediaStore albumId.
    // (albumId > 0 does not guarantee MediaStore actually has the
    // image, but that is AlbumArt's own rule everywhere else too.)
    val hasArt = (coverPath?.let { File(it).exists() } == true) || albumId > 0
    // v1.1.17 — hero size: the box height is `heightDp` (140 dp
    // default, 320 dp hero, screen height full-screen) and the disc
    // is 56% of the smaller canvas dimension (the demo's disc/total
    // is ~0.55–0.57), so the disc scales with the canvas instead of
    // staying a fixed 78 dp in a big empty box.
    // v1.1.18 — disc 56% -> 40%: the user wants the art disc
    // SMALLER and the rays LONGER ("vòng tròn chứa hình bài hát
    // nhỏ lại, còn số lượng cột âm phải tăng nhiều hơn, dài hơn").
    // A smaller hub leaves a much bigger radial budget for the rays.
    BoxWithConstraints(modifier.height(heightDp.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val minDimDp = minOf(maxWidth, maxHeight)
        val discDp = minDimDp * 0.40f
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val minDim = minOf(size.width, size.height)
            // v1.1.17: the disc WAS 28% of the smaller canvas
            // dimension (diameter 56% — the demo's disc/total is
            // ~0.55–0.57), so it scaled with the canvas: 39 dp on
            // the 140 dp row, ~90 dp on the 320 dp hero (180 dp
            // disc), ~115 dp full-screen. innerR is the disc radius
            // + a 3 dp gap; the radial budget is what is left to the
            // slot edge, so bar tips can never clip.
            // v1.1.18 — disc radius 28% -> 20% of minDim (diameter
            // 40%): 28 dp on the 140 dp row (56 dp disc, budget
            // 37 dp), 64 dp on the 320 dp hero (128 dp disc,
            // budget 91 dp), ~65 dp in landscape (~130 dp disc,
            // budget ~92 dp). The rays are now the dominant
            // feature, as the user asked.
            val discR = minDim * 0.20f
            val innerR = discR + 3.dp.toPx()
            val budget = (minDim / 2f - innerR - 2.dp.toPx()).coerceAtLeast(4.dp.toPx())
            val midR = innerR + budget / 2f
            // v1.1.17 — bar thickness: ~1.5 dp absolute (the demo's
            // rays are ~1.4 dp), NOT 25% of pitch (≈2.7 dp at 32
            // rays). At 96 rays (v1.1.18) the pitch is smaller still
            // (7.4 dp hero, 3.2 dp on the 140 dp row), so the 12%
            // pitch term (0.9 dp / 0.4 dp) is always below the 1.5 dp
            // floor and the rays are a uniform 1.5 dp everywhere.
            val barWidth = (2f * PI.toFloat() * midR / rayCount.coerceAtLeast(1) * 0.12f).coerceAtLeast(1.5.dp.toPx())
            // Thin accent ring on the disc edge (the old divider inner
            // ring is gone — the disc is the hub now).
            drawCircle(accent.copy(alpha = 0.45f), radius = discR, center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
            for (i in 0 until rayCount) {
                val len = if (live) {
                    // v1.1.8: live floor so quiet bands still read as
                    // visible rays, not dots at the hub; v1.1.13: the
                    // floor + swing are budgeted inside `budget`, so
                    // the tip never passes the slot edge.
                    4.dp.toPx() + displayed[i] * (budget - 4.dp.toPx())
                } else {
                    // Idle stub: same 18..30 dp shape as Bars, scaled to the ring.
                    3.dp.toPx() + (idleHeightDp(i) - VIS_MIN_HEIGHT_DP) /
                        (heightDp - VIS_MIN_HEIGHT_DP) * budget * 0.4f
                }
                val angle = (-PI / 2 + 2 * PI * i / rayCount.coerceAtLeast(1)).toFloat()
                val dir = Offset(cos(angle), sin(angle))
                val start = Offset(cx + dir.x * innerR, cy + dir.y * innerR)
                val end = Offset(cx + dir.x * (innerR + len), cy + dir.y * (innerR + len))
                val color: Color = if (live) {
                    lerp(colors.first(), colors.last(), i.toFloat() / (rayCount - 1).coerceAtLeast(1))
                } else {
                    accent.copy(alpha = 0.4f)
                }
                drawLine(color, start, end, strokeWidth = barWidth, cap = StrokeCap.Round)
            }
        }
        // v1.1.17: centre disc — 56% of the smaller canvas
        // dimension (78 dp on the 140 dp row, ~180 dp on the 320 dp
        // hero, ~230 dp full-screen). Real art when there is any,
        // else a theme-gradient disc with a white note (never blank).
        // v1.1.18 — 40% (56 dp on the 140 dp row, ~128 dp on the
        // 320 dp hero, ~130 dp in landscape): smaller hub, longer rays.
        if (hasArt) {
            AlbumArt(albumId, Modifier.size(discDp), CircleShape, coverPath = coverPath)
        } else {
            Box(
                Modifier.size(discDp).mioGradientBackground(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(discDp * 0.41f))
            }
        }
    }
}

/**
 * Style 4 — Mirror / symmetric bars. Each FFT band is drawn twice,
 * once above and once below a centre line, at the same height — the
 * spectrum mirrored around the horizontal centre axis. Bar widths,
 * spacing and the vertical theme gradient match the Bars style; only
 * the layout is mirrored. v1.1.6: Mirror uses its own higher gain
 * (VIS_MIRROR_GAIN = 6) and taller minimum half (12 dp, v1.1.8 — was
 * 10 dp), and the corner radius is rounded only horizontally (2 dp
 * vertically) — the old fully-rounded pills at low levels read as two
 * rows of static dots; short Mirror bars now read as flat-topped bars. With no
 * signal both halves are short fixed stubs, symmetric by construction.
 *
 * v1.1.13 — MilkDrop / projectM-inspired symmetry polish: peak caps
 * like Bars (a bright cap line floating just outside each half at
 * its recent peak — see [PeakCaps]), a bright centre line (a soft
 * low-alpha accent stroke with a thin full-alpha line on top,
 * sitting in the 4 dp gap between the halves), and a vertically
 * mirrored theme gradient — the top half now runs from the last
 * gradient stop at the top to the first at the centre, the bottom
 * half from the first at the centre to the last at the bottom, so
 * the colour is symmetric around the centre line instead of running
 * one way down the whole row. Same gain, same chase-free behaviour
 * as before apart from the caps (instant attack, 0.35/frame
 * release on the displayed values so the halves fall smoothly).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VisualizerMirror(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier, heightDp: Float = VIS_ROW_HEIGHT_DP) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val live = isLive(levels)
    val n = levels.size
    val displayed = remember(n) { FloatArray(n) }
    val halfNorms = remember(n) { FloatArray(n) }
    val caps = remember(n) { PeakCaps(n) }
    chaseDisplayed(displayed, levels, live, VIS_MIRROR_RELEASE)
    for (i in 0 until n) {
        halfNorms[i] = if (live) sqrt((displayed[i] * VIS_MIRROR_GAIN).coerceIn(0f, 1f)) else 0f
    }
    caps.update(halfNorms, live)
    // v1.1.17: halves are fractions of the canvas height, so a
    // taller hero/full-screen canvas makes the mirror taller for free.
    Canvas(modifier.height(heightDp.dp).fillMaxWidth()) {
        val gap = 2.dp.toPx()
        val spacing = 3.dp.toPx()
        val barW = ((size.width - (n - 1).coerceAtLeast(0) * spacing) / n.coerceAtLeast(1)).coerceAtLeast(1.dp.toPx())
        val cy = size.height / 2f
        val maxHalf = size.height / 2f - gap
        val minHalf = VIS_MIRROR_MIN_HALF_DP.dp.toPx()
        // v1.1.13: mirrored gradient — top half reversed (last stop at
        // the top, first stop at the centre), bottom half forward
        // (first stop at the centre, last stop at the bottom), so the
        // colour is symmetric around the centre line.
        val topBrush: Brush = if (live) Brush.verticalGradient(colors.reversed()) else SolidColor(accent.copy(alpha = 0.4f))
        val bottomBrush: Brush = if (live) Brush.verticalGradient(colors) else SolidColor(accent.copy(alpha = 0.4f))
        // v1.1.6: rounded horizontally (barW/2) but almost square
        // vertically (2 dp) — a fully-rounded short bar is a pill/dot.
        val radius = CornerRadius(barW / 2f, 2.dp.toPx())
        for (i in 0 until n) {
            val half = if (live) {
                // Mirror-only gain (6, not the shared 4): each half is
                // half the row, so quiet levels need the extra lift to
                // read as bars rather than dots.
                minHalf + halfNorms[i] * (maxHalf - minHalf)
            } else {
                // Idle stub: half the Bars idle height (the bar is split
                // across the centre line), still index-determined only.
                (idleHeightDp(i) / 2f).dp.toPx().coerceAtMost(maxHalf)
            }
            val x = i * (barW + spacing)
            // Upper half: from (centre - half) up to (centre - gap).
            drawRoundRect(
                topBrush,
                topLeft = Offset(x, cy - half),
                size = Size(barW, (half - gap).coerceAtLeast(1f)),
                cornerRadius = radius
            )
            // Lower half: mirror image below the centre line.
            drawRoundRect(
                bottomBrush,
                topLeft = Offset(x, cy + gap),
                size = Size(barW, (half - gap).coerceAtLeast(1f)),
                cornerRadius = radius
            )
            // v1.1.13: peak caps — one bright line floating just
            // outside each half at its recent peak.
            // v1.1.14: 2 dp accent -> 3 dp WHITE, 6 dp clear of the
            // bar (was the 2 dp bar gap) — same fix as Bars, so the
            // caps read instead of blending into the bar tops.
            if (live && caps.peaks[i] > 0.03f) {
                val peakHalf = minHalf + caps.peaks[i] * (maxHalf - minHalf)
                val capH = 3.dp.toPx()
                val capGap = 6.dp.toPx()
                val capRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                drawRoundRect(
                    SolidColor(Color.White),
                    topLeft = Offset(x, (cy - peakHalf - capGap - capH).coerceAtLeast(0f)),
                    size = Size(barW, capH),
                    cornerRadius = capRadius
                )
                drawRoundRect(
                    SolidColor(Color.White),
                    topLeft = Offset(x, cy + peakHalf + capGap),
                    size = Size(barW, capH),
                    cornerRadius = capRadius
                )
            }
        }
        // v1.1.13: bright centre line — a soft wide glow underneath a
        // thin full-alpha line, sitting in the gap between the halves.
        drawLine(accent.copy(alpha = 0.25f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(accent, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

/**
 * Unit-agnostic ferrofluid dome: the y of the liquid surface at
 * horizontal position `x` in a row `w` wide and `h` tall. The pool
 * is `minPool` deep at the edges and domes to `maxPool` at the
 * centre (cosine dome — the 2D read of the concentric Rosensweig
 * rings). Works in dp or px, as long as all five inputs share a unit.
 */
private fun ferroSurfaceY(x: Float, w: Float, h: Float, minPool: Float, maxPool: Float): Float {
    val t = ((x / w) - 0.5f) * 2f // -1 at the left edge, +1 at the right
    val dome = cos(t * PI.toFloat() / 2f) // 1 at the centre, 0 at the edges
    return h - (minPool + (maxPool - minPool) * dome)
}

/**
 * Style 5 — Ferrofluid / magnetic liquid.
 *
 * v1.1.12 (superseded — see v1.1.13 below): one liquid column per
 * FFT band — a dark body topped by a sharp spike. The user called
 * that look "phèn": 32 separate thin columns read as spiky bars on
 * a dark row, not as a liquid.
 *
 * v1.1.13 — REDONE from the research (Operus "Music Becomes a
 * Magnetic Field" shader, Nikkiro's open-source spiky ferrofluid
 * sphere, Dakd Jung's physical ferrofluid speaker): ONE glossy
 * black liquid blob, not 32 columns. A single domed pool spans the
 * row (13 dp deep at the edges, 38 dp at the centre); the spikes
 * grow OUT of it — each spike is a curved flare whose wide base
 * sinks into the blob and is filled with the SAME vertical-gradient
 * brush as the blob, so there is never a seam or a cone sitting on
 * top; the bases blend smoothly by construction. Spike height
 * follows the band energy via the shared lift; the spikes are
 * arranged across the dome, tallest toward the centre, which is the
 * 2D read of the concentric Rosensweig ring pattern. One tall
 * centre spike / tower replaces the middle band's spike and rises
 * on build-ups — it follows the bass energy (mean of bands 0..5),
 * not the selected range, so a bass drop lifts the tower whatever
 * the frequency selector says. Droplets detach from spike tips on
 * drops: when a band's norm falls sharply (> 0.30) from its recent
 * peak (> 0.45), a droplet spawns at that tip and rises ~2.4 dp per
 * published FFT frame for 12 frames, then vanishes (state updated
 * per published frame only — no clock). One strong highlight: a
 * theme-gradient sheen stroked along the dome surface, a soft white
 * specular arc on its upper-left, and bright tip dots on loud
 * spikes (white on the tower tip). The liquid body stays near-black
 * in BOTH themes (that is what ferrofluid looks like); only the
 * sheen / rims / tip dots use the theme gradient, so it reads on
 * the Deep Wave dark background and on the Sunset light background
 * alike. With no signal the blob sits low and still with tiny blunt
 * spikes — the same idle rule as the other styles. Cheap by design:
 * four remembered Paths (blob / spike / sheen / highlight) rewound
 * per frame, plain circle / line draws for dots and droplets, no
 * per-frame allocations beyond what Wave already does. The tallest
 * spike is budgeted inside the row (centre dome top 56 dp + tower
 * max 50 dp = 106 dp of the 140 dp row), so nothing clips at the
 * top edge.
 *
 * Frequency selector (Dakd Jung's dial, as a segmented control —
 * see [VisualizerPanel], which shows it only while Ferrofluid is
 * active): Bass = spikes follow the low bands only (0..11,
 * ~20–220 Hz), Tất cả = the full spectrum (0..31), Treble = the
 * high bands only (23..31, ~2–12 kHz). The selected range is
 * resampled across all 16 spike slots, so the blob stays full
 * whatever the range; the centre tower always follows bass.
 *
 * v1.1.14 — WHY THE 1.1.13 BLOB WAS INVISIBLE (measured from the
 * user's video, frame by frame; ratios computed from the sRGB
 * relative-luminance formula, verified in Python before shipping):
 * the blob gradient (#2E2E33 → #0B0B0D → #050506) on the Deep Wave
 * background (#07181D) is 1.08:1 at the mid/base (1.34:1 at the
 * top) — blob and background are the same black, so only the 1.5 dp
 * sheen and the tip dots showed, i.e. a thin teal comb. Fixes, all
 * in this release:
 *  (1) Body gradient brightened to #4A4A52 → #1E1E22 → #0B0B0D —
 *      the dome top is now 2.07:1 against #07181D (was 1.34:1);
 *      the mid/base stay near-black by design (1.08:1 — that is
 *      what ferrofluid looks like), and the dome outline is
 *      carried by the glowing rim instead (see (2)).
 *  (2) The dome rim is a 2.5 dp theme-gradient stroke with a 7 dp
 *      soft glow underneath, so the whole dome outline reads.
 *  (3) Spikes get their OWN brighter brush — a per-spike vertical
 *      gradient #6A6A73 (tip, 3.39:1 against the background) →
 *      #2E2E35 → #141419 (base) — instead of sharing the blob's
 *      near-black brush, with a 2 dp sheen on BOTH edges.
 *  (4) 32 thin slots → 16 thick spikes (wide bases, uneven
 *      heights, like the demo stage — a 32-tooth comb is what made
 *      it read as cheap bars).
 *  (5) Heights grow with the 140 dp row: spikes up to 40 dp, tower
 *      up to 50 dp (was 27 / 34 dp on the 88 dp row); the dome is
 *      16 dp at the edges, 56 dp at the centre. Droplet beads are
 *      brightened to #3A3A42 (1.61:1) so they read. The tallest
 *      spike is budgeted inside the row (centre dome top 56 dp +
 *      tower max 50 dp = 106 dp of the 140 dp row), so nothing
 *      clips.
 *
 * v1.1.15 — WHY 1.1.14 STILL READ AS A COMB (user's video of
 * "...Baby One More Time", MP3, frame by frame): the other four
 * styles read fine (Circular's disc + rays, Wave, Bars, Mirror),
 * but Ferrofluid was a thin tooth row at the bottom of its row —
 * no blob mass, no tower, no droplets. Measured causes: (1) the
 * silhouette filled only the bottom ~85 dp of the 140 dp row
 * (dome 56 + spike 40 max); (2) the dome bulk was still
 * 1.08–1.3:1, so only the bright spike tips showed; (3) the sqrt
 * lift saturates norms, so all 16 spikes came out the same height;
 * (4) the tower was barely taller than its neighbours; (5) the
 * droplet thresholds (peak > 0.45, drop > 0.30) almost never fired
 * with saturated norms. Fixes: dome 24/80 dp, spikes 6 + norm*50
 * (max 56), tower 12 + towerNorm*48 (max 60) with a 1.4x median
 * prominence floor, deterministic per-slot unevenness factors
 * (FERRO_SLOT_FACTOR), body gradient brightened to #5A5A63 →
 * #2A2A30 → #121215, stronger specular arc + glossy band,
 * droplet thresholds 0.35/0.18.
 *
 * v1.1.16 — WHY 1.1.15 STILL READ AS A COMB (user's video of
 * "...Baby One More Time", MP3, 22.9 s, frame by frame + pixel
 * measurement; ratios from the sRGB relative-luminance formula):
 * the 1.1.15 "brightened" gradient was still a
 * Brush.verticalGradient with NO startY/endY, so it spans the
 * whole 140 dp row while the dome only occupies its bottom 24–
 * 80 dp — the dome surface renders the gradient's middle/bottom
 * stops, not its bright top stop. Measured on the video: the dome
 * interior renders at ~#1B1B1F, 1.06:1 against #07181D — the
 * "top ~3.5:1, mid ~1.8:1" in the 1.1.15 note was the colour of
 * the gradient STOPS, not the rendered dome (the same measuring
 * mistake as 1.1.14's "2.07:1"). The spikes were the same story:
 * their brush runs #6A6A73 (tip) → #141419 (base, 1.03:1), so
 * only the bright tips + the 2 dp rim lines on both edges showed
 * — a horizontal scan through the spike zone counts ~20 thin
 * teal runs 4–6 px wide, i.e. 16 spikes × 2 rim lines each, no
 * bodies. And Circular's rays were 80% of the arc pitch (~8.2 dp
 * wide, ~1.4 dp gaps — measured: each ray spans ~10° of the
 * 11.25° pitch), so the ring read as solid petals, not the
 * demo's thin rays. Fixes, all measured, not guessed:
 *  (1) The body gradient is ANCHORED to the dome itself
 *      (startY = centre surface, endY = row bottom) and its
 *      stops brightened to #7A7A83 → #5A5A63 → #2E2E35, so the
 *      RENDERED dome is 4.27:1 at the surface, 2.66:1 at its
 *      vertical middle, ~1.9:1 at the edges — a grey liquid mass,
 *      clearly distinct from the background.
 *  (2) Spike bodies are SOLID: #8A8A93 (tip, 5.30:1) → #5A5A63 →
 *      #4A4A52 (base, 2.07:1) — no more fade to invisible black;
 *      the wide bases (already ~60–90% of the slot) now read.
 *  (3) Circular: disc 68 → 78 dp, rays 80% → 25% of pitch
 *      (~2.7 dp) with clear gaps — the demo's proportions
 *      (disc/total ≈ 0.60) at the full row height.
 *  (4) The shared lift gain 4 → 2 (Bars / Ferrofluid / Circular /
 *      Mirror) and Wave 3.5 → 2.5: the old curves saturated at
 *      level 0.25 / |x| 0.29, so loud pop read as a flat wall of
 *      equal-height bars/spikes. Row heights UNCHANGED (140 dp
 *      tall styles, 88 dp the rest — the demo stage measures
 *      ~130–144 dp, so height was never the problem).
 *  (5) Tower prominence floor 1.4x → 1.5x median; droplets
 *      brightened #3A3A42 → #6A6A73 and bigger (3 dp).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VisualizerFerrofluid(
    levels: FloatArray,
    playing: Boolean,
    freq: FerrofluidFreq = FerrofluidFreq.ALL,
    modifier: Modifier = Modifier,
    heightDp: Float = VIS_ROW_TALL_DP
) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val live = isLive(levels)
    val n = levels.size
    // v1.1.14: 16 thick spikes, not 32 thin ones — the selected band
    // range is resampled across the 16 slots below, so the blob
    // stays full whatever the frequency selector says.
    val spikeCount = FERRO_SPIKE_COUNT
    // The selected band range, resampled across all spike slots.
    val selStart = when (freq) {
        FerrofluidFreq.BASS -> 0
        FerrofluidFreq.TREBLE -> FERRO_TREBLE_START_BAND
        FerrofluidFreq.ALL -> 0
    }
    val selCount = when (freq) {
        FerrofluidFreq.BASS -> FERRO_BASS_BAND_COUNT
        FerrofluidFreq.TREBLE -> n - FERRO_TREBLE_START_BAND
        FerrofluidFreq.ALL -> n
    }
    val displayed = remember(spikeCount) { FloatArray(spikeCount) }
    val norms = remember(spikeCount) { FloatArray(spikeCount) }
    val caps = remember(spikeCount) { PeakCaps(spikeCount) }
    val droplets = remember { mutableListOf<FerroDroplet>() }
    val blobPath = remember { Path() }
    val spikePath = remember { Path() }
    val sheenPath = remember { Path() }
    val highlightPath = remember { Path() }
    // Bass energy for the centre tower: mean of bands 0..5, raw.
    var bassSum = 0f
    val bassCount = minOf(6, n)
    for (i in 0 until bassCount) bassSum += levels[i]
    val bassEnergy = if (bassCount > 0) bassSum / bassCount else 0f
    val towerNorm = if (live) scaledNorm(bassEnergy) else 0f
    // BoxWithConstraints so droplet spawn positions (in dp) are known
    // in composition, where the per-frame state lives — the Canvas
    // only reads state, never mutates it.
    // v1.1.14: tall row (140 dp) — the demo stages ferrofluid on
    // ~300 dp; at 88 dp the dome + spikes had no room to read.
    // v1.1.17 — uniform scale factor: every structural size below
    // is multiplied by `s`, so the blob/spikes/droplets grow with
    // the canvas (s = 1.0 on the 140 dp row, ~2.29 on the 320 dp
    // hero, ~5.7 full-screen) while the 140 dp look is unchanged.
    BoxWithConstraints(modifier.height(heightDp.dp).fillMaxWidth()) {
        val wDp = maxWidth.value
        val hDp = heightDp
        val s = hDp / VIS_ROW_TALL_DP
        val minPoolDp = FERRO_MIN_POOL_DP * s
        val maxPoolDp = FERRO_MAX_POOL_DP * s
        val slotW = wDp / spikeCount.coerceAtLeast(1)
        // Chase the selected (resampled) levels per slot, then lift.
        if (live) {
            for (i in 0 until spikeCount) {
                val src = (selStart + (i * selCount / spikeCount.coerceAtLeast(1))).coerceIn(0, (n - 1).coerceAtLeast(0))
                val target = levels[src]
                displayed[i] = if (target > displayed[i]) target
                else displayed[i] + (target - displayed[i]) * VIS_FERRO_RELEASE
                norms[i] = scaledNorm(displayed[i])
            }
        } else {
            displayed.fill(0f)
            norms.fill(0f)
        }
        caps.update(norms, live)
        // v1.1.15 — spike heights (dp), computed once so the draw
        // loop and the droplet spawn below agree. Spikes: 6 +
        // norm*50 (max 56 dp); tower: 12 + towerNorm*48 (max 60 dp)
        // with a 1.5x prominence floor over the median spike, so the
        // centre tower always reads as a tower. Each non-tower slot
        // gets its deterministic unevenness factor. Budget: dome
        // centre 80 dp + tower 60 dp = 140 dp = the row, so nothing
        // clips; idle heights stay small (tower 12 dp, spikes 6 dp).
        val spikeHeights = FloatArray(spikeCount)
        if (live) {
            for (i in 0 until spikeCount) {
                spikeHeights[i] = if (i == spikeCount / 2) {
                    (12f + towerNorm * 48f) * s
                } else {
                    (6f + norms[i] * 50f) * FERRO_SLOT_FACTOR[i % FERRO_SLOT_FACTOR.size] * s
                }
            }
            val sorted = spikeHeights.filterIndexed { i, _ -> i != spikeCount / 2 }.sorted()
            val median = sorted[sorted.size / 2]
            val maxOther = sorted.lastOrNull() ?: 0f
            val towerIdx = spikeCount / 2
            // v1.1.19 — the tower must be the TALLEST spike, not just
            // 1.5x the median: on a low-bass intro the median is small
            // while a few slots are already tall, so the old floor left
            // the tower shorter than its neighbours (measured on the
            // user's 1.1.18 video: tower 72 dp, slot 6 81 dp). Floor at
            // 1.3x the tallest other spike (keeping the 1.5x median
            // floor), clamped so the tip stays inside the row.
            val towerFloor = maxOf(1.5f * median, 1.3f * maxOther)
            if (spikeHeights[towerIdx] < towerFloor) spikeHeights[towerIdx] = towerFloor
            val towerMax = (hDp - maxPoolDp) * 0.92f
            if (spikeHeights[towerIdx] > towerMax) spikeHeights[towerIdx] = towerMax
        } else {
            for (i in 0 until spikeCount) spikeHeights[i] = if (i == spikeCount / 2) 12f * s else 6f * s
        }
        // Droplets: age one frame, rise, expire; spawn on sharp drops.
        if (live) {
            val it = droplets.iterator()
            while (it.hasNext()) {
                val d = it.next()
                d.age++
                d.yDp -= 2.4f * s
                if (d.age > 12) it.remove()
            }
            var spawned = 0
            for (i in 0 until spikeCount) {
                if (spawned >= 2 || droplets.size >= FERRO_MAX_DROPLETS) break
                // v1.1.15: thresholds lowered (peak > 0.35, drop >
                // 0.18) — with saturated norms the old 0.45/0.30
                // almost never fired, so no droplets ever showed.
                if (caps.peaks[i] > 0.35f && caps.peaks[i] - norms[i] > 0.18f) {
                    val cx = (i + 0.5f) * slotW
                    val spikeH = spikeHeights[i]
                    val tipY = ferroSurfaceY(cx, wDp, hDp, minPoolDp, maxPoolDp) - spikeH
                    droplets.add(FerroDroplet(cx, tipY, 0))
                    spawned++
                }
            }
        } else {
            droplets.clear()
        }
        Canvas(Modifier.fillMaxSize()) {
            val wPx = size.width
            val hPx = size.height
            val minPoolPx = minPoolDp.dp.toPx()
            val maxPoolPx = maxPoolDp.dp.toPx()
            val slotWPx = wPx / spikeCount.coerceAtLeast(1)
            // v1.1.16 — THE GRADIENT-ANCHOR FIX (the root cause of
            // three failed Ferrofluids): this brush used to be an
            // unanchored verticalGradient, so it spanned the whole
            // 140 dp row while the dome only occupies its bottom
            // 24–80 dp — the dome surface rendered the gradient's
            // middle/bottom stops (~#1B1B1F, 1.06:1 — measured on
            // the user's video), not its bright top stop. The
            // "top ~3.5:1, mid ~1.8:1" claimed above was the colour
            // of the STOPS, never the rendered dome. Now the
            // gradient is anchored to the dome itself (startY =
            // centre surface, endY = row bottom) with brighter
            // stops, so the RENDERED dome is 4.27:1 at the surface
            // (#7A7A83), 2.66:1 at its vertical middle (#5A5A63),
            // ~1.9:1 at the edges — a grey liquid mass, clearly
            // distinct from the background. Spikes still do not
            // share this brush (see below).
            val bodyBrush = Brush.verticalGradient(
                listOf(Color(0xFF7A7A83), Color(0xFF5A5A63), Color(0xFF2E2E35)),
                startY = hPx - maxPoolPx,
                endY = hPx
            )
            // The blob: one domed pool across the whole row.
            blobPath.rewind()
            blobPath.moveTo(0f, hPx)
            blobPath.lineTo(0f, ferroSurfaceY(0f, wPx, hPx, minPoolPx, maxPoolPx))
            val domeSteps = 24
            for (s in 1..domeSteps) {
                val x = s.toFloat() / domeSteps * wPx
                blobPath.lineTo(x, ferroSurfaceY(x, wPx, hPx, minPoolPx, maxPoolPx))
            }
            blobPath.lineTo(wPx, hPx)
            blobPath.close()
            drawPath(blobPath, bodyBrush)
            // Sheen: theme-gradient stroke along the dome surface.
            sheenPath.rewind()
            sheenPath.moveTo(0f, ferroSurfaceY(0f, wPx, hPx, minPoolPx, maxPoolPx))
            for (s in 1..domeSteps) {
                val x = s.toFloat() / domeSteps * wPx
                sheenPath.lineTo(x, ferroSurfaceY(x, wPx, hPx, minPoolPx, maxPoolPx))
            }
            // v1.1.14: soft glow under the rim, then the rim itself.
            // v1.1.19 — FIXED widths (were 7x / 2.5x the hero scale
            // `s`, i.e. 16 dp / 5.7 dp on the hero): a rim that thick
            // reads as a separate teal band, not a liquid edge. The
            // dome's edge is now a 1.5 dp rim + a 3 dp soft glow.
            drawPath(
                sheenPath,
                SolidColor(accent.copy(alpha = 0.25f)),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            drawPath(
                sheenPath,
                brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(wPx, 0f)),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )
            // Specular highlight: soft white arc on the upper-left of
            // the dome, just above the surface.
            highlightPath.rewind()
            val hlSteps = 8
            for (s in 0..hlSteps) {
                val x = (0.08f + 0.27f * s.toFloat() / hlSteps) * wPx
                val y = ferroSurfaceY(x, wPx, hPx, minPoolPx, maxPoolPx) - (2f * s).dp.toPx()
                if (s == 0) highlightPath.moveTo(x, y) else highlightPath.lineTo(x, y)
            }
            // v1.1.15: the old 0.20-alpha arc was too faint to sell
            // gloss on the dark blob — stronger arc plus a wide soft
            // glossy band just under the rim.
            drawPath(
                highlightPath,
                SolidColor(Color.White.copy(alpha = 0.10f)),
                style = Stroke(width = (12f * s).dp.toPx(), cap = StrokeCap.Round)
            )
            drawPath(
                highlightPath,
                SolidColor(Color.White.copy(alpha = 0.32f)),
                style = Stroke(width = (4f * s).dp.toPx(), cap = StrokeCap.Round)
            )
            // Spikes: curved flares growing out of the blob. Wide
            // base sunk 2 dp into the blob, concave sides (the
            // control points pull inward), sharp when loud (narrow
            // base, tall), blunt when quiet (wide base, short).
            val centreSlot = spikeCount / 2
            for (i in 0 until spikeCount) {
                val cx = (i + 0.5f) * slotWPx
                val surfaceY = ferroSurfaceY(cx, wPx, hPx, minPoolPx, maxPoolPx)
                val baseY = surfaceY + (2f * s).dp.toPx()
                val isTower = i == centreSlot
                // v1.1.15: heights come from spikeHeights (computed
                // above, with unevenness + tower prominence) — the
                // old inline formula (4 + norm*36, tower 10 + 40)
                // capped everything at 40 dp on a 140 dp row and made
                // all 16 spikes the same height: the comb.
                val spikeH = spikeHeights[i].dp.toPx()
                val norm = if (isTower) towerNorm else norms[i]
                // v1.1.19 — WIDER, CONVEX spikes: the old base was
                // 0.45x the slot with concave (pinched) sides, so the
                // 16 spikes read as separate sharp triangles. The base
                // is now 0.65x the slot (0.85x tower) with sides that
                // bulge OUTWARD, so neighbouring spikes merge at the
                // base into one continuous liquid surface and only
                // separate toward the tips.
                val baseHalfW = slotWPx * (if (isTower) 0.85f else 0.65f) * (1f - 0.2f * norm)
                val apex = Offset(cx, surfaceY - spikeH)
                spikePath.rewind()
                spikePath.moveTo(cx - baseHalfW, baseY)
                spikePath.quadraticBezierTo(
                    cx - baseHalfW * 1.1f, baseY - spikeH * 0.45f, apex.x, apex.y
                )
                spikePath.quadraticBezierTo(
                    cx + baseHalfW * 1.1f, baseY - spikeH * 0.45f, cx + baseHalfW, baseY
                )
                spikePath.close()
                // v1.1.14: spikes get their OWN brighter brush — a
                // v1.1.16 — SOLID spike bodies: the old gradient ran
                // #6A6A73 (tip) → #141419 (base, 1.03:1), so only
                // the bright tip + the 2 dp rim lines on both edges
                // showed — the video's "comb" is 16 spikes × 2 rim
                // lines, no bodies. Now the whole flare is bright:
                // #8A8A93 (tip, 5.30:1) → #5A5A63 → #4A4A52 (base,
                // 2.07:1). The brush still spans the spike itself,
                // so the tip is always the bright end whatever the
                // spike's height; the wide base (already ~60–90% of
                // the slot) now reads instead of vanishing.
                val spikeBrush = Brush.verticalGradient(
                    listOf(Color(0xFF8A8A93), Color(0xFF5A5A63), Color(0xFF4A4A52)),
                    startY = apex.y,
                    endY = baseY
                )
                drawPath(spikePath, spikeBrush)
                // v1.1.19 — ROUNDED APEX: a bead at the tip in the
                // same brush, so the spike ends in a liquid drop,
                // not a sharp point.
                drawCircle(spikeBrush, radius = baseHalfW * 0.3f, center = apex)
                // v1.1.19 — the teal rim lines down BOTH spike edges
                // are GONE. Measured on the user's 1.1.18 video they
                // were 4.6 dp thick at hero scale ((2f * s), s=2.29)
                // and ate 50–80% of each spike's width — the whole
                // upper spike rendered solid teal, which is why the
                // ferrofluid read as "teeth". The tip dot is now a
                // white gloss highlight on every loud spike, not a
                // teal blob.
                if (live && norm > 0.55f) {
                    if (isTower) {
                        drawCircle(Color.White.copy(alpha = 0.9f), radius = (2f * s).dp.toPx(), center = apex)
                    } else {
                        drawCircle(Color.White.copy(alpha = 0.7f), radius = (1.4f * s).dp.toPx(), center = apex)
                    }
                }
            }
            // Droplets: small glossy liquid beads above the tips.
            for (d in droplets) {
                // v1.1.16: beads bigger (3 dp, was 2.2 dp) and
                // brighter (#6A6A73, 3.39:1 — was #3A3A42, 1.61:1,
                // still too dim to notice on device).
                val r = ((3.0f - d.age * 0.1f) * s).dp.toPx().coerceAtLeast((1.0f * s).dp.toPx())
                val centre = Offset(d.xDp.dp.toPx(), d.yDp.dp.toPx())
                val alpha = (1f - d.age / 12f).coerceIn(0f, 1f)
                drawCircle(Color(0xFF6A6A73).copy(alpha = alpha), radius = r, center = centre)
                drawCircle(
                    Color.White.copy(alpha = 0.8f * alpha),
                    radius = r * 0.35f,
                    center = Offset(centre.x - r * 0.35f, centre.y - r * 0.35f)
                )
            }
        }
    }
}
