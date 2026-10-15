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
import app.remastered.core.Catalog
import app.remastered.core.Connection
import app.remastered.core.model.PlayHistory
import app.remastered.core.model.TimeRange
import app.remastered.core.model.best
import app.remastered.core.warehouse.HomeEntry
import app.remastered.core.warehouse.HomeFeed
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
    val aroundNow: List<HomeItem>,
    val rediscover: List<HomeItem>,
    /** The local hour "Around this time" was ranked for. */
    val hour: Int?,
    val topArtists: List<HomeItem>,
    val onRepeat: List<app.remastered.core.model.Track>,
)

/** Tiles beside Liked Songs. */
private const val SHORTCUT_TILES = 7
/** Cards in each carousel. */
private const val ROW_CARDS = 10
/** Extra candidates per row looked up, so a failed lookup doesn't leave a row short. */
private const val SLACK = 2
/** Contexts Home resolves for its shortcuts and "Jump back in" without the warehouse's ranking. */
private const val HOME_CONTEXTS = 16

/** Home's rows, each item shown once, in row order. */
internal data class HomeRows<T>(val shortcuts: List<T>, val jumpBackIn: List<T>, val aroundNow: List<T>, val rediscover: List<T>)

/** Takes each row's first [shortcuts] / [cards] items that no earlier row has. */
internal fun <T> arrangeHome(rows: HomeRows<T>, uri: (T) -> String, shortcuts: Int, cards: Int): HomeRows<T> {
    val shown = HashSet<String>()
    fun List<T>.fresh(n: Int): List<T> {
        val out = ArrayList<T>(n)
        for (item in this) {
            if (out.size >= n) break
            if (shown.add(uri(item))) out += item
        }
        return out
    }
    return HomeRows(rows.shortcuts.fresh(shortcuts), rows.jumpBackIn.fresh(cards), rows.aroundNow.fresh(cards), rows.rediscover.fresh(cards))
}

/** A context's tile from the Web API, or null when the API won't return it (Spotify-owned playlists). */
private suspend fun lookup(c: Catalog, uri: String, type: String): HomeItem? {
    val id = uri.substringAfterLast(':')
    return runCatching {
        when (type) {
            "playlist" -> c.playlist(id).let { HomeItem(it.uri, it.name, "Playlist  ·  ${it.owner.displayName}", it.images.best()?.url, false) }
            "album" -> c.album(id).album.let { a -> HomeItem(a.uri, a.name, "Album  ·  ${a.artists.joinToString { it.name }}", a.images.best()?.url, false) }
            "artist" -> c.artist(id).let { HomeItem(it.uri, it.name, "Artist", it.images.best()?.url, true) }
            else -> null
        }
    }.getOrNull()
}

/**
 * Tiles for [plays], newest first: each play's context, or its track's album when it has none or
 * the API won't return it, so the grid stays full.
 */
private suspend fun fromPlays(c: Catalog, plays: List<PlayHistory>, limit: Int): List<HomeItem> = coroutineScope {
    val contexts = plays.mapNotNull { it.context }.distinctBy { it.uri }.take(limit)
    val byUri = contexts.map { ctx -> async { lookup(c, ctx.uri, ctx.type) } }.awaitAll().filterNotNull().associateBy { it.uri }
    plays.mapNotNull { h ->
        h.context?.uri?.let(byUri::get)
            ?: h.track.album?.let { a -> HomeItem(a.uri, a.name, "Album  ·  ${h.track.artistNames}", a.images.best()?.url, false) }
    }.distinctBy { it.uri }
}

/**
 * Home is built from the user's own listening: Spotify's personalised feed isn't available
 * to apps. With the warehouse, its ranked rows ([HomeFeed]); without it, Spotify's recently
 * played contexts. Then top artists and top tracks for the last four weeks.
 */
private suspend fun loadHome(app: AppState): HomeData = coroutineScope {
    val c = app.c.catalog
    val recent = async { c.recentlyPlayed(50) }
    val artists = async { runCatching { c.topArtists(TimeRange.Short, 12) }.getOrDefault(emptyList()) }
    val tracks = async { runCatching { c.topTracks(TimeRange.Short, 8) }.getOrDefault(emptyList()) }
    val feed = async {
        if (!app.c.warehouse.supports(WarehouseFeature.Home)) null
        else runCatching { app.c.warehouse.api.home() }.getOrNull()
    }
    val rows = feed.await()?.let { fromFeed(c, it, recent.await()) } ?: fromRecent(app, recent.await())
    HomeData(
        shortcuts = listOf(HomeItem("liked", "Liked Songs", "", null, false)) + rows.shortcuts,
        jumpBackIn = rows.jumpBackIn,
        aroundNow = rows.aroundNow,
        rediscover = rows.rediscover,
        hour = feed.await()?.hour,
        topArtists = artists.await().map { HomeItem(it.uri, it.name, "", it.images.best()?.url, true) },
        onRepeat = tracks.await(),
    )
}

