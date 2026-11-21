package com.mio.player.ui.visualizer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mio.player.MioApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v1.1.5 — style picker + the one-call panel for Now Playing.
 *
 * v1.1.12 — the chip row is GONE at the user's request (the
 * [VisualizerStylePicker] chip-row composable is deleted; it was only
 * ever used by [VisualizerPanel], verified by grep — no other call
 * site existed). Switching now happens on the visualizer area itself:
 * tap cycles to the next style, swipe left / right goes to the next /
 * previous style, and a small pill with the current style name shows
 * briefly (~1.5 s) after each switch. The choice is still persisted
 * in DataStore under the same key ([VisualizerStyle.KEY],
 * "visualizer_style") exactly as before.
 *
 * [VisualizerPanel] is the one-call version for Now Playing: it reads
 * the saved style from DataStore (same "mio_settings" store as every
 * other setting), renders the visualizer, and saves the choice when
 * the user taps or swipes — the coordinator only passes the live
 * audio data.
 */

/**
 * One-call integration for Now Playing (the coordinator's drop-in):
 * visualizer with tap / swipe style switching, with the style loaded
 * from and saved to DataStore automatically. Replaces the bare
 * `LiveWaveform(...)` call.
 *
 * Gestures (scoped to the visualizer area only, so the slider /
 * transport below are untouched):
 *  - TAP the visualizer -> next style ([VisualizerStyle.next()],
 *    wraps around).
 *  - SWIPE LEFT -> next style; SWIPE RIGHT -> previous style
 *    ([VisualizerStyle.previous()]). A swipe only counts past a
 *    24 dp horizontal offset, so small accidental drags do nothing.
 *  - After a switch a small pill with the new style's label appears
 *    centred over the visualizer for ~1.5 s, then hides itself
 *    (state + [LaunchedEffect] delay — UI chrome only, not audio).
 * Tap and swipe coexist as two separate `pointerInput` modifiers:
 * a tap produces no drag, so the drag detector ignores it; a drag
 * cancels the tap detector, so a swipe never also fires a tap — tap
 * still works after a swipe and vice versa.
 *
 * @param levels live FFT band magnitudes (`vm.pm.waveform.levels`)
 * @param pcmFrame live mono PCM frame (`vm.pm.waveform.pcmFrame`)
 * @param playing whether audio is actually playing
 */
@Composable
fun VisualizerPanel(
    levels: FloatArray,
    pcmFrame: FloatArray,
    playing: Boolean,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val settings = remember(ctx) { (ctx.applicationContext as MioApp).container.settings }
    val scope = rememberCoroutineScope()
    val styleId by settings.flow(VisualizerStyle.KEY, VisualizerStyle.BARS.id)
        .collectAsState(initial = VisualizerStyle.BARS.id)
    val style = VisualizerStyle.fromId(styleId)

    // Style-name pill: shown briefly after a switch. The first
    // composition (initial load) does not flash the pill.
    var showLabel by remember { mutableStateOf(false) }
    var firstSeen by remember { mutableStateOf(true) }
    LaunchedEffect(style) {
        if (firstSeen) {
            firstSeen = false
            return@LaunchedEffect
        }
        showLabel = true
        delay(1500)
        showLabel = false
    }

    fun switchTo(chosen: VisualizerStyle) {
        if (chosen == style) return
        scope.launch {
            runCatching { settings.set(VisualizerStyle.KEY, chosen.id) }
        }
    }

    Box(
        modifier
            .pointerInput(style) {
                detectTapGestures(onTap = { switchTo(style.next()) })
            }
            .pointerInput(style) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val threshold = 24.dp.toPx()
                        if (totalDrag < -threshold) {
                            switchTo(style.next())
                        } else if (totalDrag > threshold) {
                            switchTo(style.previous())
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                    onHorizontalDrag = { _, dragAmount -> totalDrag += dragAmount }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        MioVisualizer(style, levels, pcmFrame, playing, Modifier.fillMaxWidth())
        if (showLabel) {
            Box(
                Modifier
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    style.label,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
