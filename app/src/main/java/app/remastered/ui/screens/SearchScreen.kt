package app.remastered.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.remastered.core.RecentSearches
import app.remastered.core.model.Album
import app.remastered.core.model.Artist
import app.remastered.core.model.Playlist
import app.remastered.core.model.SearchResults
import app.remastered.core.model.SearchType
import app.remastered.core.model.TimeRange
import app.remastered.core.model.Track
import app.remastered.core.model.best
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.Load
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Card
import app.remastered.ui.components.Chip
import app.remastered.ui.components.CollectionRow
import app.remastered.ui.components.EmptyMessage
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.Loading
import app.remastered.ui.components.PlayButton
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.SectionHeader
import app.remastered.ui.components.TrackRow
import app.remastered.ui.components.dotted
import app.remastered.ui.history.FromYourHistory
import app.remastered.ui.load
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.delay

private enum class SearchTab(val label: String, val type: SearchType?) {
    Top("Top", null), Songs("Songs", SearchType.Track), Artists("Artists", SearchType.Artist),
    Albums("Albums", SearchType.Album), Playlists("Playlists", SearchType.Playlist),
}

private val GenreColors = listOf(0xFFC93B6E, 0xFF5262D6, 0xFF14917A, 0xFFD2692B, 0xFF8A4FD1, 0xFF2F8CC4).map(::Color)

@Composable
fun SearchScreen(app: AppState) {
    var query by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var tab by rememberSaveable { mutableStateOf(SearchTab.Top) }
    val active = focused || query.isNotEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding) {
        item(key = "title") {
            Column(Modifier.statusBarsPadding()) {
                if (!active) Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Search", style = Type.title, color = Palette.text, modifier = Modifier.weight(1f))
                    Artwork(app.me?.images?.best(80)?.url, Modifier.size(36.dp).clickable(onClickLabel = "Settings") { app.open(Route.Settings) }, CircleShape)
                }
                Row(
                    Modifier.padding(start = 20.dp, end = 20.dp, top = if (active) 10.dp else 18.dp).fillMaxWidth().height(50.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (active) Palette.surface else Palette.chipOn)
                        .border(1.5.dp, if (active) Palette.accent.copy(alpha = .55f) else Color.Transparent, RoundedCornerShape(14.dp))
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) IconButton(Icons.Back, "Back", { query = ""; focusManager.clearFocus() }, tint = Palette.text)
                    else Icon(Icons.Search, null, Modifier.padding(horizontal = 10.dp).size(22.dp), tint = Palette.onChipOn)
                    Box(Modifier.weight(1f).padding(start = 6.dp)) {
                        if (query.isEmpty()) Text("What do you want to listen to?", style = Type.bodyRegular, color = if (active) Palette.sub else Color(0xFF4A5069))
                        BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = Type.bodyRegular.copy(color = if (active) Palette.text else Palette.onChipOn),
                            cursorBrush = SolidColor(Palette.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
                        )
                    }
                    if (query.isNotEmpty()) IconButton(Icons.Close, "Clear search", { query = ""; focus.requestFocus() }, tint = Palette.text)
                }
            }
        }
        if (query.isBlank()) browse(app, onGenre = { query = "genre:\"$it\""; tab = SearchTab.Top })
        else results(app, query.trim(), tab) { tab = it }
    }
}

/** The empty state: recent searches, then genres from the user's top artists. */
private fun LazyListScope.browse(app: AppState, onGenre: (String) -> Unit) {
    item(key = "recent") { RecentList(app) }
    item(key = "genres") { Genres(app, onGenre) }
}

@Composable
private fun RecentList(app: AppState) {
    val recent by app.c.recentSearches.entries.collectAsState()
    val enabled by app.c.settings.saveRecentSearches.value.collectAsState()
    if (!enabled || recent.isEmpty()) return
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader("Recent searches", action = "Clear", onAction = { app.c.recentSearches.clear() }, small = true)
        Spacer(Modifier.height(6.dp))
        recent.forEach { e ->
            CollectionRow(e.title, e.subtitle,
                { Artwork(e.imageUrl, Modifier.size(48.dp), if (e.round) CircleShape else RoundedCornerShape(9.dp)) },
                { app.openUri(e.uri) },
                trailing = { IconButton(Icons.Close, "Remove ${e.title}", { app.c.recentSearches.remove(e.uri) }, tint = Palette.sub, iconSize = 18.dp) })
        }
    }
}

