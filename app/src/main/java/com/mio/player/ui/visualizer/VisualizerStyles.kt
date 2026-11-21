package com.mio.player.ui.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v1.1.5 — the four visualizer styles, each a self-contained
 * @Composable driven ONLY by real audio data:
 *  - Bars / Circular / Mirror read `levels` (the 32 log-spaced FFT band
 *    magnitudes, 20 Hz–12 kHz, from WaveformTap).
 *  - Wave reads `pcmFrame` (the newest 1024 mono PCM samples, also from
 *    WaveformTap, published with each FFT frame at ~10 Hz).
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
 * (~10 Hz); between frames the line is simply redrawn from the same
 * real samples — nothing is interpolated or animated. With no signal
 * the line is a flat, still centre line.
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
        val innerR = minDim * 0.30f
        val maxLen = minDim * 0.19f
        val midR = innerR + maxLen / 2f
        // Bar thickness: ~60% of the arc pitch at the middle radius.
        val barWidth = (2f * PI.toFloat() * midR / n * 0.6f).coerceAtLeast(2.dp.toPx())
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
 * the layout is mirrored. With no signal both halves are short fixed
 * stubs, symmetric by construction.
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
        val minHalf = 5.dp.toPx()
        val brush: Brush = if (live) {
            Brush.verticalGradient(colors)
        } else {
            SolidColor(accent.copy(alpha = 0.4f))
        }
        val radius = CornerRadius(barW / 2f, barW / 2f)
        for (i in 0 until n) {
            val half = if (live) {
                minHalf + scaledNorm(levels[i]) * (maxHalf - minHalf)
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
