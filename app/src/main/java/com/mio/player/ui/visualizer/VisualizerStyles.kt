package com.mio.player.ui.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.mio.player.ui.components.LiveWaveform
import com.mio.player.ui.theme.LocalMioColors
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
 * style sits still at a low, fixed, index-determined shape — the same
 * rule LiveWaveform already follows.
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
 * Sizing / colours match LiveWaveform (ui/components/Common.kt) so all
 * styles are interchangeable in the same 88 dp slot on Now Playing:
 * same row height, same gain (4) for the FFT styles, same theme
 * gradient (Deep Wave teal #14B8A6 -> cyan #22D3EE; Sunset coral ->
 * orange -> amber via the theme tokens — no hardcoded colours), except
 * Ferrofluid whose liquid body is deliberately near-black in both
 * themes (that is what ferrofluid looks like) with the theme gradient
 * used only for its spike highlights / rims.
 */

// Scaling — identical to LiveWaveform's v1.1.4 calibration (see its
// KDoc for the math): only a level >= 0.25 reaches full height.
// v1.1.12: row height 112 dp -> 88 dp so the lyrics area on Now
// Playing gains 24 dp. NOTE: Bars delegates to LiveWaveform, which
// keeps its own WAVEFORM_ROW_HEIGHT_DP (112 dp) in Common.kt —
// outside this file's ownership — so Bars still renders at 112 dp
// until that constant is reduced too.
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
 *  array LiveWaveform uses, so the idle state looks identical in
 *  every style. Deterministic from the bar index only. */
private val STATIC_IDLE = floatArrayOf(
    0.0f, 0.6f, 1.0f, 0.4f, -0.3f, -0.8f, -0.2f, 0.7f,
    0.9f, 0.1f, -0.6f, -1.0f, -0.4f, 0.5f, 0.8f, 0.0f
)

/** Idle bar height in dp (18..30), from the bar index only. */
private fun idleHeightDp(i: Int): Float =
    18f + 12f * (0.5f + 0.5f * STATIC_IDLE[i % STATIC_IDLE.size])

/** Dispatcher — the single entry point for Now Playing. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun MioVisualizer(
    style: VisualizerStyle,
    levels: FloatArray,
    pcmFrame: FloatArray,
    playing: Boolean,
    modifier: Modifier = Modifier
) {
    when (style) {
        VisualizerStyle.BARS -> VisualizerBars(levels, playing, modifier)
        VisualizerStyle.WAVE -> VisualizerWave(pcmFrame, playing, modifier)
        VisualizerStyle.CIRCULAR -> VisualizerCircular(levels, playing, modifier)
        VisualizerStyle.MIRROR -> VisualizerMirror(levels, playing, modifier)
        VisualizerStyle.FERROFLUID -> VisualizerFerrofluid(levels, playing, modifier)
    }
}

/**
 * Style 1 — Bars (the current Now Playing wave, kept as the default).
 * Delegates to LiveWaveform so the default style is byte-for-byte the
 * one the user already approved: 32 gradient bars, 5 dp wide, 3 dp
 * apart. `progress` is passed as 0f — LiveWaveform
 * ignores it by design and never colours by progress. v1.1.12: the
 * other styles shrank to the 88 dp row, but LiveWaveform keeps its
 * own 112 dp constant in Common.kt (not owned by this change), so
 * Bars still renders at 112 dp until that constant is reduced too.
 */