/**
 * The warehouse's rows. Entries it has a name and art for are used as they are; the rest
 * (playlists and artists, which it has no art for) are looked up, and dropped if that fails.
 * Plays it hasn't stored yet (it polls every 30 minutes) go first in "Jump back in".
 */
private suspend fun fromFeed(c: Catalog, feed: HomeFeed, plays: List<PlayHistory>): HomeRows<HomeItem> = coroutineScope {
    val newest = feed.jumpBackIn.maxOfOrNull { it.lastPlayedMs ?: 0 } ?: 0
    val fresh = async { fromPlays(c, plays.filter { it.playedAtEpochMs > newest }, limit = 4) }
    // Only what can be shown is looked up: each row's candidates as arranged, plus some slack.
    val candidates = arrangeHome(
        HomeRows(feed.shortcuts, feed.jumpBackIn, feed.aroundNow, feed.rediscover), { it.uri }, SHORTCUT_TILES + SLACK, ROW_CARDS + SLACK,
    ).let { it.shortcuts + it.jumpBackIn + it.aroundNow + it.rediscover }
    val items = candidates.map { e ->
        async {
            val name = e.name
            if (name != null && e.imageUrl != null) HomeItem(e.uri, name, subtitle(e), e.imageUrl, e.type == "artist")
            else lookup(c, e.uri, e.type)
        }
    }.awaitAll().filterNotNull().associateBy { it.uri }
    fun List<HomeEntry>.resolved() = mapNotNull { items[it.uri] }
    arrangeHome(
        HomeRows(feed.shortcuts.resolved(), fresh.await() + feed.jumpBackIn.resolved(), feed.aroundNow.resolved(), feed.rediscover.resolved()),
        { it.uri }, SHORTCUT_TILES, ROW_CARDS,
    )
}

private fun subtitle(e: HomeEntry) = when (e.type) {
    "album" -> listOfNotNull("Album", e.artist).joinToString("  ·  ")
    "artist" -> "Artist"
    else -> "Playlist"
}

/**
 * Without the warehouse's ranking: Spotify's last 50 plays, which can all be one album, then
 * (with an older warehouse) older contexts from the stored history.
 */
private suspend fun fromRecent(app: AppState, plays: List<PlayHistory>): HomeRows<HomeItem> = coroutineScope {
    val c = app.c.catalog
    val older = async {
        if (!app.c.warehouse.state.value.isUsable) emptyList()
        else runCatching { recentContexts(HOME_CONTEXTS) { before, limit -> app.c.warehouse.api.plays(limit, beforeMs = before) } }
            .getOrDefault(emptyList())
    }
    val recentItems = async { fromPlays(c, plays, HOME_CONTEXTS) }
    val known = plays.mapNotNull { it.context?.uri }.toSet()
    val olderItems = older.await().filter { it.uri !in known }.take(HOME_CONTEXTS)
        .map { ctx -> async { lookup(c, ctx.uri, ctx.type) } }.awaitAll().filterNotNull()
    // Older contexts whose lookup failed are left out: the warehouse has no album to fall back to.
    val resolved = (recentItems.await() + olderItems).distinctBy { it.uri }
    // "Jump back in" has only what the shortcuts don't already show; it hides when that's nothing.
    arrangeHome(HomeRows(resolved, resolved, emptyList(), emptyList()), { it.uri }, SHORTCUT_TILES, ROW_CARDS)
}

/** "Around this time"'s note: the part of the day it was ranked for. */
private fun partOfDay(hour: Int) = when (hour) {
    in 5..11 -> "Mornings"
    in 12..17 -> "Afternoons"
    in 18..22 -> "Evenings"
    else -> "Late nights"
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
                if (d.aroundNow.isNotEmpty()) item {
                    Carousel(app, "Around this time", d.aroundNow, size = 148, action = d.hour?.let(::partOfDay))
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
                if (d.rediscover.isNotEmpty()) item {
                    Carousel(app, "Rediscover", d.rediscover, size = 148, action = "Not played lately")
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
