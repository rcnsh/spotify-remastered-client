package app.remastered.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import app.remastered.core.Connection
import app.remastered.core.model.Track
import app.remastered.core.warehouse.WarehouseException
import app.remastered.core.warehouse.WarehouseException.Problem
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.core.warehouse.spotifyUri
import app.remastered.ui.AppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Start radio" from the history: the warehouse ranks the songs the user plays near a song or an
 * artist (`/api/radio`), and App Remote plays the seed and queues them, since Spotify's own
 * radio and recommendations aren't open to apps. Shown only where [canStartRadio] says so: with
 * the warehouse set up and serving `/api/radio`. A warehouse that predates the route answers 404
 * once, which says so and hides the entry points (WarehouseServices marks the feature missing).
 */

/** Songs asked for: a few hours' listening, without flooding Spotify's queue. */
private const val RADIO_LENGTH = 30
/** Between queue commands, so App Remote takes them in order without dropping any. */
private const val QUEUE_SPACING_MS = 120L

@Composable
fun AppState.canStartRadio(): Boolean = warehouseSupports(WarehouseFeature.Radio)

/** Plays [track] and queues its radio. Episodes and tracks without a Spotify id have none. */
fun AppState.startRadio(track: Track) {
    val uri = spotifyUri("track", track.id)
    if (track.isEpisode || uri == null) return message("There's no radio for this one.")
    startRadio(seedUri = uri, trackId = track.id)
}

/** Plays the radio for an artist: its own most-played songs first, then what's played near them. */
fun AppState.startArtistRadio(artistId: String) = startRadio(seedUri = null, artistId = artistId)

private fun AppState.startRadio(seedUri: String?, trackId: String? = null, artistId: String? = null) {
    // Commands sent while App Remote is down are dropped, so connect first and let the user retry.
    if (c.player.connection.value != Connection.Connected) {
        c.player.connect()
        return message("Connecting to Spotify. Try again in a moment.")
    }
    c.scope.launch {
        val radio = try {
            c.warehouse.api.radio(trackId = trackId, artistId = artistId, limit = RADIO_LENGTH)
        } catch (e: WarehouseException) {
            message(if (e.problem == Problem.NotFound) "Radio needs a newer warehouse. Update its Worker to use it." else e.userMessage)
            return@launch
        }
        val uris = radio.tracks.map { it.uri }.filter { it != seedUri }
        val first = seedUri ?: uris.firstOrNull()
        if (first == null || uris.isEmpty()) {
            message("Not enough listening around this for a radio yet.")
            return@launch
        }
        play(first)
        val queued = uris.filter { it != first }
        for (uri in queued) {
            delay(QUEUE_SPACING_MS)
            c.player.queue(uri)
        }
        message(if (queued.size == 1) "Radio: 1 song queued" else "Radio: ${queued.size} songs queued")
    }
}

/** A broadcast mark, drawn like the theme's icons (24 px grid, 2 px round strokes). */
val RadioIcon: ImageVector = ImageVector.Builder("radio", 24.dp, 24.dp, 24f, 24f).apply {
    listOf(
        "M10.5 12a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0 -3 0",
        "M8.2 8.2a5.4 5.4 0 0 0 0 7.6M15.8 8.2a5.4 5.4 0 0 1 0 7.6",
        "M5.1 5.1a9.8 9.8 0 0 0 0 13.8M18.9 5.1a9.8 9.8 0 0 1 0 13.8",
    ).forEach {
        addPath(PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()
