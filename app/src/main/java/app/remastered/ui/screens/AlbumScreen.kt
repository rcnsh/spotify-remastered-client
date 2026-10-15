package app.remastered.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.remastered.core.model.Album
import app.remastered.core.model.AlbumGroup
import app.remastered.core.model.AlbumRef
import app.remastered.core.model.AlbumType
import app.remastered.core.model.Artist
import app.remastered.core.model.Track
import app.remastered.core.model.best
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.Route
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.Loading
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.TrackRow
import app.remastered.ui.components.dotted
import app.remastered.ui.load
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private class AlbumData(val album: Album, val tracks: List<Track>, val artist: Artist?, val more: List<Album>)

@Composable
fun AlbumScreen(app: AppState, id: String) {
    val data = load(app.c.screens, "album:$id", maxAgeMs = 10 * 60_000) {
        coroutineScope {
            val detail = app.c.catalog.album(id)
            val a = detail.album
            val artistId = a.artists.firstOrNull()?.id
            val artist = async { artistId?.let { runCatching { app.c.catalog.artist(it) }.getOrNull() } }
            val more = async {
                artistId?.let { aid ->
                    runCatching { app.c.catalog.artistAlbums(aid, setOf(AlbumGroup.Album, AlbumGroup.Single), 0, 10).items }
                        .onFailure { Log.w("AlbumScreen", "More by artist failed: ${it.javaClass.simpleName} ${it.message}") }
                        .getOrNull()
                }.orEmpty().filter { it.id != a.id }
            }
            val tracks = if (detail.tracks.hasMore) {
                detail.tracks.items + allPages(max = 500) { app.c.catalog.albumTracks(id, detail.tracks.items.size + it, 50) }
            } else detail.tracks.items
            AlbumData(a, tracks, artist.await(), more.await())
        }
    }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 700 } }
    Box(Modifier.fillMaxSize()) {
        when (val s = data.state) {
            Load.Loading -> Loading(Modifier.padding(top = 120.dp))
            is Load.Failed -> ErrorMessage(s.message, data.retry, Modifier.padding(top = 120.dp))
            is Load.Done -> {
                val d = s.value
                val a = d.album
                val player by app.c.player.state.collectAsState()
                val liked by app.c.liked.uris.collectAsState()
                val saved = app.rememberSaved(a.uri)
                val shuffle = app.shuffleOn()
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = ScreenPadding) {
                    item(key = "header") {
                        CollectionHeader(
                            imageUrl = a.images.best(600)?.url,
                            title = a.name,
                            ownerName = a.artists.joinToString(", ") { it.name },
                            ownerImage = d.artist?.images?.best(64)?.url,
                            onOwner = a.artists.firstOrNull()?.let { artist -> { app.open(Route.Artist(artist.id)) } },
                            meta = dotted(a.type.label(), a.year, durationText(d.tracks.sumOf { it.durationMs })),
                            playing = app.isPlaying(a.uri),
                            onPlay = { app.togglePlay(a.uri) },
                            shuffle = shuffle,
                            onShuffle = { app.c.player.setShuffle(!shuffle) },
                        ) {
                            val on = saved.value == true
                            IconButton(if (on) Icons.Check else Icons.Plus, if (on) "Remove from Your Library" else "Add to Your Library",
                                { app.setSaved(a.uri, !on) { saved.value = it } }, tint = if (on) Palette.accent else Palette.text, iconSize = 24.dp)
                            IconButton(Icons.Share, "Share", { app.share(a.uri, a.name) }, tint = Palette.text)
                        }
                    }
                    val discs = discs(d.tracks)
                    for (disc in discs) {
                        if (discs.size > 1) item(key = "disc" + disc.number) { DiscHeader(disc.number) }
                        items(disc.tracks, key = { "t" + it.value.id + it.index }) { (index, t) ->
                            TrackRow(
                                t,
                                onClick = { app.playAt(a.uri, index) },
                                onMore = { app.menu = app.trackMenu(t.copy(album = t.album ?: albumRef(a))) },
                                playing = player?.track?.uri == t.uri,
                                liked = t.uri in liked,
                                number = t.trackNumber ?: (index + 1),
                            )
                        }
                    }
                    item(key = "release") {
                        Text(dotted(a.releaseDate, songs(d.tracks.size)), style = Type.caption, color = Palette.dim,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp))
                    }
                    a.artists.firstOrNull()?.let { artist ->
                        albumCarousel(app, "More by ${artist.name}", d.more, action = "See discography", onAction = { app.open(Route.Artist(artist.id)) })
                    }
                }
            }
        }
        TopBar(app, title = data.value?.album?.name, solid = scrolled)
    }
}

/** One disc of an album; [tracks] keep their index in the whole album, which playback uses. */
internal class Disc(val number: Int, val tracks: List<IndexedValue<Track>>)

/**
 * Splits an album's tracks into discs. Spotify's disc_number decides; a track without one
 * starts a new disc when its track number drops back (1, 2, 3, 1, 2…).
 */
internal fun discs(tracks: List<Track>): List<Disc> {
    val groups = mutableListOf<MutableList<IndexedValue<Track>>>()
    for (t in tracks.withIndex()) {
        val prev = groups.lastOrNull()?.last()?.value
        val (a, b) = prev to t.value
        val split = when {
            a == null -> true
            a.discNumber != null && b.discNumber != null -> a.discNumber != b.discNumber
            a.trackNumber != null && b.trackNumber != null -> b.trackNumber <= a.trackNumber
            else -> false
        }
        if (split) groups += mutableListOf(t) else groups.last() += t
    }
    return groups.mapIndexed { i, g -> Disc(g.first().value.discNumber ?: (i + 1), g) }
}

@Composable
private fun DiscHeader(number: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Disc, null, Modifier.size(18.dp), tint = Palette.sub)
        }
        Spacer(Modifier.width(13.dp))
        Text("Disc $number", style = Type.label, color = Palette.sub)
    }
}

private fun albumRef(a: Album) = AlbumRef(a.id, a.name, a.images)

fun AlbumType.label() = when (this) {
    AlbumType.Album -> "Album"
    AlbumType.Single -> "Single"
    AlbumType.Compilation -> "Compilation"
}
