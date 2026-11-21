package com.mio.player.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mio.player.data.model.Track
import com.mio.player.data.repo.LyricsRepository
import com.mio.player.data.repo.MetadataRepository
import com.mio.player.ui.theme.CardLarge
import com.mio.player.ui.theme.MioCyan
import com.mio.player.ui.theme.MioDivider
import com.mio.player.ui.theme.MioText
import com.mio.player.ui.theme.MioTextSecondary
import kotlinx.coroutines.launch

/**
 * v1.1.12 — manual metadata edit / search dialog (Now Playing,
 * portrait and landscape). v1.1.11's "Tìm metadata" button fetched
 * immediately with the library-derived title/artist and, for many
 * songs, found nothing with no way for the user to correct the
 * search terms. This dialog lets the user type them:
 *
 *  - Three editable fields — Tên bài, Nghệ sĩ, Album — pre-filled
 *    with the current track's values (title via
 *    lyricsRepo.normalizeTitle like the existing fetch path;
 *    "<unknown>" artist/album become empty fields).
 *  - "Tìm": searches iTunes then MusicBrainz with the EDITED
 *    title/artist (repo.fetchForManualWith — same inner logic as
 *    the old immediate fetch, still bypassing the auto toggle and
 *    the 7-day retry block). On a hit the dialog's artist/album
 *    fields are filled with the found data, the cover is
 *    downloaded and stored exactly like the old path, and the
 *    queue item is swapped live via [onTrackUpdated] so Now
 *    Playing / MiniPlayer show it immediately. On a miss the
 *    result line says so and the miss is recorded as before.
 *  - "Lưu": persists whatever is currently in the fields as this
 *    song's metadata (repo.saveTypedMetadata, source "manual") and
 *    swaps the queue item live the same way. A manual entry
 *    OVERRIDES the track's own artist/album even when the local
 *    tag is present but wrong — see MetadataRepository
 *    .applyMetadata(). The cover comes only from a successful
 *    search; there is no image picker.
 *  - The result line shows "Đang tìm…" / "Đã tìm thấy metadata" /
 *    "Không tìm thấy metadata" / "Đã lưu". "Đóng" just closes;
 *    the dialog state resets when the track changes because the
 *    callers drop it on track change (and the field state below
 *    is keyed on track.id).
 */
@Composable
fun MetadataEditDialog(
    track: Track,
    repo: MetadataRepository,
    lyricsRepo: LyricsRepository,
    onDismissRequest: () -> Unit,
    onTrackUpdated: (Track) -> Unit,
) {
    var title by remember(track.id) {
        mutableStateOf(lyricsRepo.normalizeTitle(track.title).ifBlank { track.title.trim() })
    }
    var artist by remember(track.id) {
        mutableStateOf(if (repo.isUnknownArtist(track.artist)) "" else track.artist)
    }
    var album by remember(track.id) {
        mutableStateOf(if (repo.isUnknownAlbum(track.album)) "" else track.album)
    }
    var busy by remember(track.id) { mutableStateOf(false) }
    var status by remember(track.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun searchWithEditedValues() {
        if (busy) return
        scope.launch {
            busy = true
            status = "Đang tìm…"
            val result = runCatching { repo.fetchForManualWith(track, title, artist) }
                .getOrDefault(null)
            if (result?.found == true) {
                result.artist?.takeIf { it.isNotBlank() }?.let { artist = it }
                result.album?.takeIf { it.isNotBlank() }?.let { album = it }
                onTrackUpdated(repo.mergedTrack(track))
                status = "Đã tìm thấy metadata"
            } else {
                status = "Không tìm thấy metadata"
            }
            busy = false
        }
    }

    fun saveTypedValues() {
        if (busy) return
        scope.launch {
            busy = true
            val saved = runCatching { repo.saveTypedMetadata(track, title, artist, album) }
                .getOrDefault(false)
            if (saved) {
                onTrackUpdated(repo.mergedTrack(track))
                status = "Đã lưu"
            } else {
                status = "Không lưu được, thử lại"
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        shape = CardLarge,
        title = {
            Text("Sửa metadata", color = MioText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Tên bài", fontSize = 14.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MioText,
                        unfocusedTextColor = MioText,
                        focusedBorderColor = MioCyan,
                        unfocusedBorderColor = MioDivider,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Nghệ sĩ", fontSize = 14.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MioText,
                        unfocusedTextColor = MioText,
                        focusedBorderColor = MioCyan,
                        unfocusedBorderColor = MioDivider,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album", fontSize = 14.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MioText,
                        unfocusedTextColor = MioText,
                        focusedBorderColor = MioCyan,
                        unfocusedBorderColor = MioDivider,
                    ),
                )
                status?.let {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (busy) {
                            CircularProgressIndicator(
                                color = MioCyan,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(it, color = MioCyan, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                Button(onClick = { searchWithEditedValues() }, enabled = !busy) {
                    Text("Tìm")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { saveTypedValues() }, enabled = !busy) {
                    Text("Lưu", color = MioCyan, fontSize = 14.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !busy) {
                Text("Đóng", color = MioTextSecondary, fontSize = 14.sp)
            }
        },
    )
}
