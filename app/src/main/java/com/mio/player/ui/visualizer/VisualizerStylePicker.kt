package com.mio.player.ui.visualizer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.mio.player.ui.theme.LocalMioColors
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
 * v1.1.13 — two additions, both additive to the panel contract:
 *  - The panel now takes the current track's `albumId` / `coverPath`
 *    and passes them to [MioVisualizer], so the Circular style can
 *    ring the album art (see VisualizerStyles.kt). Other styles
 *    ignore them.
 *  - While Ferrofluid is the active style, a small segmented control
 *    (Bass / Tất cả / Treble) overlays the top-right of the
 *    visualizer — the frequency selector for the ferrofluid spikes
 *    (see [FerrofluidFreq]). The choice is persisted in DataStore
 *    under [VisualizerStyle.KEY_FERROFLUID_FREQ] ("ferrofluid_freq",
 *    default "all") via the same generic `settings.set` / `flow` as
 *    the style itself; no change to SettingsStore was needed. The
 *    chips use `clickable`, which consumes the tap's down event, so
 *    tapping a chip changes the frequency WITHOUT also firing the
 *    panel's tap-to-next-style detector (its `detectTapGestures`
 *    requires an unconsumed down). The control is hidden for every
 *    other style, and the tap / swipe / pill behaviour is otherwise
 *    exactly as in v1.1.12.
 *
 * v1.1.20 — the frequency chips MOVE OUT of the canvas: on the
 * user's 1.1.19 landscape frame the cyan "Tất cả" chip sits on top
 * of the centre tower's tip. The chips now live in a row BELOW the
 * visualizer (same segmented control, same DataStore key, same
 * tap behaviour — chips are outside the tap/swipe area now, so a
 * chip tap can never switch the style either). When the panel is
 * given a fixed height (hero / landscape / full-screen), the
 * visualizer shrinks by 32 dp to make room for the row, so the
 * panel's total height is unchanged.
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
 * visualizer call in Now Playing.
 *
 * Gestures (scoped to the visualizer area only, so the slider /
 * transport below are untouched):
 *  - TAP the visualizer -> next style ([VisualizerStyle.next()],
 *    wraps around).
 *  - SWIPE LEFT -> next style; SWIPE RIGHT -> previous style
 *    ([VisualizerStyle.previous()]). A swipe only counts past a
 *    24 dp horizontal offset, so small accidental drags do nothing.
 *  - After a switch a small pill with the new style's label appears
 *    in the TOP-LEFT corner of the visualizer for ~1.5 s, then hides
 *    itself (state + [LaunchedEffect] delay — UI chrome only, not
 *    audio). v1.1.14: it used to sit CENTRED over the visualizer,
 *    which covered Circular's album-art disc — it now lives in the
 *    corner, clear of every style's content (the Ferrofluid
 *    frequency chips own the top-right corner).
 * Tap and swipe coexist as two separate `pointerInput` modifiers:
 * a tap produces no drag, so the drag detector ignores it; a drag
 * cancels the tap detector, so a swipe never also fires a tap — tap
 * still works after a swipe and vice versa.
 *
 * @param levels live FFT band magnitudes (`vm.pm.waveform.levels`)
 * @param pcmFrame live mono PCM frame (`vm.pm.waveform.pcmFrame`)
 * @param playing whether audio is actually playing
 * @param albumId current track's album id, for the Circular art disc (0 = none)
 * @param coverPath current track's downloaded cover file, for the Circular art disc (null = none)
 * @param heightDp v1.1.17 — canvas height in dp; null keeps the per-style default (88 dp Bars/Wave/Mirror, 140 dp Circular/Ferrofluid)
 */
@Composable
fun VisualizerPanel(
    levels: FloatArray,
    pcmFrame: FloatArray,
    playing: Boolean,
    modifier: Modifier = Modifier,
    albumId: Long = 0L,
    coverPath: String? = null,
    heightDp: Float? = null
) {
    val ctx = LocalContext.current
    val settings = remember(ctx) { (ctx.applicationContext as MioApp).container.settings }
    val scope = rememberCoroutineScope()
    val styleId by settings.flow(VisualizerStyle.KEY, VisualizerStyle.BARS.id)
        .collectAsState(initial = VisualizerStyle.BARS.id)
    val style = VisualizerStyle.fromId(styleId)
    // v1.1.13 — Ferrofluid frequency range, persisted under
    // "ferrofluid_freq" (default "all" = full spectrum).
    val ferroFreqId by settings.flow(VisualizerStyle.KEY_FERROFLUID_FREQ, FerrofluidFreq.ALL.id)
        .collectAsState(initial = FerrofluidFreq.ALL.id)
    val ferroFreq = FerrofluidFreq.fromId(ferroFreqId)
    val accent = LocalMioColors.current.accent

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

    // v1.1.20 — the Ferrofluid frequency chips live BELOW the
    // visualizer, not overlaid on it (the overlay covered the
    // centre tower tip on the user's 1.1.19 frame). With a fixed
    // panel height the visualizer gives up 32 dp for the chip row,
    // so the panel's total height is unchanged; without a fixed
    // height the row just adds below the default-height visualizer.
    val vizHeightDp = if (style == VisualizerStyle.FERROFLUID && heightDp != null) {
        (heightDp - 32f).coerceAtLeast(88f)
    } else {
        heightDp
    }
    Column(modifier) {
    Box(
        Modifier
            .fillMaxWidth()
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
        MioVisualizer(style, levels, pcmFrame, playing, albumId, coverPath, ferroFreq, Modifier.fillMaxWidth(), vizHeightDp)
        // v1.1.14: the pill sits in the top-left corner — it used to
        // sit centred, covering Circular's art disc.
        if (showLabel) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
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
        // v1.1.20 — Ferrofluid frequency selector, BELOW the
        // visualizer: the same segmented control (Bass / Tất cả /
        // Treble, same DataStore key), now centred under the canvas
        // where it covers nothing. Outside the tap/swipe Box, so a
        // chip tap can never also switch the style.
        if (style == VisualizerStyle.FERROFLUID) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (option in FerrofluidFreq.values()) {
                        val selected = option == ferroFreq
                        Box(
                            Modifier
                                .background(
                                    if (selected) accent else Color.Black.copy(alpha = 0.55f),
                                    CircleShape
                                )
                                .clickable {
                                    if (option != ferroFreq) {
                                        scope.launch {
                                            runCatching {
                                                settings.set(VisualizerStyle.KEY_FERROFLUID_FREQ, option.id)
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                option.label,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}
