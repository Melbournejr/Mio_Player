package com.mio.player.ui.components
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
/**
 * Landscape support (v1.1.5). The app never had a portrait lock — no
 * `screenOrientation` in the manifest, no `requestedOrientation` anywhere
 * — but every screen was laid out for portrait only (fixed 240 dp cover,
 * one vertical column, bottom nav), so rotating produced clipped /
 * unusable layouts. Screens branch on [isLandscape] and keep the same
 * content, view-models and theme tokens in both orientations.
 */
/** True when the device is currently in landscape orientation. */
@Composable fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * Screen side margin from the design spec (PDF p.8): 20 px in portrait.
 * In landscape the screen is much wider than tall, so the margin grows
 * to 48 dp — content stays readable instead of stretching edge to edge.
 */
@Composable fun screenHorizontalPadding(): Dp = if (isLandscape()) 48.dp else 20.dp
