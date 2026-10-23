package app.remastered.ui.screens

import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.remastered.core.model.Album
import app.remastered.core.model.AlbumGroup
import app.remastered.core.model.Artist
import app.remastered.core.model.Page
import app.remastered.core.model.best
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.Route
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Card
import app.remastered.ui.components.Chip
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.Loading
import app.remastered.ui.components.PlayButton
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.SectionHeader
import app.remastered.ui.components.SubText
import app.remastered.ui.components.TitleText
import app.remastered.ui.components.TrackRow
import app.remastered.ui.history.RadioIcon
import app.remastered.ui.history.ArtistConcerts
import app.remastered.ui.history.YourPlays
import app.remastered.ui.history.canStartRadio
import app.remastered.ui.history.startArtistRadio
import app.remastered.ui.load
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private class ArtistData(val artist: Artist, val discography: Map<AlbumGroup, Page<Album>>)

private val DiscographyTabs = listOf(AlbumGroup.Album to "Albums", AlbumGroup.Single to "Singles & EPs", AlbumGroup.Compilation to "Compilations")

/**
 * Spotify removed artist top tracks for apps (Feb 2026), so the artist page leads with the
 * user's own liked songs by the artist, then the discography.
 */
@Composable
fun ArtistScreen(app: AppState, id: String) {
    val data = load(app.c.screens, "artist:$id", maxAgeMs = 10 * 60_000) {
        coroutineScope {
            val artist = async { app.c.catalog.artist(id) }
            val groups = (DiscographyTabs.map { it.first } + AlbumGroup.AppearsOn).map { g ->
                g to async {
                    runCatching { app.c.catalog.artistAlbums(id, setOf(g), 0, 10) }
                        .onFailure { Log.w("ArtistScreen", "Discography ${g.api} failed: ${it.javaClass.simpleName} ${it.message}") }
                        .getOrDefault(Page.of(emptyList()))
                }
            }
            ArtistData(artist.await(), groups.associate { (g, d) -> g to d.await() })
        }
    }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 700 } }
    Box(Modifier.fillMaxSize()) {
        when (val s = data.state) {
            Load.Loading -> Loading(Modifier.padding(top = 120.dp))
            is Load.Failed -> ErrorMessage(s, data.retry, Modifier.padding(top = 120.dp))
            is Load.Done -> ArtistBody(app, s.value, list)
        }
        TopBar(app, title = data.value?.artist?.name, solid = scrolled)
    }
}

