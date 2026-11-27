package com.mio.player.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
        // Design system: card radius 16–20 px (CardLarge = 20 dp);
        // the Material default dialog shape (28 dp) was off-spec.
        shape = CardLarge,
        title = { Text("Update available — ${info.versionName}", color = MioText, fontSize = 24.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    if (info.changelog.isBlank()) "A new version of Mio Player is available on GitHub."
                    else info.changelog,
                    color = MioTextSecondary, fontSize = 14.sp,
                )
                if (downloading) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth(), color = MioCyan)
                    Spacer(Modifier.height(8.dp))
                    // Design system: the "downloading" state gets its own
                    // icon, not just colour or text.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Download, contentDescription = "Downloading", tint = MioCyan, modifier = Modifier.size(16.dp))
                        Text(" Downloading… $progress%", color = MioTextSecondary, fontSize = 12.sp)
                    }
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
            // v1.1.51 — only a real UpToDate may say "latest"; a
            // failed check (rate limit / network) says so instead.
            val result = OtaUpdateChecker.checkForUpdateDetailed()
            state.checking = false
            state.lastChecked = System.currentTimeMillis()
            when (result) {
                is OtaUpdateChecker.CheckResult.Available -> state.available = result.info
                is OtaUpdateChecker.CheckResult.UpToDate ->
                    if (showToastIfNone) Toast.makeText(ctx, "You are on the latest version.", Toast.LENGTH_SHORT).show()
                is OtaUpdateChecker.CheckResult.Failed ->
                    if (showToastIfNone) Toast.makeText(ctx, "Không kiểm tra được cập nhật (${result.reason}). Thử lại sau.", Toast.LENGTH_LONG).show()
            }
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
