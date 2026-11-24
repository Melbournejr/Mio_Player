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
import com.mio.player.data.model.Lyrics
import com.mio.player.data.model.Track
import com.mio.player.data.repo.LyricsRepository
import com.mio.player.ui.theme.CardLarge
import com.mio.player.ui.theme.MioCyan
import com.mio.player.ui.theme.MioDivider
import com.mio.player.ui.theme.MioText
import com.mio.player.ui.theme.MioTextSecondary
import kotlinx.coroutines.launch

/**
 * v1.1.35 — manual lyrics search dialog (Now Playing, portrait and
 * landscape), opened by the "Tìm lời bài hát" overflow-menu item.
 * The automatic lyrics lookup has no manual path: when a track's
 * metadata is wrong (or the sources simply have no match for it)
 * the user had no way to find the lyrics by hand.
 *
 *  - Two editable fields — Tên bài, Nghệ sĩ — pre-filled with the
 *    current track's values ("<unknown>" artist becomes empty).
 *  - "Tìm": searches LRCLIB (exact, then search) and Netease with
 *    the EDITED title/artist (repo.searchManual — same sources as
 *    the automatic path, but no sidecar / embedded / filename
 *    derivation). The result is NOT cached yet: the dialog shows a
 *    preview (first lines + source + synced/plain) so the user can
 *    tell whether it is the right song before committing.
 *  - "Dùng lời này": persists the found lyrics as this track's
 *    lyrics (repo.saveManualLyrics — cached under the TRACK's own
 *    artist/title, not the search terms, source "manual") and
 *    hands them back via [onLyricsSaved] so Now Playing shows
 *    them immediately. A later manual metadata change re-fetches
 *    lyrics for the new values, as usual.
 *  - The result line shows "Đang tìm…" / "Đã tìm thấy lời (…)"
 *    / "Không tìm thấy lời". "Đóng" just closes; the field state
 *    is keyed on track.id.
 */
@Composable
fun LyricsSearchDialog(
    track: Track,
    lyricsRepo: LyricsRepository,
    durationMs: Long,
    onDismissRequest: () -> Unit,
    onLyricsSaved: (Lyrics) -> Unit,
) {
    var title by remember(track.id) {
        mutableStateOf(lyricsRepo.normalizeTitle(track.title).ifBlank { track.title.trim() })
    }
    var artist by remember(track.id) {
        mutableStateOf(if (track.artist.trim().lowercase() in setOf("unknown artist", "<unknown>", "unknown", "")) "" else track.artist)
    }
    var busy by remember(track.id) { mutableStateOf(false) }
    var status by remember(track.id) { mutableStateOf<String?>(null) }
    var found by remember(track.id) { mutableStateOf<Lyrics?>(null) }
    val scope = rememberCoroutineScope()

    fun searchWithEditedValues() {
        if (busy) return
        scope.launch {
            busy = true
            status = "Đang tìm…"
            found = null
            val result = runCatching { lyricsRepo.searchManual(artist, title, durationMs) }
                .getOrDefault(null)
            if (result != null && (result.synced.isNotEmpty() || !result.plain.isNullOrBlank())) {
                found = result
                val kind = if (result.synced.isNotEmpty()) "đồng bộ (${result.synced.size} dòng)" else "không đồng bộ"
                status = "Đã tìm thấy lời ($kind) — ${result.source}"
            } else {
                status = "Không tìm thấy lời"
            }
            busy = false
        }
    }

    fun useFoundLyrics() {
        val lyrics = found ?: return
        if (busy) return
        scope.launch {
            busy = true
            runCatching { lyricsRepo.saveManualLyrics(track.artist, track.title, lyrics) }
            onLyricsSaved(lyrics)
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        shape = CardLarge,
        title = {
            Text("Tìm lời bài hát", color = MioText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
                found?.let { lyrics ->
                    Spacer(Modifier.height(12.dp))
                    val preview = when {
                        lyrics.synced.isNotEmpty() -> lyrics.synced.take(3).joinToString("\n") { it.text }
                        !lyrics.plain.isNullOrBlank() -> lyrics.plain.lines().take(3).joinToString("\n")
                        else -> ""
                    }
                    Text(preview, color = MioTextSecondary, fontSize = 13.sp)
                }
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
                TextButton(onClick = { useFoundLyrics() }, enabled = !busy && found != null) {
                    Text("Dùng lời này", color = MioCyan, fontSize = 14.sp)
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
