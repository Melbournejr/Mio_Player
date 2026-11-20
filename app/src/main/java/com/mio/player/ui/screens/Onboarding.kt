
package com.mio.player.ui.screens
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mio.player.R
import com.mio.player.ui.components.GradientButton
import com.mio.player.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
@Composable fun SplashScreen(onDone: () -> Unit) {
    LaunchedEffect(Unit) { delay(1600); onDone() }
    Box(Modifier.fillMaxSize().background(MioBg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.mio_logo), null, Modifier.size(120.dp))
            Spacer(Modifier.height(16.dp))
            Text("Mio Player", color = MioText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Hear every moment", color = MioTextSecondary, fontSize = 14.sp)
        }
    }
}
@Composable fun OnboardingScreen(onDone: () -> Unit, onSkip: () -> Unit) {
    val pages = listOf("Discover music made for you" to "Playlists and albums picked from your own library.",
        "Studio sound, anywhere" to "High-res playback, DSD decoding and a full 10-band equalizer.",
        "Lyrics that follow the song" to "Synced lyrics, found automatically.")
    var page by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(MioBg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Text(pages[page].first, color = MioText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp)); Text(pages[page].second, color = MioTextSecondary, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        GradientButton(if (page < 2) "Next" else "Get Started") { if (page < 2) page++ else onDone() }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.TextButton(onClick = onSkip) { Text("Skip", color = MioTextSecondary) }
    }
}
@Composable fun LoginScreen(onDone: () -> Unit) {
    // Local profile only — no server, no account. The user asked for personal use.
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as com.mio.player.MioApp).container
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(MioBg).padding(24.dp)) {
        Spacer(Modifier.height(48.dp))
        Text("Welcome back", color = MioText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Your library stays on your device.", color = MioTextSecondary)
        Spacer(Modifier.height(32.dp))
        OutlinedTextFieldLocal("Your name (optional)", name) { name = it }
        Spacer(Modifier.height(24.dp))
        GradientButton("Continue") {
            // The name used to live only in this screen's memory and was
            // asked again on every launch; save it now.
            scope.launch {
                if (name.isNotBlank()) container.settings.set(com.mio.player.data.prefs.SettingsStore.KEY_USER_NAME, name.trim())
                container.settings.setOnboarded(true)
            }
            onDone()
        }
        Spacer(Modifier.height(12.dp))
        Text("No password, no server. Mio Player is a local player for your own music.", color = MioTextSecondary, fontSize = 12.sp)
    }
}
@Composable fun OutlinedTextFieldLocal(label: String, value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedTextColor = MioText, unfocusedTextColor = MioText, focusedBorderColor = MioCyan, unfocusedBorderColor = MioDivider),
        shape = InputShape)
}
@Composable fun PickGenresScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as com.mio.player.MioApp).container
    val scope = rememberCoroutineScope()
    val genres = listOf("Pop","Chill","Lo-Fi","Rock","Jazz","EDM","Classical","Hip-Hop","R&B","Acoustic")
    val selected = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxSize().background(MioBg).padding(24.dp)) {
        Text("What do you love to hear?", color = MioText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
            items(genres.size) { i ->
                val g = genres[i]; val sel = g in selected
                androidx.compose.material3.FilterChip(selected = sel, onClick = { if (sel) selected.remove(g) else selected.add(g) }, label = { Text(g) },
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = MioTeal, selectedLabelColor = androidx.compose.ui.graphics.Color.White))
            }
        }
        GradientButton("Continue") {
            scope.launch {
                container.settings.set(com.mio.player.data.prefs.SettingsStore.KEY_GENRES, selected.toSet())
                container.settings.setOnboarded(true)
            }
            onDone()
        }
    }
}
