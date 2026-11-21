package com.mio.player.ui.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v1.1.5 — the five visualizer styles, each a self-contained
 * @Composable driven ONLY by real audio data:
 *  - Bars / Circular / Mirror / Droplet read `levels` (the 32 log-spaced FFT band
 *    magnitudes, 20 Hz–12 kHz, from WaveformTap).
 *  - Wave reads `pcmFrame` (the newest 1024 mono PCM samples, also from
 *    WaveformTap, published with each FFT frame at ~30 Hz, v1.1.6).
 * Nothing here depends on time, playback position, duration or
 * progress. When there is no live signal (paused / silence) every
 * style sits still at a low, fixed, index-determined shape — the same
 * rule LiveWaveform already follows.
 *
 * Sizing / colours match LiveWaveform (ui/components/Common.kt) so all
 * styles are interchangeable in the same 112 dp slot on Now Playing:
 * same row height, same gain (4), same theme gradient (Deep Wave teal
 * #14B8A6 -> cyan #22D3EE; Sunset coral -> orange -> amber via the
 * theme tokens — no hardcoded colours).
 */

// Scaling — identical to LiveWaveform's v1.1.4 calibration (see its
// KDoc for the math): only a level >= 0.25 reaches full height.
private const val VIS_ROW_HEIGHT_DP = 112f
private const val VIS_MIN_HEIGHT_DP = 10f
private const val VIS_GAIN = 4f
private const val VIS_LIVE_THRESHOLD = 0.005f
// v1.1.6 — Mirror-only lift: the mirrored halves are each half the row,
// so the same level reads half as tall as in Bars, and at levels
// ~0.04-0.05 the halves were short rounded pills that read as two rows
// of static dots. Mirror therefore uses a higher gain (6 vs 4) and a
// taller minimum half (10 dp vs 5 dp) so quiet bars still read as bars.
// At level 0.04: sqrt(0.04*6) = 0.49 (was 0.40); at 0.01: 0.245 (was
// 0.20); the minimum visible half is 10 - 2 (gap) = 8 dp per side,
// 16 dp total, instead of 3 dp per side / 6 dp total.
private const val VIS_MIRROR_GAIN = 6f
private const val VIS_MIRROR_MIN_HALF_DP = 10f

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
        VisualizerStyle.DROPLET -> VisualizerDroplet(levels, pcmFrame, playing, modifier)
    }
}

/**
 * Style 1 — Bars (the current Now Playing wave, kept as the default).
 * Delegates to LiveWaveform so the default style is byte-for-byte the
 * one the user already approved: 32 gradient bars, 5 dp wide, 3 dp
 * apart, on the 112 dp row. `progress` is passed as 0f — LiveWaveform
 * ignores it by design and never colours by progress.
 */
@Composable
fun VisualizerBars(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier) {
    LiveWaveform(levels, playing, 0f, modifier)
}

/**
 * Style 2 — Wave / oscilloscope line. Draws the raw mono PCM frame
 * (1024 samples) as one continuous polyline, linear amplitude
 * (sample -1..1 maps to the full row height, no gain fudge — a quiet
 * passage honestly looks small), stroked with the theme gradient and
 * a faint fill underneath. The frame refreshes at the FFT cadence
 * (~30 Hz, v1.1.6 — was ~10 Hz); between frames the line is simply
 * redrawn from the same real samples — nothing is interpolated or
 * animated, deliberately: interpolation would invent samples that
 * were never played, and at 30 Hz fresh real samples arrive every
 * 33 ms, so the higher rate alone makes the line move smoothly.
 * With no signal the line is a flat, still centre line.
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
            val y = cy - pcmFrame[idx].coerceIn(-1f, 1f) * amp
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
        drawPath(fillPath, SolidColor(accent.copy(alpha = 0.10f)))
        drawPath(
            linePath,
            brush = Brush.linearGradient(colors, start = Offset.Zero, end = Offset(w, 0f)),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * Style 3 — Circular / radial bars. The 32 FFT bands radiate outwards
 * from an inner circle (first band at the top, clockwise). Bar length
 * is the same scaledNorm() as the Bars style, so a loud bass band
 * reaches the same relative length in both styles; bar colour blends
 * along the theme gradient by band index (low = first gradient stop,
 * high = last). With no signal every band is a short fixed stub.
 * The circle is sized by the row height (112 dp), so it fits the same
 * slot as the other styles.
 *
 * v1.1.7 redesign (user asked for a smaller ring, bigger bars): the
 * inner circle shrank from 0.30 to 0.18 × minDim, the bars grew from
 * 0.19 to 0.30 × minDim and thickened from 60% to 80% of the arc
 * pitch — the ring now reads as a small hub with long, chunky rays.
 * At full scale the bar tip just touches the slot edge (the same
 * class of edge-touch the old geometry already had at max level).
 */
