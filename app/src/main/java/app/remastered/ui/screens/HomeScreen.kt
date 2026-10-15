package app.remastered.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remastered.core.Connection
import app.remastered.core.model.TimeRange
import app.remastered.core.model.best
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.core.warehouse.recentContexts
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.Route
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Card
import app.remastered.ui.components.Chip
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.LikedArt
import app.remastered.ui.components.Loading
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.SectionHeader
import app.remastered.ui.components.TrackRow
import app.remastered.ui.history.onThisDay
import app.remastered.ui.history.rememberHomeHistory
import app.remastered.ui.history.warehouseOnRepeat
import app.remastered.ui.load
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.LocalTime

/** A playlist, album or artist shown on Home, resolved from a recently played context. */
data class HomeItem(val uri: String, val title: String, val subtitle: String, val imageUrl: String?, val round: Boolean)

private data class HomeData(
    val shortcuts: List<HomeItem>,
    val jumpBackIn: List<HomeItem>,
    val topArtists: List<HomeItem>,
    val onRepeat: List<app.remastered.core.model.Track>,
)

/** Contexts Home resolves for its shortcuts and "Jump back in". */
private const val HOME_CONTEXTS = 16

/**
 * Home is built from the user's own listening: Spotify's personalised feed isn't available
 * to apps. Shortcuts and "Jump back in" are recently played contexts; then top artists and
 * top tracks for the last four weeks.
 *
 * Spotify only returns the last 50 plays, which can all be one album. With the warehouse set
 * up, older contexts from the stored history fill in after them.
 */
private suspend fun loadHome(app: AppState): HomeData = coroutineScope {
    val c = app.c.catalog
    val recent = async { c.recentlyPlayed(50) }
    val artists = async { runCatching { c.topArtists(TimeRange.Short, 12) }.getOrDefault(emptyList()) }
    val tracks = async { runCatching { c.topTracks(TimeRange.Short, 8) }.getOrDefault(emptyList()) }
    val older = async {
        if (!app.c.warehouse.state.value.isUsable) emptyList()
        else runCatching { recentContexts(HOME_CONTEXTS) { before, limit -> app.c.warehouse.api.plays(limit, beforeMs = before) } }
            .getOrDefault(emptyList())
    }
    val plays = recent.await()
    val fromPlays = plays.mapNotNull { it.context }.distinctBy { it.uri }
    val contexts = (fromPlays + older.await()).distinctBy { it.uri }.take(HOME_CONTEXTS)
    val byUri = contexts.map { ctx ->
        async {
            val id = ctx.uri.substringAfterLast(':')
            runCatching {
                when (ctx.type) {
                    "playlist" -> c.playlist(id).let { HomeItem(it.uri, it.name, "Playlist  ·  ${it.owner.displayName}", it.images.best()?.url, false) }
                    "album" -> c.album(id).album.let { a -> HomeItem(a.uri, a.name, "Album  ·  ${a.artists.joinToString { it.name }}", a.images.best()?.url, false) }
                    "artist" -> c.artist(id).let { HomeItem(it.uri, it.name, "Artist", it.images.best()?.url, true) }
                    else -> null
                }
            }.getOrNull()
        }
    }.awaitAll().filterNotNull().associateBy { it.uri }
    // Plays without a context, or whose context the API won't return (Spotify-owned
    // playlists in Development Mode), fall back to the track's album so the grid stays full.
    val fromRecent = plays.mapNotNull { h ->
        h.context?.uri?.let(byUri::get)
            ?: h.track.album?.let { a -> HomeItem(a.uri, a.name, "Album  ·  ${h.track.artistNames}", a.images.best()?.url, false) }
    }
    // Older contexts whose lookup failed are left out: the warehouse has no album to fall back to.
    val resolved = (fromRecent + contexts.mapNotNull { byUri[it.uri] }).distinctBy { it.uri }
    val liked = HomeItem("liked", "Liked Songs", "", null, false)
    HomeData(
        shortcuts = (listOf(liked) + resolved).take(8),
        // Only what the shortcuts don't already show; the section hides when that's nothing.
        jumpBackIn = resolved.drop(7),
        topArtists = artists.await().map { HomeItem(it.uri, it.name, "", it.images.best()?.url, true) },
        onRepeat = tracks.await(),
    )
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomeScreen(app: AppState) {
    val home = load(app.c.screens, "home") { loadHome(app) }
    val connection by app.c.player.connection.collectAsState()
    val liked by app.c.liked.uris.collectAsState()
    val history = rememberHomeHistory(app)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(app.me?.images?.best(80)?.url, Modifier.size(36.dp).clickable(onClickLabel = "Settings") { app.open(Route.Settings) }, CircleShape)
                Text(greeting(), style = Type.title, color = Palette.text, modifier = Modifier.padding(start = 14.dp))
            }
        }
        item { ConnectionBanner(app, connection) }
        item { HistoryShortcuts(app) }
        when (val s = home.state) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { ErrorMessage(s.message, home.retry) }
            is Load.Done -> {
                val d = s.value
                item {
                    Column(Modifier.padding(horizontal = 20.dp).padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.shortcuts.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { item -> Shortcut(app, item, Modifier.weight(1f)) }
                                if (pair.size == 1) Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (d.jumpBackIn.isNotEmpty()) item {
                    Carousel(app, "Jump back in", d.jumpBackIn, size = 148)
                }
                if (d.topArtists.isNotEmpty()) item {
                    Carousel(app, "Your top artists", d.topArtists, size = 112, action = "This month")
                }
                warehouseOnRepeat(app, history, liked)
                if (history.useWebOnRepeat && d.onRepeat.isNotEmpty()) {
                    item { SectionHeader("On repeat", Modifier.padding(top = 30.dp, bottom = 6.dp), action = "Last 4 weeks") }
                    items(d.onRepeat, key = { "rep" + it.id }) { t ->
                        TrackRow(t, onClick = { app.play(t.uri) }, onMore = { app.menu = app.trackMenu(t) }, liked = t.uri in liked)
                    }
                }
                onThisDay(app, history)
            }
        }
    }
}

