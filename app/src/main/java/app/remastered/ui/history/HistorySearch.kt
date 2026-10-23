package app.remastered.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.components.CollectionRow
import app.remastered.ui.components.SectionHeader
import app.remastered.ui.components.dotted
import app.remastered.ui.theme.Palette
import kotlinx.coroutines.delay

/** Artists and songs shown under Search's results; the history pages have the rest. */
private const val HISTORY_ARTISTS = 3
private const val HISTORY_SONGS = 5

/**
 * "From your history": stored artists and songs matching [query] (warehouse `/api/search`), with
 * play counts, each opening its history page. Finds what Spotify's search ranks low, or no longer
 * has. Nothing without the warehouse, on a deployment without the route, on failure, or with no
 * matches; genre searches aren't names, so they're skipped too.
 */
@Composable
fun FromYourHistory(app: AppState, query: String) {
    val artists = app.warehouseSupports(WarehouseFeature.ArtistHistory)
    val songs = app.warehouseSupports(WarehouseFeature.TrackHistory)
    val on = app.warehouseSupports(WarehouseFeature.Search) && (artists || songs) && !query.startsWith("genre:")
    val found = loadWarehouse(on, query) {
        if (!on) return@loadWarehouse null
        delay(300) // debounce typing, as Spotify's search does
        app.c.warehouse.api.search(query, limit = HISTORY_SONGS)
    }.value ?: return
    val shownArtists = if (artists) found.artists.filter { it.plays > 0 }.take(HISTORY_ARTISTS) else emptyList()
    val shownSongs = if (songs) found.tracks.filter { it.plays > 0 }.take(HISTORY_SONGS) else emptyList()
    if (shownArtists.isEmpty() && shownSongs.isEmpty()) return
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader("From your history", small = true)
        Spacer(Modifier.height(6.dp))
        shownArtists.forEach { a ->
            CollectionRow(a.name, dotted("Artist", plays(a.plays)), { HistoryArt(CircleShape) }, { app.open(Route.HistoryArtist(a.id, a.name)) })
        }
        shownSongs.forEach { t ->
            CollectionRow(t.name, dotted(t.artist, plays(t.plays)), { HistoryArt(RoundedCornerShape(9.dp)) }, { app.open(Route.HistoryTrack(t.id)) })
        }
    }
}

/** The history icon where a row would have artwork: the warehouse's search has none. */
@Composable
private fun HistoryArt(shape: Shape) {
    Box(Modifier.size(48.dp).clip(shape).background(Palette.accentSoft), contentAlignment = Alignment.Center) {
        Icon(HistoryIcons.History, null, Modifier.size(22.dp), tint = Palette.accentLight)
    }
}
