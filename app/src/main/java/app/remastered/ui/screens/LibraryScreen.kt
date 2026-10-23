package app.remastered.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.remastered.core.LibraryEntry
import app.remastered.core.LibraryLayout
import app.remastered.core.model.best
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.Loaded
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

internal enum class LibKind { Playlist, Album, Artist, Show }
private enum class LibFilter(val label: String) { Playlists("Playlists"), ByYou("By you"), Albums("Albums"), Artists("Artists"), Podcasts("Podcasts") }
private enum class LibSort(val label: String) { Recents("Recents"), Alphabetical("Alphabetical"), Creator("Creator") }

/** [ownerId] is a playlist's owner, for "By you". */
@Serializable
internal data class LibItem(
    val uri: String, val title: String, val subtitle: String, val creator: String,
    val imageUrl: String?, val kind: LibKind, val ownerId: String? = null,
)

/**
 * Everything in Your Library, kept on disk and refreshed every half hour (or after a change made
 * here): a large library is several requests, and it changes rarely. Pins and folders that point at
 * something no longer in it are dropped once it loads.
 */
@Composable
internal fun loadLibrary(app: AppState): Loaded<List<LibItem>> {
    val data = load(app.c.screens, "library", app.playlistsVersion, maxAgeMs = 30 * 60_000, saver = ListSerializer(LibItem.serializer())) {
        coroutineScope {
            val c = app.c.catalog
            val playlists = async { allPages(1_000) { c.myPlaylists(it, 50) } }
            val albums = async { runCatching { allPages(1_000) { c.savedAlbums(it, 50) } }.getOrDefault(emptyList()) }
            val artists = async { runCatching { c.followedArtists(50) }.getOrDefault(emptyList()) }
            // Hidden, like albums and artists, if Spotify stops sharing saved shows.
            val shows = async { runCatching { allPages(1_000) { c.savedShows(it, 50) } }.getOrDefault(emptyList()) }
            playlists.await().map {
                LibItem(it.uri, it.name, dotted("Playlist", it.owner.displayName), it.owner.displayName, it.images.best(160)?.url, LibKind.Playlist, it.owner.id)
            } + albums.await().map { a ->
                val by = a.artists.joinToString { it.name }
                LibItem(a.uri, a.name, dotted("Album", by), by, a.images.best(160)?.url, LibKind.Album)
            } + artists.await().map {
                LibItem(it.uri, it.name, "Artist", it.name, it.images.best(160)?.url, LibKind.Artist)
            } + shows.await().map {
                LibItem(it.uri, it.name, "Podcast", it.name, it.images.best(160)?.url, LibKind.Show)
            }
        }
    }
    LaunchedEffect(data.value) { data.value?.let { items -> app.c.library.update { it.prune(items.mapTo(HashSet()) { i -> i.uri }) } } }
    return data
}

/**
 * Your Library: playlists, saved albums, followed artists and saved podcasts, with the app's own
 * pins and folders (Spotify never exposed its own to apps). Liked Songs is always pinned.
 */