@Composable
fun VisualizerBars(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier) {
    LiveWaveform(levels, playing, 0f, modifier)
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
        drawPath(fillPath, SolidColor(accent.copy(alpha = 0.16f)))
        drawPath(
            linePath,
            brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(w, 0f)),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Style 3 — Circular / radial bars. The 32 FFT bands radiate outwards
 * from an inner circle (first band at the top, clockwise). Bar length
 * uses Circular's own lift (v1.1.11, see below), steeper than the
 * shared scaledNorm() the Bars style uses, so the same level change
 * reads as a bigger radial move; bar colour blends along the theme
 * gradient by band index (low = first gradient stop, high = last).
 * With no signal every band is a short fixed stub.
 * The circle is sized by the row height (88 dp, v1.1.12 — was 112 dp),
 * so it fits the same slot as the other styles.
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
 */
@Composable
fun VisualizerCircular(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val divider = LocalMioColors.current.divider
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
    Canvas(modifier.height(VIS_ROW_HEIGHT_DP.dp).fillMaxWidth()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val minDim = minOf(size.width, size.height)
        // v1.1.7: small hub (was 0.30), long bars (was 0.19).
        val innerR = minDim * 0.18f
        val maxLen = minDim * 0.30f
        val midR = innerR + maxLen / 2f
        // Bar thickness: ~80% of the arc pitch at the middle radius
        // (was 60%) — the bars are now nearly contiguous.
        val barWidth = (2f * PI.toFloat() * midR / n * 0.8f).coerceAtLeast(2.dp.toPx())
        // Inner ring (the circle the bars radiate from).
        drawCircle(divider, radius = innerR - 2.dp.toPx(), center = Offset(cx, cy), style = Stroke(width = 1.dp.toPx()))
        for (i in 0 until n) {
            val len = if (live) {
                // v1.1.8: live floor 6 dp (was 4 dp) so quiet bands still
                // read as visible rays, not dots at the hub.
                // v1.1.11: length from the chased displayed value, not
                // the raw published level.
                6.dp.toPx() + displayed[i] * maxLen
            } else {
                // Idle stub: same 18..30 dp shape as Bars, scaled to the ring.
                4.dp.toPx() + (idleHeightDp(i) - VIS_MIN_HEIGHT_DP) /
                    (VIS_ROW_HEIGHT_DP - VIS_MIN_HEIGHT_DP) * maxLen * 0.5f
            }
            val angle = (-PI / 2 + 2 * PI * i / n).toFloat()
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
 */
@Composable
fun VisualizerMirror(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val live = isLive(levels)
    val n = levels.size
    Canvas(modifier.height(VIS_ROW_HEIGHT_DP.dp).fillMaxWidth()) {
        val gap = 2.dp.toPx()
        val spacing = 3.dp.toPx()
        val barW = ((size.width - (n - 1) * spacing) / n).coerceAtLeast(1.dp.toPx())
        val cy = size.height / 2f
        val maxHalf = size.height / 2f - gap
        val minHalf = VIS_MIRROR_MIN_HALF_DP.dp.toPx()
        val brush: Brush = if (live) {
            Brush.verticalGradient(colors)
        } else {
            SolidColor(accent.copy(alpha = 0.4f))
        }
        // v1.1.6: rounded horizontally (barW/2) but almost square
        // vertically (2 dp) — a fully-rounded short bar is a pill/dot.
        val radius = CornerRadius(barW / 2f, 2.dp.toPx())
        for (i in 0 until n) {
            val half = if (live) {
                // Mirror-only gain (6, not the shared 4): each half is
                // half the row, so quiet levels need the extra lift to
                // read as bars rather than dots.
                minHalf + sqrt((levels[i] * VIS_MIRROR_GAIN).coerceIn(0f, 1f)) * (maxHalf - minHalf)
            } else {
                // Idle stub: half the Bars idle height (the bar is split
                // across the centre line), still index-determined only.
                (idleHeightDp(i) / 2f).dp.toPx().coerceAtMost(maxHalf)
            }
            val x = i * (barW + spacing)
            // Upper half: from (centre - half) up to (centre - gap).
            drawRoundRect(
                brush,
                topLeft = Offset(x, cy - half),
                size = Size(barW, (half - gap).coerceAtLeast(1f)),
                cornerRadius = radius
            )
            // Lower half: mirror image below the centre line.
            drawRoundRect(
                brush,
                topLeft = Offset(x, cy + gap),
                size = Size(barW, (half - gap).coerceAtLeast(1f)),
                cornerRadius = radius
            )
        }
    }
}

/**
 * Style 5 — Ferrofluid / magnetic liquid (v1.1.12). Inspired by a
 * ferrofluid speaker (Sovenomund type): black magnetic liquid that
 * dances to the music. One liquid column per FFT band (32): a dark,
 * glossy near-black body whose height follows the band level via the
 * shared lift scaledNorm(), topped by a sharp spike — tall and
 * narrow when loud, short and blunt when quiet — with a glossy
 * highlight: a theme-gradient rim stroked along one spike edge plus
 * a wet dot at the tip. A thin dark liquid pool line at the bottom
 * joins the columns, with a 1 dp theme-gradient sheen on its top
 * edge. The liquid body stays near-black in BOTH themes (that is
 * what ferrofluid looks like); only the spike rims / pool sheen use
 * the theme gradient, so it reads on the Deep Wave dark background
 * and on the Sunset light background alike. With no signal the
 * columns sit low and still at the index-determined idle bumps
 * (idleHeightDp) with tiny blunt spikes — the same idle rule as the
 * other styles. Driven ONLY by the real FFT levels: no clock, no
 * time-based animation, no progress colouring. Cheap by design: one
 * shared body brush, one reused spike Path (rewound per band), plain
 * rects / lines — no per-frame allocations beyond what Wave already
 * does. The tallest spike is budgeted inside the row (max body
 * height leaves max spike height + 2 dp headroom), so nothing clips
 * at the top edge.
 */
@Composable
fun VisualizerFerrofluid(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalMioColors.current.gradientColors
    val live = isLive(levels)
    val n = levels.size
    Canvas(modifier.height(VIS_ROW_HEIGHT_DP.dp).fillMaxWidth()) {
        val spacing = 3.dp.toPx()
        val barW = ((size.width - (n - 1) * spacing) / n).coerceAtLeast(1.dp.toPx())
        val poolH = 4.dp.toPx()
        val baseline = size.height - poolH
        val maxSpikeH = 23.dp.toPx() // 3 dp + 20 dp at full level
        val maxBody = baseline - maxSpikeH - 2.dp.toPx()
        val minBody = VIS_MIN_HEIGHT_DP.dp.toPx()
        // One vertical brush for every body: glossy near-black, light
        // at the top fading to black at the pool.
        val bodyBrush = Brush.verticalGradient(
            listOf(Color(0xFF26262B), Color(0xFF0B0B0D), Color(0xFF050506))
        )
        val spikeFill = SolidColor(Color(0xFF17171A))
        val spikePath = Path()
        for (i in 0 until n) {
            val norm = if (live) scaledNorm(levels[i]) else 0f
            val bodyH = if (live) {
                minBody + norm * (maxBody - minBody)
            } else {
                // Idle bump: same 18..30 dp index-determined shape as
                // the other styles, capped to the budgeted max body.
                idleHeightDp(i).dp.toPx().coerceAtMost(maxBody)
            }
            val x = i * (barW + spacing)
            val cx = x + barW / 2f
            val top = baseline - bodyH
            drawRoundRect(
                bodyBrush,
                topLeft = Offset(x, top),
                size = Size(barW, bodyH),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
            // Spike: tall + narrow (sharp) when loud, short + wide
            // (blunt) when quiet. Base sinks 2 dp into the body so
            // there is never a visible seam between spike and body.
            val spikeH = if (live) 3.dp.toPx() + norm * 20.dp.toPx() else 2.dp.toPx()
            val baseHalfW = barW / 2f * (1f - 0.5f * norm)
            val apex = Offset(cx, top - spikeH)
            val baseY = top + 2.dp.toPx()
            spikePath.rewind()
            spikePath.moveTo(apex.x, apex.y)
            spikePath.lineTo(cx + baseHalfW, baseY)
            spikePath.lineTo(cx - baseHalfW, baseY)
            spikePath.close()
            drawPath(spikePath, spikeFill)
            // Glossy highlight: theme-gradient rim down one spike
            // edge, plus a wet dot at the tip on loud bands.
            val rimColor = lerp(colors.first(), colors.last(), i.toFloat() / (n - 1).coerceAtLeast(1))
            drawLine(
                rimColor,
                apex,
                Offset(cx + baseHalfW, baseY),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
            if (live && norm > 0.05f) {
                drawCircle(rimColor, radius = 1.2.dp.toPx(), center = apex)
            }
        }
        // Liquid pool: thin dark line joining the columns, with a
        // 1 dp theme-gradient sheen along its top edge.
        drawRoundRect(
            Brush.verticalGradient(listOf(Color(0xFF1A1A1F), Color(0xFF050506))),
            topLeft = Offset(0f, baseline),
            size = Size(size.width, poolH),
            cornerRadius = CornerRadius(poolH / 2f, poolH / 2f)
        )
        drawLine(
            Brush.linearGradient(colors, start = Offset.Zero, end = Offset(size.width, 0f)),
            Offset(0f, baseline),
            Offset(size.width, baseline),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}
