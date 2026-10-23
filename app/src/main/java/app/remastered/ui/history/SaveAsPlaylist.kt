package app.remastered.ui.history

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.remastered.core.SavePlaylist
import app.remastered.core.model.Playlist
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.components.Chip
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.launch

// "Save as playlist" for song lists from the history: a chip, a dialog to name it, and the result.

/**
 * A "Save as playlist" chip for [uris] (null for songs without a Spotify id), opening a dialog
 * with [defaultName]. Hidden when signed out of Spotify or when no song could be saved.
 */
@Composable
fun SaveAsPlaylistChip(app: AppState, defaultName: String, uris: List<String?>, modifier: Modifier = Modifier) {
    if (!app.c.spotify.signedIn || SavePlaylist.uris(uris).isEmpty()) return
    var naming by rememberSaveable { mutableStateOf(false) }
    Chip("Save as playlist", selected = false, onClick = { naming = true }, modifier = modifier, icon = Icons.Plus)
    if (naming) SavePlaylistDialog(defaultName, onDismiss = { naming = false }) { name ->
        naming = false
        app.saveAsPlaylist(name, uris)
    }
}

@Composable
private fun SavePlaylistDialog(defaultName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surface,
        title = { Text("Save as playlist", style = Type.sectionSmall, color = Palette.text) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(defaultName) }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Creates the playlist (two Web API requests), then says how it went, with "Open" where there's one to open. */
fun AppState.saveAsPlaylist(name: String, uris: List<String?>) {
    c.scope.launch {
        val (text, playlist) = try {
            val saved = SavePlaylist.save(c.catalog, name, uris)
            playlistsChanged()
            savedMessage(saved) to saved.playlist
        } catch (e: SavePlaylist.Failed) {
            if (e.created != null) playlistsChanged()
            failedMessage(e) to e.created
        } catch (_: IllegalArgumentException) {
            "None of these songs are on Spotify, so there's nothing to save." to null
        }
        snackbar.currentSnackbarData?.dismiss()
        val result = snackbar.showSnackbar(text, actionLabel = playlist?.let { "Open" }, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed && playlist != null) open(Route.Playlist(playlist.id))
    }
}

internal fun savedMessage(saved: SavePlaylist.Saved): String {
    val text = "Saved ${count(saved.added, "song")} to ${saved.playlist.name}"
    // Left out: songs Spotify has no id for (imported plays), repeats, or past the cap.
    return if (saved.skipped == 0) text else "$text; ${saved.skipped} left out"
}

internal fun failedMessage(e: SavePlaylist.Failed): String {
    val why = AppState.describe(e.cause)
    val created: Playlist = e.created ?: return "Couldn't create the playlist. $why"
    return "Made ${created.name}, but couldn't add the songs. $why"
}