@Composable
fun LibraryScreen(app: AppState) {
    val data = loadLibrary(app)
    val layout by app.c.library.layout.collectAsState()
    var sheet by remember { mutableStateOf<LibrarySheet?>(null) }
    var filter by rememberSaveable { mutableStateOf<LibFilter?>(null) }
    var sort by rememberSaveable { mutableStateOf(LibSort.Recents) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val likedCount = app.c.liked.tracks.collectAsState().value.size
    val warehouse by app.c.warehouse.state.collectAsState()
    // Listening history (music-warehouse), pinned under Liked Songs while it's set up.
    val history = if (warehouse.isUsable) listOf(
        HistoryEntry("Listening history", "Every play, from your warehouse", Icons.Queue, Route.History),
        HistoryEntry("Stats", "On repeat, rhythm, top artists over time", Icons.Sort, Route.Stats),
    ) else emptyList()

    val myId = app.me?.id
    val shown = remember(data.value, filter, sort, query, myId) {
        val all = data.value.orEmpty().filter { item ->
            when (filter) {
                null -> true
                LibFilter.Playlists -> item.kind == LibKind.Playlist
                LibFilter.ByYou -> item.kind == LibKind.Playlist && myId != null && item.ownerId == myId
                LibFilter.Albums -> item.kind == LibKind.Album
                LibFilter.Artists -> item.kind == LibKind.Artist
                LibFilter.Podcasts -> item.kind == LibKind.Show
            } && (query.isBlank() || item.title.contains(query.trim(), ignoreCase = true) || item.creator.contains(query.trim(), ignoreCase = true))
        }
        when (sort) {
            LibSort.Recents -> all
            LibSort.Alphabetical -> all.sortedBy { it.title.lowercase() }
            LibSort.Creator -> all.sortedBy { it.creator.lowercase() }
        }
    }
    val hasShows = data.value.orEmpty().any { it.kind == LibKind.Show }
    val showLiked = (filter == null || filter == LibFilter.Playlists) && query.isBlank()
    // Folders show with everything or playlists; other filters and a search list what's inside them.
    val entries = remember(shown, layout, showLiked, sort) {
        if (showLiked) layout.arrange(shown, LibItem::uri, foldersByName = sort != LibSort.Recents)
            .filter { it !is LibraryEntry.Folder || filter == null || it.members.isNotEmpty() }
        else LibraryLayout(pins = layout.pins).arrange(shown, LibItem::uri)
    }

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
                    IconButton(Icons.Plus, "Create", { sheet = LibrarySheet.Create }, tint = Palette.text, iconSize = 24.dp)
                }
                if (finding) FilterField(query, { query = it }, "Find in Your Library", Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
                    if (filter != null) item { Chip("", selected = false, icon = Icons.Close, onClick = { filter = null }) }
                    // Podcasts only once there's one to show.
                    items(LibFilter.entries.filter { (filter == null || it == filter) && (it != LibFilter.Podcasts || hasShows) }) { f ->
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
        if (showLiked) history.forEachIndexed { i, entry ->
            item(span = { if (grid) GridItemSpan(1) else GridItemSpan(maxLineSpan) }) {
                if (grid) GridCard(1 + i, entry.title, entry.subtitle, { IconArt(entry.icon, Modifier.fillMaxWidth().aspectRatio(1f), 14) }) { app.open(entry.route) }
                else CollectionRow(entry.title, entry.subtitle, { IconArt(entry.icon, Modifier.size(56.dp), 10) }, { app.open(entry.route) })
            }
        }
        when (val s = data.state) {
            Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
            is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorMessage(s, data.retry) }
            is Load.Done -> {
                itemsIndexed(entries, key = { _, it -> it.uri(LibItem::uri) }) { index, entry ->
                    val position = index + (if (showLiked) 1 + history.size else 0)
                    when (entry) {
                        is LibraryEntry.Item -> LibraryItemRow(app, entry.item, entry.pinned, grid, position) { sheet = LibrarySheet.Item(entry.item) }
                        is LibraryEntry.Folder -> FolderRow(app, entry, grid, position) { sheet = LibrarySheet.Folder(entry.folder.id) }
                    }
                }
                if (shown.isEmpty() && !showLiked) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyMessage(if (query.isBlank()) "Nothing here yet" else "No matches",
                        if (query.isBlank()) "Save albums and podcasts, follow artists and playlists, and they'll show up here." else "Nothing in Your Library matches \"$query\".")
                }
            }
        }
    }
    sheet?.let { LibrarySheets(app, it, data.value.orEmpty()) { next -> sheet = next } }
}

private data class HistoryEntry(val title: String, val subtitle: String, val icon: ImageVector, val route: Route)

@Composable
private fun IconArt(icon: ImageVector, modifier: Modifier, cornerDp: Int) {
    Box(modifier.clip(RoundedCornerShape(cornerDp.dp)).background(Palette.raised), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(26.dp), tint = Palette.accent)
    }
}

@Composable
internal fun GridCard(position: Int, title: String, subtitle: String, art: @Composable () -> Unit, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    // Two columns with 14dp between them and 20dp at the screen edges.
    val left = position % 2 == 0
    Column(Modifier.padding(start = if (left) 20.dp else 7.dp, end = if (left) 7.dp else 20.dp, top = 8.dp, bottom = 8.dp)
        .clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        art()
        Text(title, style = Type.body, color = Palette.text, maxLines = 1, modifier = Modifier.padding(top = 9.dp))
        Text(subtitle, style = Type.caption, color = Palette.sub, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
internal fun CreatePlaylistDialog(app: AppState, onDone: () -> Unit) {
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
