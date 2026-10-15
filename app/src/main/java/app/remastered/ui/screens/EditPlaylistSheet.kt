package app.remastered.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.remastered.core.CoverJpeg
import app.remastered.core.model.Playlist
import app.remastered.core.model.best
import app.remastered.ui.AppState
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Chip
import app.remastered.ui.player.Sheet
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Spotify's limits on a playlist's name and description. */
private const val MAX_NAME = 100
private const val MAX_DESCRIPTION = 300

/**
 * Edit details for a playlist the user owns: name, description and (once the session has the
 * `ugc-image-upload` scope) a new cover from the photo picker.
 */
@Composable
fun EditPlaylistSheet(app: AppState, playlist: Playlist, onDone: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(playlist.name) }
    var description by remember { mutableStateOf(playlist.description) }
    var cover by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Scopes added after sign-in need a fresh sign-in; until then covers can't be changed.
    val canChangeCover = remember { app.c.spotify.hasScope("ugc-image-upload") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) cover = uri }

    fun save() {
        val newName = name.trim()
        // Spotify keeps descriptions to one line.
        val newDescription = description.replace(Regex("\\s*\\n\\s*"), " ").trim()
        val changed = newName != playlist.name || newDescription != playlist.description
        val image = cover
        saving = true
        app.launch {
            try {
                val jpeg = image?.let {
                    withContext(Dispatchers.IO) { runCatching { coverJpeg(context, it) }.getOrNull() }
                        ?: run { app.message("Can't use that image as a cover."); return@launch }
                }
                if (changed) app.c.catalog.updatePlaylistDetails(playlist.id, newName, newDescription)
                if (jpeg != null) app.c.catalog.uploadPlaylistCover(playlist.id, jpeg)
                app.playlistsChanged()
                app.message(if (jpeg != null) "Saved. Spotify can take a minute to show the new cover." else "Saved")
                onDone()
            } finally {
                saving = false
            }
        }
    }

    Sheet(onDone) {
        Text("Edit details", style = Type.sectionSmall, color = Palette.text, modifier = Modifier.padding(bottom = 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val art = Modifier.size(120.dp)
            Box(if (canChangeCover) art.clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Choose a cover") {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            } else art) {
                Artwork(cover?.toString() ?: playlist.images.best(300)?.url, art, RoundedCornerShape(14.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (canChangeCover) {
                    Chip("Choose cover", selected = false, icon = Icons.Image, onClick = {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Image, null, Modifier.size(16.dp), tint = Palette.dim)
                        Text("Sign in to Spotify again to change covers.", style = Type.caption, color = Palette.sub, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        Field("Name", name, { name = it.take(MAX_NAME) }, singleLine = true, modifier = Modifier.padding(top = 20.dp))
        Field("Description", description, { description = it.take(MAX_DESCRIPTION) }, singleLine = false, modifier = Modifier.padding(top = 12.dp),
            placeholder = "Add an optional description")
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Chip("Cancel", selected = false, onClick = onDone)
            Spacer(Modifier.width(8.dp))
            Chip(if (saving) "Saving…" else "Save", selected = true, onClick = { if (!saving && name.isNotBlank()) save() })
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean, modifier: Modifier = Modifier, placeholder: String = "") {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = Type.label, color = Palette.sub, modifier = Modifier.padding(bottom = 6.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(12.dp)).background(Palette.raised).padding(horizontal = 14.dp, vertical = 13.dp)) {
            if (value.isEmpty()) Text(placeholder, style = Type.bodyRegular, color = Palette.dim)
            BasicTextField(value, onChange, singleLine = singleLine, maxLines = if (singleLine) 1 else 4,
                textStyle = Type.bodyRegular.copy(color = Palette.text), cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Decodes [uri], crops the middle square at up to [CoverJpeg.SIZE_PX] and fits it in Spotify's limit. */
private fun coverJpeg(context: Context, uri: Uri): ByteArray? {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val w = info.size.width
        val h = info.size.height
        val scale = CoverJpeg.SIZE_PX.toFloat() / minOf(w, h)
        if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
    }
    val side = minOf(bitmap.width, bitmap.height)
    val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
    return CoverJpeg.fit { quality -> ByteArrayOutputStream().also { square.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray() }
}
