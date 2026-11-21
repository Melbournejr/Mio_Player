package com.mio.player.ui.visualizer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mio.player.MioApp
import com.mio.player.ui.theme.MioSurfaceRaised
import com.mio.player.ui.theme.MioTextSecondary
import com.mio.player.ui.theme.mioGradientBackground
import kotlinx.coroutines.launch

/**
 * v1.1.5 — style picker + the one-call panel for Now Playing.
 *
 * The picker itself is stateless ([VisualizerStylePicker]): the caller
 * owns the current style and the save, so it can be dropped into any
 * screen. [VisualizerPanel] is the one-call version for Now Playing:
 * it reads the saved style from DataStore (same "mio_settings" store
 * as every other setting, key [VisualizerStyle.KEY]), renders the
 * visualizer, and saves the choice when the user taps a chip — the
 * coordinator only passes the live audio data.
 */

/** Row of five style chips. Selected chip: theme gradient, white text.
 *  Unselected: raised surface, secondary text. No icons — the labels
 *  ("Cột / Sóng / Tròn / Gương / Giọt nước") are the style names.
 *  v1.1.7: the row is horizontally scrollable — five chips at 13 sp
 *  with 14 dp padding are ~337 dp wide, wider than the ~320 dp left
 *  on a 360 dp screen after Now Playing's 20 dp side margins, so the
 *  last chip ("Giọt nước") would otherwise be cut off. When the row
 *  fits (wider screens) it stays centred and does not scroll. */
@Composable
fun VisualizerStylePicker(
    selected: VisualizerStyle,
    onSelect: (VisualizerStyle) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VisualizerStyle.values().forEach { style ->
            val isSelected = style == selected
            val chipModifier = if (isSelected) {
                Modifier.mioGradientBackground(CircleShape)
            } else {
                Modifier.background(MioSurfaceRaised, CircleShape)
            }
            androidx.compose.foundation.layout.Box(
                chipModifier
                    .clickable { onSelect(style) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    style.label,
                    color = if (isSelected) Color.White else MioTextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/**
 * One-call integration for Now Playing (the coordinator's drop-in):
 * visualizer + picker, with the style loaded from and saved to
 * DataStore automatically. Replaces the bare `LiveWaveform(...)` call.
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
    Column(modifier) {
        MioVisualizer(style, levels, pcmFrame, playing, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        VisualizerStylePicker(
            selected = style,
            onSelect = { chosen ->
                scope.launch {
                    runCatching { settings.set(VisualizerStyle.KEY, chosen.id) }
                }
            },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