@Composable
private fun Genres(app: AppState, onGenre: (String) -> Unit) {
    val genres = load(app.c.screens, "genres", maxAgeMs = 30 * 60_000) {
        val artists = app.c.catalog.topArtists(TimeRange.Medium, 30)
        // Most common genres across top artists, with the art of the first artist in each.
        artists.flatMap { a -> a.genres.map { it to a } }.groupBy({ it.first }, { it.second })
            .entries.sortedByDescending { it.value.size }.take(8).map { it.key to it.value.first().images.best(160)?.url }
    }
    val list = genres.value.orEmpty()
    if (list.isEmpty()) return
    Column(Modifier.padding(top = 22.dp)) {
        SectionHeader("Your genres", small = true)
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            list.withIndex().chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (i, g) -> GenreTile(g.first, g.second, GenreColors[i % GenreColors.size], { onGenre(g.first) }, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GenreTile(name: String, imageUrl: String?, color: Color, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.height(96.dp).clip(RoundedCornerShape(16.dp))
        .background(Brush.linearGradient(listOf(color, color.copy(red = color.red * .8f, green = color.green * .8f, blue = color.blue * .9f))))
        .clickable(onClick = onClick)) {
        Text(name.replaceFirstChar { it.uppercase() }, style = Type.sectionSmall, color = Color.White, maxLines = 2,
            modifier = Modifier.padding(14.dp).padding(end = 40.dp))
        Artwork(imageUrl, Modifier.align(Alignment.BottomEnd).padding(end = 0.dp).size(64.dp).clip(RoundedCornerShape(10.dp)), RoundedCornerShape(10.dp))
    }
}

private fun LazyListScope.results(app: AppState, query: String, tab: SearchTab, onTab: (SearchTab) -> Unit) {
    item(key = "tabs") {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
            items(SearchTab.entries) { t -> Chip(t.label, selected = t == tab, onClick = { onTab(t) }) }
        }
    }
    item(key = "results-$tab") { Results(app, query, tab) }
    if (tab == SearchTab.Top) item(key = "history-results") { FromYourHistory(app, query) }
}

@Composable
private fun Results(app: AppState, query: String, tab: SearchTab) {
    val results = load(app.c.screens, "search:$tab:$query", maxAgeMs = 5 * 60_000) {
        delay(300) // debounce typing
        val types = tab.type?.let(::setOf) ?: SearchType.entries.toSet()
        app.c.catalog.search(query, types, 0, 10)
    }
    val liked by app.c.liked.uris.collectAsState()
    val player by app.c.player.state.collectAsState()
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        when (val s = results.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorMessage(s, results.retry)
            is Load.Done -> {
                val r = s.value
                if (r.isEmpty()) { EmptyMessage("No results for \"$query\"", "Check the spelling, or try fewer words."); return@Column }
                fun saveRecent(e: RecentSearches.Entry) = app.c.recentSearches.add(e)
                when (tab) {
                    SearchTab.Top -> {
                        topResult(r, query)?.let { TopResult(app, it, ::saveRecent) }
                        r.tracks?.items?.takeIf { it.isNotEmpty() }?.let { tracks ->
                            SectionHeader("Songs", Modifier.padding(top = 22.dp, bottom = 4.dp), small = true)
                            tracks.take(5).forEach { t -> SongRow(app, t, liked, player?.track?.uri) }
                        }
                        r.artists?.items?.takeIf { it.isNotEmpty() }?.let { Shelf("Artists") { items(it, key = { a -> a.id }) { a -> ArtistCard(app, a, ::saveRecent) } } }
                        r.albums?.items?.takeIf { it.isNotEmpty() }?.let { Shelf("Albums") { items(it, key = { a -> a.id }) { a -> AlbumCard(app, a, ::saveRecent) } } }
                        r.playlists?.items?.takeIf { it.isNotEmpty() }?.let { Shelf("Playlists") { items(it, key = { p -> p.id }) { p -> PlaylistCard(app, p, ::saveRecent) } } }
                    }
                    SearchTab.Songs -> r.tracks?.items.orEmpty().forEach { t -> SongRow(app, t, liked, player?.track?.uri) }
                    SearchTab.Artists -> r.artists?.items.orEmpty().forEach { a ->
                        CollectionRow(a.name, "Artist", { Artwork(a.images.best(120)?.url, Modifier.size(56.dp), CircleShape) },
                            { saveRecent(a.entry()); app.openUri(a.uri) })
                    }
                    SearchTab.Albums -> r.albums?.items.orEmpty().forEach { a ->
                        CollectionRow(a.name, dotted(a.type.label(), a.year, a.artists.joinToString { it.name }),
                            { Artwork(a.images.best(120)?.url, Modifier.size(56.dp), RoundedCornerShape(10.dp)) },
                            { saveRecent(a.entry()); app.openUri(a.uri) })
                    }
                    SearchTab.Playlists -> r.playlists?.items.orEmpty().forEach { p ->
                        CollectionRow(p.name, dotted("Playlist", p.owner.displayName),
                            { Artwork(p.images.best(120)?.url, Modifier.size(56.dp), RoundedCornerShape(10.dp)) },
                            { saveRecent(p.entry()); app.openUri(p.uri) })
                    }
                }
                if (tab != SearchTab.Top) Text("Spotify returns up to 10 results per search for apps like this one.",
                    style = Type.caption, color = Palette.dim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            }
        }
    }
}

private fun SearchResults.isEmpty() =
    tracks?.items.isNullOrEmpty() && artists?.items.isNullOrEmpty() && albums?.items.isNullOrEmpty() && playlists?.items.isNullOrEmpty()

/** The big card: an artist whose name matches the query, else the first song. */
private fun topResult(r: SearchResults, query: String): Any? {
    val q = query.lowercase()
    return r.artists?.items?.firstOrNull { it.name.lowercase() == q }
        ?: r.artists?.items?.firstOrNull { it.name.lowercase().startsWith(q) }
        ?: r.tracks?.items?.firstOrNull()
        ?: r.artists?.items?.firstOrNull()
}

@Composable
private fun TopResult(app: AppState, item: Any, saveRecent: (RecentSearches.Entry) -> Unit) {
    val (title, kind, image, round, uri) = when (item) {
        is Artist -> TopInfo(item.name, "Artist", item.images.best(300)?.url, true, item.uri)
        is Track -> TopInfo(item.name, dotted("Song", item.artistNames), item.album?.images?.best(300)?.url, false, item.uri)
        else -> return
    }
    Row(
        Modifier.padding(horizontal = 20.dp).padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Palette.surface).border(1.dp, Palette.line, RoundedCornerShape(20.dp))
            .clickable { if (item is Artist) { saveRecent(item.entry()); app.openUri(uri) } else app.play(uri) }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(image, Modifier.size(84.dp), if (round) CircleShape else RoundedCornerShape(12.dp))
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = Type.headline, color = Palette.text, maxLines = 2)
            Text(kind, style = Type.label, color = Palette.sub, maxLines = 1,
                modifier = Modifier.padding(top = 8.dp).clip(CircleShape).background(Palette.selected).padding(horizontal = 10.dp, vertical = 4.dp))
        }
        PlayButton(app.isPlaying(uri), { if (item is Artist) app.togglePlay(uri) else app.play(uri) }, size = 52.dp)
    }
}