@Composable
private fun Shortcut(app: AppState, item: HomeItem, modifier: Modifier) {
    val playing = app.isPlaying(item.uri)
    Row(
        modifier.height(58.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(12.dp))
            .clickable { if (item.uri == "liked") app.open(Route.LikedSongs) else app.openUri(item.uri) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.uri == "liked") LikedArt(Modifier.size(58.dp))
        else Artwork(item.imageUrl, Modifier.size(58.dp))
        Text(item.title, style = Type.label.copy(fontWeight = Type.section.fontWeight, fontSize = 13.5.sp, lineHeight = 16.sp),
            color = Palette.text, maxLines = 2, modifier = Modifier.weight(1f).padding(horizontal = 11.dp))
        if (playing) Box(Modifier.padding(end = 10.dp).size(6.dp).clip(CircleShape).background(Palette.accent))
    }
}

@Composable
private fun Carousel(app: AppState, title: String, items: List<HomeItem>, size: Int, action: String? = null) {
    Column(Modifier.padding(top = 30.dp)) {
        SectionHeader(title, action = action)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 14.dp)) {
            items(items, key = { it.uri }) { item ->
                Card(item.title, item.subtitle.ifEmpty { null }, item.imageUrl, { app.openUri(item.uri) },
                    size = size.dp, round = item.round, centered = item.round)
            }
        }
    }
}

/** Stats and Wrapped from the user's listening history; nothing when the warehouse isn't set up. */
@Composable
private fun HistoryShortcuts(app: AppState) {
    val state by app.c.warehouse.state.collectAsState()
    val missing by app.c.warehouse.missing.collectAsState()
    if (!state.isUsable) return
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HistoryShortcut("Your stats", Icons.Sort, Modifier.weight(1f)) { app.open(Route.Stats) }
        if (WarehouseFeature.WrappedRanges !in missing) HistoryShortcut("Your Wrapped", Icons.Play, Modifier.weight(1f)) { app.open(Route.Wrapped) }
        else Box(Modifier.weight(1f))
    }
}

@Composable
private fun HistoryShortcut(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Palette.accent)
        Text(title, style = Type.label.copy(fontWeight = Type.section.fontWeight), color = Palette.text, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Explains why playback controls aren't working yet, with the fix. */
@Composable
private fun ConnectionBanner(app: AppState, connection: Connection) {
    val (title, body) = when (connection) {
        Connection.SpotifyMissing -> "Spotify isn't installed" to "Install Spotify from the Play Store and log in. Remastered plays through it."
        Connection.NeedsAuth -> "Connect to Spotify" to "Allow Remastered to control playback in the Spotify app."
        is Connection.Failed -> "Can't reach Spotify" to connection.message
        else -> return
    }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(Palette.accentSoft).border(1.dp, Palette.accent.copy(alpha = .35f), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text(title, style = Type.body, color = Palette.text)
        Text(body, style = Type.caption, color = Palette.sub, modifier = Modifier.padding(top = 4.dp))
        Chip("Connect", selected = true, onClick = { app.c.player.connect() }, modifier = Modifier.padding(top = 12.dp))
    }
}
