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
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v1.1.5 — the five visualizer styles, each a self-contained
 * @Composable driven ONLY by real audio data:
 *  - Bars / Circular / Mirror / Ferrofluid read `levels` (the 32
 *    log-spaced FFT band magnitudes, 20 Hz–12 kHz, from WaveformTap).
 *  - Wave reads `pcmFrame` (the newest 1024 mono PCM samples, also from
 *    WaveformTap, published with each FFT frame at ~30 Hz, v1.1.6).
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
 * v1.1.13 — Bars is now native in this file: LiveWaveform
 * (ui/components/Common.kt) is deleted — it was only ever used by
 * Bars, verified by grep — so Bars, Mirror and Ferrofluid can share
 * the peak-cap / chase helpers here with no dead code left behind.
 * Every style is reworked in this release, each with its own
 * "v1.1.13 — ..." note: Bars gets Monstercat-style peak caps,
 * Wave gets a soft glow, Circular rings the album art, Mirror gets
 * caps + a bright centre line + a vertically mirrored gradient, and
 * Ferrofluid is redrawn as one glossy liquid blob (the v1.1.12
 * columns read as cheap thin bars — the user called it "phèn") with
 * a frequency selector (Bass / Tất cả / Treble, see
 * [FerrofluidFreq]).
 *
 * Sizing / colours: all styles share the same 88 dp slot, the same
 * gain (4) for the FFT styles, and the same theme gradient (Deep
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
private const val VIS_ROW_HEIGHT_DP = 88f
private const val VIS_MIN_HEIGHT_DP = 10f
private const val VIS_GAIN = 4f
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
private const val VIS_MIRROR_GAIN = 6f
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
private const val VIS_WAVE_GAIN = 2.5f

// v1.1.13 — peak caps (Bars / Mirror / Ferrofluid): a cap holds its
// height for ~12 published frames (~0.4 s at ~30 Hz) after a new
// peak, then falls 0.02 of full height per published frame
// (~1.7 s for a full fall). Updated per published FFT frame only —
// no clock, no animation.
private const val PEAK_HOLD_FRAMES = 12
private const val PEAK_FALL_PER_FRAME = 0.02f
// v1.1.13 — chase (displayed-value) release per published frame:
// Bars 0.30 and Mirror 0.35 fall to ~10% of a step in ~7 frames
// (~0.25 s); attack is always instant. Ferrofluid is more viscous
// (0.50) so the liquid lags the beat slightly instead of snapping.
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
private const val FERRO_MIN_POOL_DP = 13f
private const val FERRO_MAX_POOL_DP = 38f
private const val FERRO_MAX_DROPLETS = 8

private fun waveLift(sample: Float): Float {
    val s = (sample * VIS_WAVE_GAIN).coerceIn(-1f, 1f)
    return if (s >= 0f) sqrt(s) else -sqrt(-s)
}

/** Perceptual lift of one raw FFT band level, 0..1. */
private fun scaledNorm(level: Float): Float =
    sqrt((level * VIS_GAIN).coerceIn(0f, 1f))

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
 * Position is in dp of the 88 dp row; `age` counts published FFT
 * frames since detachment — no clock. The droplet rises ~2.4 dp per
 * frame and is removed after 12 frames.
 */
private class FerroDroplet(var xDp: Float, var yDp: Float, var age: Int)

/** Dispatcher — the single entry point for Now Playing. */
@Composable
fun MioVisualizer(
    style: VisualizerStyle,
    levels: FloatArray,
    pcmFrame: FloatArray,
    playing: Boolean,
    albumId: Long = 0L,
    coverPath: String? = null,
    ferrofluidFreq: FerrofluidFreq = FerrofluidFreq.ALL,
    modifier: Modifier = Modifier
) {
    when (style) {
        VisualizerStyle.BARS -> VisualizerBars(levels, playing, modifier)
        VisualizerStyle.WAVE -> VisualizerWave(pcmFrame, playing, modifier)
        VisualizerStyle.CIRCULAR -> VisualizerCircular(levels, playing, albumId, coverPath, modifier)
        VisualizerStyle.MIRROR -> VisualizerMirror(levels, playing, modifier)
        VisualizerStyle.FERROFLUID -> VisualizerFerrofluid(levels, playing, ferrofluidFreq, modifier)
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
fun VisualizerBars(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier) {
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
    Canvas(modifier.height(VIS_ROW_HEIGHT_DP.dp).fillMaxWidth()) {
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
            if (live && caps.peaks[i] > 0.03f) {
                val peakH = minH + caps.peaks[i] * (maxH - minH)
                val capY = (bottom - peakH - 5.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(
                    SolidColor(accent),
                    topLeft = Offset(x, capY),
                    size = Size(barW, 2.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                )
            }
        }
    }
}

/**
 * Style 2 — Wave / oscilloscope line. Draws the raw mono PCM frame
 * (1024 samples) as one continuous polyline, stroked with the theme
 * gradient and a faint fill underneath. The frame refreshes at the
 * FFT cadence (~30 Hz, v1.1.6 — was ~10 Hz); between frames the line
 * is simply redrawn from the same real samples — nothing is
 * interpolated or animated, deliberately: interpolation would invent
 * samples that were never played, and at 30 Hz fresh real samples
 * arrive every 33 ms, so the higher rate alone makes the line move
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
 */
@Composable
fun VisualizerWave(pcmFrame: FloatArray, playing: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val divider = LocalMioColors.current.divider
    // |x| > 0.02 anywhere in the frame counts as signal; a frame of
    // near-silence (leading/trailing) is a flat line, not noise.
    val hasSignal = playing && pcmFrame.any { it * it > 0.0004f }
    Canvas(modifier.height(VIS_ROW_HEIGHT_DP.dp).fillMaxWidth()) {
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
        // v1.1.13: fill brightened 0.16 -> 0.22 alpha.
        drawPath(fillPath, SolidColor(accent.copy(alpha = 0.22f)))
        // v1.1.13: soft glow — a wide low-alpha stroke underneath
        // the thin full-alpha gradient stroke on top.
        drawPath(
            linePath,
            SolidColor(accent.copy(alpha = 0.22f)),
            style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
        )
        drawPath(
            linePath,
            brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(w, 0f)),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
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
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VisualizerCircular(
    levels: FloatArray,
    playing: Boolean,
    albumId: Long = 0L,
    coverPath: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val live = isLive(levels)
    val n = levels.size
    // v1.1.11 — per-bar displayed lengths (Circular-only constants,
    // kept local so no shared constant is touched). Updated here, in
    // composition, from the newest published levels only — no clock,
    // no animation: a rising target is taken instantly, a falling
    // target is chased at 0.75/frame, so the bars hug the published
    // levels within ~1 frame instead of adding a second layer of
    // smoothing on top of WaveformTap's. When idle the chase state is
    // dropped, so the next live frame attacks instantly from zero
    // instead of decaying from stale bar lengths.
    val liftGain = 4f // same gain as the shared sqrt lift; only the exponent changes
    val liftPower = 0.75f
    val releaseWeight = 0.75f
    val displayed = remember(n) { FloatArray(n) }
    if (live) {
        for (i in 0 until n) {
            val target = ((levels[i] * liftGain).coerceIn(0f, 1f)).pow(liftPower)
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
    Box(modifier.height(VIS_ROW_HEIGHT_DP.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val minDim = minOf(size.width, size.height)
            // v1.1.13: the hub is the 44 dp art disc — innerR is the
            // disc radius (22 dp) + a 3 dp gap, and the radial budget
            // is what is left to the slot edge (17 dp on the 88 dp
            // slot), so bar tips can never clip.
            val discR = 22.dp.toPx()
            val innerR = discR + 3.dp.toPx()
            val budget = (minDim / 2f - innerR - 2.dp.toPx()).coerceAtLeast(4.dp.toPx())
            val midR = innerR + budget / 2f
            // Bar thickness: ~80% of the arc pitch at the middle radius
            // — the bars are nearly contiguous.
            val barWidth = (2f * PI.toFloat() * midR / n.coerceAtLeast(1) * 0.8f).coerceAtLeast(2.dp.toPx())
            // Thin accent ring on the disc edge (the old divider inner
            // ring is gone — the disc is the hub now).
            drawCircle(accent.copy(alpha = 0.45f), radius = discR, center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
            for (i in 0 until n) {
                val len = if (live) {
                    // v1.1.8: live floor so quiet bands still read as
                    // visible rays, not dots at the hub; v1.1.13: the
                    // floor + swing are budgeted inside `budget`, so
                    // the tip never passes the slot edge.
                    4.dp.toPx() + displayed[i] * (budget - 4.dp.toPx())
                } else {
                    // Idle stub: same 18..30 dp shape as Bars, scaled to the ring.
                    3.dp.toPx() + (idleHeightDp(i) - VIS_MIN_HEIGHT_DP) /
                        (VIS_ROW_HEIGHT_DP - VIS_MIN_HEIGHT_DP) * budget * 0.4f
                }
                val angle = (-PI / 2 + 2 * PI * i / n.coerceAtLeast(1)).toFloat()
                val dir = Offset(cos(angle), sin(angle))
                val start = Offset(cx + dir.x * innerR, cy + dir.y * innerR)
                val end = Offset(cx + dir.x * (innerR + len), cy + dir.y * (innerR + len))
                val color: Color = if (live) {
                    lerp(colors.first(), colors.last(), i.toFloat() / (n - 1).coerceAtLeast(1))
                } else {
                    accent.copy(alpha = 0.4f)
                }
                drawLine(color, start, end, strokeWidth = barWidth, cap = StrokeCap.Round)
            }
        }
        // v1.1.13: centre disc — real art when there is any, else a
        // theme-gradient disc with a white note (never blank).
        if (hasArt) {
            AlbumArt(albumId, Modifier.size(44.dp), CircleShape, coverPath = coverPath)
        } else {
            Box(
                Modifier.size(44.dp).mioGradientBackground(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(22.dp))
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
fun VisualizerMirror(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier) {
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
    Canvas(modifier.height(VIS_ROW_HEIGHT_DP.dp).fillMaxWidth()) {
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
            if (live && caps.peaks[i] > 0.03f) {
                val peakHalf = minHalf + caps.peaks[i] * (maxHalf - minHalf)
                val capH = 2.dp.toPx()
                val capRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                drawRoundRect(
                    SolidColor(accent),
                    topLeft = Offset(x, (cy - peakHalf - gap - capH).coerceAtLeast(0f)),
                    size = Size(barW, capH),
                    cornerRadius = capRadius
                )
                drawRoundRect(
                    SolidColor(accent),
                    topLeft = Offset(x, cy + peakHalf + gap),
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
 * spike is budgeted inside the row (centre dome top 38 dp + tower
 * max 34 dp = 72 dp of the 88 dp row), so nothing clips at the top
 * edge.
 *
 * Frequency selector (Dakd Jung's dial, as a segmented control —
 * see [VisualizerPanel], which shows it only while Ferrofluid is
 * active): Bass = spikes follow the low bands only (0..11,
 * ~20–220 Hz), Tất cả = the full spectrum (0..31), Treble = the
 * high bands only (23..31, ~2–12 kHz). The selected range is
 * resampled across all 32 spike slots, so the blob stays full
 * whatever the range; the centre tower always follows bass.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VisualizerFerrofluid(
    levels: FloatArray,
    playing: Boolean,
    freq: FerrofluidFreq = FerrofluidFreq.ALL,
    modifier: Modifier = Modifier
) {
    val colors = LocalMioColors.current.gradientColors
    val live = isLive(levels)
    val n = levels.size
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
    val displayed = remember(n) { FloatArray(n) }
    val norms = remember(n) { FloatArray(n) }
    val caps = remember(n) { PeakCaps(n) }
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
    BoxWithConstraints(modifier.height(VIS_ROW_HEIGHT_DP.dp).fillMaxWidth()) {
        val wDp = maxWidth.value
        val hDp = VIS_ROW_HEIGHT_DP
        val slotW = wDp / n.coerceAtLeast(1)
        // Chase the selected (resampled) levels per slot, then lift.
        if (live) {
            for (i in 0 until n) {
                val src = (selStart + (i * selCount / n.coerceAtLeast(1))).coerceIn(0, (n - 1).coerceAtLeast(0))
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
        // Droplets: age one frame, rise, expire; spawn on sharp drops.
        if (live) {
            val it = droplets.iterator()
            while (it.hasNext()) {
                val d = it.next()
                d.age++
                d.yDp -= 2.4f
                if (d.age > 12) it.remove()
            }
            var spawned = 0
            for (i in 0 until n) {
                if (spawned >= 2 || droplets.size >= FERRO_MAX_DROPLETS) break
                if (caps.peaks[i] > 0.45f && caps.peaks[i] - norms[i] > 0.30f) {
                    val cx = (i + 0.5f) * slotW
                    val spikeH = if (i == n / 2) 8f + towerNorm * 26f else 3f + norms[i] * 24f
                    val tipY = ferroSurfaceY(cx, wDp, hDp, FERRO_MIN_POOL_DP, FERRO_MAX_POOL_DP) - spikeH
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
            val minPoolPx = FERRO_MIN_POOL_DP.dp.toPx()
            val maxPoolPx = FERRO_MAX_POOL_DP.dp.toPx()
            val slotWPx = wPx / n.coerceAtLeast(1)
            // One vertical brush for blob AND spikes: the same
            // near-black gradient across both, so the spike bases
            // blend into the blob with no visible seam.
            val bodyBrush = Brush.verticalGradient(
                listOf(Color(0xFF2E2E33), Color(0xFF0B0B0D), Color(0xFF050506))
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
                val y = ferroSurfaceY(x, wPx, hPx, minPoolPx, maxPoolPx) - 2.dp.toPx()
                if (s == 0) highlightPath.moveTo(x, y) else highlightPath.lineTo(x, y)
            }
            drawPath(
                highlightPath,
                SolidColor(Color.White.copy(alpha = 0.20f)),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
            // Spikes: curved flares growing out of the blob. Wide
            // base sunk 2 dp into the blob, concave sides (the
            // control points pull inward), sharp when loud (narrow
            // base, tall), blunt when quiet (wide base, short).
            val centreSlot = n / 2
            for (i in 0 until n) {
                val cx = (i + 0.5f) * slotWPx
                val surfaceY = ferroSurfaceY(cx, wPx, hPx, minPoolPx, maxPoolPx)
                val baseY = surfaceY + 2.dp.toPx()
                val isTower = i == centreSlot
                val spikeH = if (live) {
                    if (isTower) (8f + towerNorm * 26f).dp.toPx() else (3f + norms[i] * 24f).dp.toPx()
                } else {
                    if (isTower) 8.dp.toPx() else 2.dp.toPx()
                }
                val norm = if (isTower) towerNorm else norms[i]
                val baseHalfW = slotWPx * (if (isTower) 0.8f else 0.45f) * (1f - 0.3f * norm)
                val apex = Offset(cx, surfaceY - spikeH)
                spikePath.rewind()
                spikePath.moveTo(cx - baseHalfW, baseY)
                spikePath.quadraticBezierTo(
                    cx - baseHalfW * 0.45f, baseY - spikeH * 0.6f, apex.x, apex.y
                )
                spikePath.quadraticBezierTo(
                    cx + baseHalfW * 0.45f, baseY - spikeH * 0.6f, cx + baseHalfW, baseY
                )
                spikePath.close()
                drawPath(spikePath, bodyBrush)
                // Glossy rim down one spike edge + a bright tip dot
                // on loud spikes (white on the tower tip).
                val rimColor = lerp(colors.first(), colors.last(), i.toFloat() / (n - 1).coerceAtLeast(1))
                drawLine(
                    rimColor,
                    apex,
                    Offset(cx + baseHalfW, baseY),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                if (live && norm > 0.55f) {
                    if (isTower) {
                        drawCircle(Color.White.copy(alpha = 0.9f), radius = 2.dp.toPx(), center = apex)
                    } else {
                        drawCircle(rimColor, radius = 1.4.dp.toPx(), center = apex)
                    }
                }
            }
            // Droplets: small glossy liquid beads above the tips.
            for (d in droplets) {
                val r = (2.2f - d.age * 0.08f).dp.toPx().coerceAtLeast(0.8.dp.toPx())
                val centre = Offset(d.xDp.dp.toPx(), d.yDp.dp.toPx())
                val alpha = (1f - d.age / 12f).coerceIn(0f, 1f)
                drawCircle(Color(0xFF1B1B1F).copy(alpha = alpha), radius = r, center = centre)
                drawCircle(
                    Color.White.copy(alpha = 0.8f * alpha),
                    radius = r * 0.35f,
                    center = Offset(centre.x - r * 0.35f, centre.y - r * 0.35f)
                )
            }
        }
    }
}