private data class TopInfo(val title: String, val kind: String, val image: String?, val round: Boolean, val uri: String)

@Composable
private fun SongRow(app: AppState, t: Track, liked: Set<String>, playingUri: String?) {
    TrackRow(t, onClick = { app.play(t.uri) }, onMore = { app.menu = app.trackMenu(t) }, playing = playingUri == t.uri, liked = t.uri in liked)
}

@Composable
private fun Shelf(title: String, content: LazyListScope.() -> Unit) {
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader(title, small = true)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(top = 14.dp), content = content)
    }
}

@Composable
private fun ArtistCard(app: AppState, a: Artist, saveRecent: (RecentSearches.Entry) -> Unit) =
    Card(a.name, "Artist", a.images.best()?.url, { saveRecent(a.entry()); app.openUri(a.uri) }, size = 120.dp, round = true, centered = true)

@Composable
private fun AlbumCard(app: AppState, a: Album, saveRecent: (RecentSearches.Entry) -> Unit) =
    Card(a.name, dotted(a.year, a.artists.firstOrNull()?.name), a.images.best()?.url, { saveRecent(a.entry()); app.openUri(a.uri) }, size = 132.dp)

@Composable
private fun PlaylistCard(app: AppState, p: Playlist, saveRecent: (RecentSearches.Entry) -> Unit) =
    Card(p.name, p.owner.displayName, p.images.best()?.url, { saveRecent(p.entry()); app.openUri(p.uri) }, size = 132.dp)

private fun Artist.entry() = RecentSearches.Entry(uri, name, "Artist", images.best(120)?.url, true)
private fun Album.entry() = RecentSearches.Entry(uri, name, dotted(type.label(), artists.joinToString { it.name }), images.best(120)?.url, false)
private fun Playlist.entry() = RecentSearches.Entry(uri, name, dotted("Playlist", owner.displayName), images.best(120)?.url, false)