@Composable
fun VisualizerCircular(levels: FloatArray, playing: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val divider = LocalMioColors.current.divider
    val live = isLive(levels)
    val n = levels.size
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
                4.dp.toPx() + scaledNorm(levels[i]) * maxLen
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
 * (VIS_MIRROR_GAIN = 6) and taller minimum half (10 dp), and the
 * corner radius is rounded only horizontally (2 dp vertically) — the
 * old fully-rounded pills at low levels read as two rows of static
 * dots; short Mirror bars now read as flat-topped bars. With no
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

/** Teardrop path: a circle of radius [r] centred at ([cx], [cy]) with
 *  a pointed tip 2r above the centre. The tip is drawn with two
 *  cubic curves from the circle's equator; the round bottom is the
 *  lower half of the circle (arc from 0° to 180°, which passes
 *  through the bottom in Android's y-down coordinates). */
private fun dropletPath(cx: Float, cy: Float, r: Float): Path = Path().apply {
    moveTo(cx, cy - 2.0f * r) // pointed tip
    // Right flank: tip -> equator (3 o'clock).
    cubicTo(cx + 1.15f * r, cy - 1.05f * r, cx + r, cy - 0.35f * r, cx + r, cy)
    // Round bottom: equator -> 6 o'clock -> equator (9 o'clock).
    arcTo(Rect(cx - r, cy - r, cx + r, cy + r), 0f, 180f, false)
    // Left flank: equator -> tip.
    cubicTo(cx - r, cy - 0.35f * r, cx - 1.15f * r, cy - 1.05f * r, cx, cy - 2.0f * r)
    close()
}

/**
 * Style 5 — Droplet / water drops (v1.1.7, new at the user's request:
 * "làm dạng giọt nước chuyển động cho đẹp"). Sixteen teardrops in a
 * row above a faint baseline; each drop averages one adjacent pair
 * of the 32 FFT bands, so neighbouring drops stay distinct instead of
 * overlapping. A drop's SIZE (radius 3.5–8.5 dp) and POSITION (it
 * rises up to 30 dp off the baseline) are both driven by its band's
 * live level through the same scaledNorm() as the other styles, so
 * the drops visibly swell and bob with the music — bass drops jump
 * high, quiet ones sit low and small. Colour blends along the theme
 * gradient by drop index, with a small white gloss dot for a wet
 * look. `pcmFrame` is accepted for dispatcher symmetry with the
 * other styles but not used — the drops are FFT-driven.
 * No time-based animation: when paused / silent every drop sits
 * still at a low fixed size and lift from the shared idle array,
 * exactly like the other styles. Same 112 dp slot.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun VisualizerDroplet(
    levels: FloatArray,
    pcmFrame: FloatArray,
    playing: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalMioColors.current.gradientColors
    val accent = LocalMioColors.current.accent
    val divider = LocalMioColors.current.divider
    val live = isLive(levels)
    val n = levels.size
    val drops = 16
    Canvas(modifier.height(VIS_ROW_HEIGHT_DP.dp).fillMaxWidth()) {
        val w = size.width
        val h = size.height
        val baseY = h - 12.dp.toPx()
        val minR = 3.5f.dp.toPx()
        val maxR = 8.5f.dp.toPx()
        val maxLift = 30.dp.toPx()
        // Faint baseline the droplets rise from.
        drawLine(divider, Offset(0f, baseY), Offset(w, baseY), strokeWidth = 1.dp.toPx())
        val step = w / drops
        for (d in 0 until drops) {
            // Live level: average of this drop's two FFT bands.
            // Idle level: fixed from the shared idle array (0.05..0.45).
            val norm = if (live) {
                val a = levels[(2 * d).coerceAtMost(n - 1)]
                val b = levels[(2 * d + 1).coerceAtMost(n - 1)]
                scaledNorm((a + b) / 2f)
            } else {
                0.25f + 0.20f * STATIC_IDLE[d % STATIC_IDLE.size]
            }
            val r = minR + norm * (maxR - minR)
            // The drop rises with its level; idle drops hover just
            // above the baseline (half lift) so the row never looks dead.
            val lift = if (live) norm * maxLift else norm * maxLift * 0.5f
            val cx = step * (d + 0.5f)
            val cy = baseY - r - lift
            val color: Color = if (live) {
                lerp(colors.first(), colors.last(), d.toFloat() / (drops - 1).coerceAtLeast(1))
            } else {
                accent.copy(alpha = 0.4f)
            }
            drawPath(dropletPath(cx, cy, r), SolidColor(color))
            // Gloss dot — a small white highlight near the upper-left
            // of the drop, so it reads as water, not a flat blob.
            drawCircle(
                Color.White.copy(alpha = 0.22f),
                radius = r * 0.28f,
                center = Offset(cx - r * 0.35f, cy - r * 0.45f)
            )
        }
    }
}
