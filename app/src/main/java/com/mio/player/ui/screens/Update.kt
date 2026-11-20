package com.mio.player.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mio.player.data.ota.OtaUpdateChecker
import com.mio.player.data.ota.UpdateInfo
import com.mio.player.ui.theme.*
import kotlinx.coroutines.launch

/** OTA update dialog: shows when a newer GitHub Release exists. */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Update available — ${info.versionName}", color = MioText) },
        text = {
            Column {
                Text(
                    if (info.changelog.isBlank()) "A new version of Mio Player is available on GitHub."
                    else info.changelog,
                    color = MioTextSecondary, fontSize = 14.sp,
                )
                if (downloading) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth())
                    Text("Downloading… $progress%", color = MioTextSecondary, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (downloading) return@TextButton
                    downloading = true
                    scope.launch {
                        val file = OtaUpdateChecker.downloadApk(ctx, info) { progress = it }
                        downloading = false
                        if (file != null) {
                            onDismiss()
                            OtaUpdateChecker.installApk(ctx, file)
                        } else {
                            Toast.makeText(ctx, "Download failed. Check your connection and try again.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !downloading,
            ) { Text(if (downloading) "Downloading…" else "Download & install", color = MioCyan) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) { Text("Later", color = MioTextSecondary) }
        },
        containerColor = MioSurfaceRaised,
    )
}

@Composable
fun rememberUpdateState(): UpdateUiState {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { UpdateUiState() }
    fun check(showToastIfNone: Boolean = false) {
        if (state.checking) return
        state.checking = true
        scope.launch {
            val info = OtaUpdateChecker.checkForUpdate()
            state.checking = false
            state.lastChecked = System.currentTimeMillis()
            if (info != null) state.available = info
            else if (showToastIfNone) Toast.makeText(ctx, "You are on the latest version.", Toast.LENGTH_SHORT).show()
        }
    }
    state.check = ::check
    return state
}

class UpdateUiState {
    var available: UpdateInfo? by mutableStateOf(null)
    var checking: Boolean by mutableStateOf(false)
    var lastChecked: Long by mutableStateOf(0L)
    var check: (Boolean) -> Unit = {}
}