@Composable
private fun ArtistBody(app: AppState, d: ArtistData, list: LazyListState) {
    val a = d.artist
    val player by app.c.player.state.collectAsState()
    LaunchedEffect(Unit) { app.c.liked.load() }
    val likedTracks by app.c.liked.tracks.collectAsState()
    val mine = remember(likedTracks, a.id) { likedTracks.filter { t -> t.artists.any { it.id == a.id } } }
    val following = app.rememberSaved(a.uri)
    val shuffle = app.shuffleOn()
    var showMine by rememberSaveable(a.id) { mutableStateOf(false) }
    // The API serves 10 releases per request, so each tab's row fetches the next page as it
    // nears its end; the cached ArtistData keeps only the first page.
    val disco = remember(a.id) { mutableStateMapOf<AlbumGroup, Page<Album>>().apply { putAll(d.discography) } }
    var tab by rememberSaveable(a.id) { mutableStateOf(DiscographyTabs.firstOrNull { disco[it.first]?.items.orEmpty().isNotEmpty() }?.first ?: AlbumGroup.Album) }
    val row = remember(a.id, tab) { LazyListState() }
    val nearEnd by remember(row) { derivedStateOf { (row.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= row.layoutInfo.totalItemsCount - 3 } }
    val loaded = disco[tab]?.items?.size ?: 0
    LaunchedEffect(tab, nearEnd, loaded) {
        val p = disco[tab] ?: return@LaunchedEffect
        if (!nearEnd || !p.hasMore) return@LaunchedEffect
        runCatching { app.c.catalog.artistAlbums(a.id, setOf(tab), p.items.size, 10) }
            .onSuccess { next -> disco[tab] = Page((p.items + next.items).distinctBy { it.id }, 0, next.total, next.hasMore && next.items.isNotEmpty()) }
            .onFailure { Log.w("ArtistScreen", "Discography ${tab.api} page failed: ${it.javaClass.simpleName} ${it.message}") }
    }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = ScreenPadding) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().aspectRatio(1.15f)) {
                Artwork(a.images.best(640)?.url, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .55f to Palette.base.copy(alpha = .35f), 1f to Palette.base)))
                Text(a.name, style = Type.display.copy(fontSize = Type.display.fontSize * 1.5f, lineHeight = Type.display.lineHeight * 1.4f),
                    color = Palette.text, maxLines = 2, modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 10.dp))
            }
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val on = following.value == true
                Chip(if (on) "Following" else "Follow", selected = false, onClick = { app.setSaved(a.uri, !on) { following.value = it } })
                Spacer(Modifier.width(8.dp))
                if (app.canStartRadio()) {
                    Chip("Radio", selected = false, onClick = { app.startArtistRadio(a.id) }, icon = RadioIcon)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(Icons.Share, "Share", { app.share(a.uri, a.name) }, tint = Palette.text)
                Spacer(Modifier.weight(1f))
                IconButton(Icons.Shuffle, if (shuffle) "Shuffle on" else "Shuffle off", { app.c.player.setShuffle(!shuffle) },
                    tint = if (shuffle) Palette.accent else Palette.text, active = shuffle, iconSize = 24.dp)
                Spacer(Modifier.width(10.dp))
                PlayButton(app.isPlaying(a.uri), { app.togglePlay(a.uri) })
            }
        }
        item(key = "your-plays") { YourPlays(app, a.id, a.name) }
        item(key = "concerts") { ArtistConcerts(app, a.id, a.name) }
        if (mine.isNotEmpty()) {
            item(key = "mine") {
                Column(Modifier.padding(top = 22.dp)) {
                    SectionHeader("In your library", small = true)
                    Row(
                        Modifier.padding(horizontal = 20.dp).padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(18.dp))
                            .clickable { showMine = !showMine }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            Artwork(a.images.best(160)?.url, Modifier.size(56.dp), CircleShape)
                            Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(Palette.accent), contentAlignment = Alignment.Center) {
                                Icon(Icons.HeartFilled, null, Modifier.size(11.dp), tint = Color.White)
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            TitleText(if (mine.size == 1) "You've liked 1 song" else "You've liked ${mine.size} songs")
                            SubText("By ${a.name}", Modifier.padding(top = 3.dp))
                        }
                        Icon(if (showMine) Icons.ChevronDown else Icons.Forward, null, Modifier.size(20.dp), tint = Palette.sub)
                    }
                }
            }
            if (showMine) items(mine, key = { "mine" + it.id }) { t ->
                TrackRow(t, onClick = { app.play(t.uri) }, onMore = { app.menu = app.trackMenu(t) },
                    playing = player?.track?.uri == t.uri, liked = true)
            }
        }
        val shown = disco[tab]?.items.orEmpty()
        if (DiscographyTabs.any { disco[it.first]?.items.orEmpty().isNotEmpty() }) item(key = "disco") {
            Column(Modifier.padding(top = 30.dp)) {
                SectionHeader("Discography", small = true)
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
                    items(DiscographyTabs.filter { disco[it.first]?.items.orEmpty().isNotEmpty() }, key = { it.first }) { (g, label) ->
                        Chip(label, selected = g == tab, onClick = { tab = g })
                    }
                }
                LazyRow(state = row, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 16.dp)) {
                    items(shown, key = { it.id }) { al ->
                        Card(al.name, al.year, al.images.best()?.url, { app.open(Route.Album(al.id)) })
                    }
                }
            }
        }
        albumCarousel(app, "Appears on", d.discography[AlbumGroup.AppearsOn]?.items.orEmpty())
    }
}
