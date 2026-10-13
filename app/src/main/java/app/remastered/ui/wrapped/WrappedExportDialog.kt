package app.remastered.ui.wrapped

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.remastered.core.warehouse.Wrapped
import app.remastered.core.wrapped.VideoExport
import app.remastered.core.wrapped.WrappedTimeline
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The video export (web App.tsx `Ready`): silent, or over the hook of a song file the listener
 * picks. Spotify's audio never goes into a video, so there is no "use the song that's playing".
 * The screen stays on while it works; Cancel stops it.
 *
 * [auto] (debug preview only) starts at once: `silent`, or the uri of a song file.
 */
@Composable
internal fun ExportDialog(
    renderer: WrappedRenderer,
    data: Wrapped,
    assets: WrappedAssets,
    onDismiss: () -> Unit,
    message: (String) -> Unit,
    auto: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val seconds = remember(data) { WrappedTimeline.of(data).durationInFrames.toDouble() / VideoExport.FPS }
    var song by remember { mutableStateOf<SongFile?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<File?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val view = LocalView.current
    DisposableEffect(busy != null) {
        view.keepScreenOn = busy != null
        onDispose { view.keepScreenOn = false }
    }

    suspend fun analyze(uri: Uri) {
        busy = "Finding the hook…"
        try {
            song = SongFile.analyze(context, uri, seconds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("WrappedVideo", "analysis failed", e)
            error = (e as? ExportException)?.message ?: "Couldn't read that file."
        } finally {
            busy = null
        }
    }

    fun export() {
        error = null
        result = null
        progress = 0f
        job = scope.launch {
            busy = if (song != null) "Preparing the song…" else "Rendering video"
            try {
                result = exportWrappedVideo(context, renderer, data, assets, song) {
                    busy = "Rendering video"
                    progress = it
                }
                Log.i("WrappedVideo", "done ${result!!.name}")
            } catch (e: CancellationException) {
                Log.i("WrappedVideo", "cancelled")
                throw e
            } catch (e: Exception) {
                Log.w("WrappedVideo", "export failed", e)
                error = (e as? ExportException)?.message ?: "The video couldn't be made."
            } finally {
                busy = null
                job = null
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            error = null
            job = scope.launch {
                try { analyze(uri) } finally { job = null }
            }
        }
    }

    if (auto != null) LaunchedEffect(auto) {
        if (auto != "silent") analyze(Uri.parse(auto))
        if (error == null) export()
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Export video") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val done = result
                when {
                    busy != null -> {
                        Text(if (busy == "Rendering video") "Rendering video · ${(progress * 100).toInt()}%" else busy!!)
                        if (busy == "Rendering video") LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Keep the app open while it renders.")
                    }
                    done != null -> Text("Your video is ready (${done.length() / 1_000_000} MB, ${seconds.toInt()} s).")
                    else -> {
                        Text("1080 × 1920, ${seconds.toInt()} seconds.")
                        val s = song
                        if (s == null) {
                            Text("Silent, or pick a song file from this phone to play under it from its loudest stretch. Spotify's audio can't go into a video.")
                            TextButton(onClick = { pick.launch(arrayOf("audio/*")) }) { Text("Pick a song file") }
                        } else {
                            Text("${s.name}: starting at ${s.startLabel}, the loudest stretch.")
                            TextButton(onClick = { song = null }) { Text("Remove song") }
                        }
                    }
                }
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val done = result
            when {
                busy != null -> TextButton(onClick = { job?.cancel() }, enabled = job != null) { Text("Cancel") }
                done != null -> Row {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                saveVideoToMovies(context, done)
                                message("Saved to Movies/Remastered")
                            } catch (e: Exception) {
                                message(e.message ?: "Couldn't save to Movies.")
                            }
                        }
                    }) { Text("Save to Movies") }
                    TextButton(onClick = { WrappedShare.share(context, done, "Your Wrapped", "video/mp4") }) { Text("Share") }
                }
                else -> TextButton(onClick = ::export) { Text(if (song != null) "Export with song" else "Export silent") }
            }
        },
        dismissButton = {
            when {
                busy != null -> Unit
                result != null -> TextButton(onClick = onDismiss) { Text("Done") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
