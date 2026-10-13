package app.remastered.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.remastered.core.model.best
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.Route
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Chip
import app.remastered.ui.components.CollectionRow
import app.remastered.ui.components.EmptyMessage
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.LikedArt
import app.remastered.ui.components.Loading
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.dotted
import app.remastered.ui.load
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private enum class LibKind { Playlist, Album, Artist }
private enum class LibFilter(val label: String) { Playlists("Playlists"), ByYou("By you"), Albums("Albums"), Artists("Artists") }
private enum class LibSort(val label: String) { Recents("Recents"), Alphabetical("Alphabetical"), Creator("Creator") }

private data class LibItem(
    val uri: String, val title: String, val subtitle: String, val creator: String,
    val imageUrl: String?, val kind: LibKind, val byYou: Boolean,
)

/**
 * Your Library: playlists, saved albums and followed artists. Folders and pins aren't in the
 * Web API, so only Liked Songs is pinned.
 */
@Composable
fun LibraryScreen(app: AppState) {
    val data = load(app.playlistsVersion, app.me?.id) {
        coroutineScope {
            val c = app.c.catalog
            val playlists = async { allPages(1_000) { c.myPlaylists(it, 50) } }
            val albums = async { runCatching { allPages(1_000) { c.savedAlbums(it, 50) } }.getOrDefault(emptyList()) }
            val artists = async { runCatching { c.followedArtists(50) }.getOrDefault(emptyList()) }
            playlists.await().map {
                val mine = it.owner.id == app.me?.id
                LibItem(it.uri, it.name, dotted("Playlist", it.owner.displayName), it.owner.displayName, it.images.best(160)?.url, LibKind.Playlist, mine)
            } + albums.await().map { a ->
                val by = a.artists.joinToString { it.name }
                LibItem(a.uri, a.name, dotted("Album", by), by, a.images.best(160)?.url, LibKind.Album, false)
            } + artists.await().map {
                LibItem(it.uri, it.name, "Artist", it.name, it.images.best(160)?.url, LibKind.Artist, false)
            }
        }
    }
    var filter by rememberSaveable { mutableStateOf<LibFilter?>(null) }
    var sort by rememberSaveable { mutableStateOf(LibSort.Recents) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val likedCount = app.c.liked.tracks.value.size

    val shown = remember(data.value, filter, sort, query) {
        val all = data.value.orEmpty().filter { item ->
            when (filter) {
                null -> true
                LibFilter.Playlists -> item.kind == LibKind.Playlist
                LibFilter.ByYou -> item.byYou
                LibFilter.Albums -> item.kind == LibKind.Album
                LibFilter.Artists -> item.kind == LibKind.Artist
            } && (query.isBlank() || item.title.contains(query.trim(), ignoreCase = true) || item.creator.contains(query.trim(), ignoreCase = true))
        }
        when (sort) {
            LibSort.Recents -> all
            LibSort.Alphabetical -> all.sortedBy { it.title.lowercase() }
            LibSort.Creator -> all.sortedBy { it.creator.lowercase() }
        }
    }
    val showLiked = (filter == null || filter == LibFilter.Playlists) && query.isBlank()

    LazyVerticalGrid(
        columns = if (grid) GridCells.Fixed(2) else GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScreenPadding,
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(start = 20.dp, end = 10.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(app.me?.images?.best(80)?.url, Modifier.size(36.dp).clickable(onClickLabel = "Settings") { app.open(Route.Settings) }, CircleShape)
                    Text("Your Library", style = Type.title, color = Palette.text, modifier = Modifier.weight(1f).padding(start = 14.dp))
                    IconButton(Icons.Search, "Find in Your Library", { finding = !finding; if (!finding) query = "" }, tint = Palette.text, iconSize = 24.dp)
                    IconButton(Icons.Plus, "Create playlist", { creating = true }, tint = Palette.text, iconSize = 24.dp)
                }
                if (finding) FilterField(query, { query = it }, "Find in Your Library", Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
                    if (filter != null) item { Chip("", selected = false, icon = Icons.Close, onClick = { filter = null }) }
                    items(LibFilter.entries.filter { filter == null || it == filter }) { f ->
                        Chip(f.label, selected = f == filter, onClick = { filter = if (filter == f) null else f })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { sort = LibSort.entries[(sort.ordinal + 1) % LibSort.entries.size] }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Sort, null, Modifier.size(18.dp), tint = Palette.text)
                        Text(sort.label, style = Type.label, color = Palette.text, modifier = Modifier.padding(start = 8.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(if (grid) Icons.List else Icons.Grid, if (grid) "Show as list" else "Show as grid", { grid = !grid }, tint = Palette.sub)
                }
            }
        }
        if (showLiked) item(span = { if (grid) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) {
            val subtitle = dotted("Playlist", if (likedCount > 0) songs(likedCount) else null)
            if (grid) GridCard(0, "Liked Songs", subtitle, { LikedArt(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(14.dp)) }) { app.open(Route.LikedSongs) }
            else CollectionRow("Liked Songs", subtitle, { LikedArt(Modifier.size(56.dp), RoundedCornerShape(10.dp)) }, { app.open(Route.LikedSongs) },
                leadingSubIcon = Icons.Pin)
        }
        when (val s = data.state) {
            Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorMessage(s.message, data.retry) }
            is Load.Done -> {
                itemsIndexed(shown, key = { _, it -> it.uri }) { index, item ->
                    val playing = app.isPlaying(item.uri)
                    val round = item.kind == LibKind.Artist
                    if (grid) GridCard(index + (if (showLiked) 1 else 0), item.title, item.subtitle, {
                        Artwork(item.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f), if (round) CircleShape else RoundedCornerShape(14.dp))
                    }) { app.openUri(item.uri) }
                    else CollectionRow(item.title, item.subtitle,
                        { Artwork(item.imageUrl, Modifier.size(56.dp), if (round) CircleShape else RoundedCornerShape(10.dp)) },
                        { app.openUri(item.uri) }, playing = playing)
                }
                if (shown.isEmpty() && !showLiked) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyMessage(if (query.isBlank()) "Nothing here yet" else "No matches",
                        if (query.isBlank()) "Save albums, follow artists and playlists, and they'll show up here." else "Nothing in Your Library matches \"$query\".")
                }
            }
        }
    }
    if (creating) CreatePlaylistDialog(app) { creating = false }
}

@Composable
private fun GridCard(position: Int, title: String, subtitle: String, art: @Composable () -> Unit, onClick: () -> Unit) {
    // Two columns with 14dp between them and 20dp at the screen edges.
    val left = position % 2 == 0
    Column(Modifier.padding(start = if (left) 20.dp else 7.dp, end = if (left) 7.dp else 20.dp, top = 8.dp, bottom = 8.dp)
        .clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        art()
        Text(title, style = Type.body, color = Palette.text, maxLines = 1, modifier = Modifier.padding(top = 9.dp))
        Text(subtitle, style = Type.caption, color = Palette.sub, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun CreatePlaylistDialog(app: AppState, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Palette.surface,
        title = { Text("Give your playlist a name", style = Type.sectionSmall, color = Palette.text) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("My playlist") }) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val n = name.trim()
                onDone()
                app.launch {
                    val p = app.c.catalog.createPlaylist(n)
                    app.playlistsChanged()
                    app.open(Route.Playlist(p.id))
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
