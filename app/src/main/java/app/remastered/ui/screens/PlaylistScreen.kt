package app.remastered.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import app.remastered.core.CatalogException
import app.remastered.core.PlaylistOrder
import app.remastered.ui.components.rememberReorderState
import app.remastered.ui.components.reorderItem
import app.remastered.ui.components.reorderable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.remastered.core.model.Playlist
import app.remastered.core.model.PlaylistItem
import app.remastered.core.model.best
import app.remastered.core.model.canEdit
import app.remastered.core.model.canReadItems
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.components.Chip
import app.remastered.ui.components.EmptyMessage
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.Loading
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.TrackRow
import app.remastered.ui.components.dotted
import app.remastered.ui.load
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette

private enum class PlaylistSort(val label: String) { Custom("Custom order"), Added("Date added"), Title("Title"), Artist("Artist") }

private class PlaylistData(val playlist: Playlist, val items: List<PlaylistItem>?)

@Composable
fun PlaylistScreen(app: AppState, id: String) {
    val data = load(app.c.screens, "playlist:$id", app.playlistsVersion) {
        val p = app.c.catalog.playlist(id)
        // Spotify only returns items for playlists the user owns or collaborates on.
        val items = if (p.canReadItems(app.me)) allPages { app.c.catalog.playlistItems(id, it, 50) } else null
        PlaylistData(p, items)
    }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 700 } }
    Box(Modifier.fillMaxSize()) {
        when (val s = data.state) {
            Load.Loading -> Loading(Modifier.padding(top = 120.dp))
            is Load.Failed -> ErrorMessage(s.message, data.retry, Modifier.padding(top = 120.dp))
            is Load.Done -> PlaylistBody(app, s.value, list)
        }
        TopBar(app, onMore = null, title = data.value?.playlist?.name, solid = scrolled)
    }
}

/** One row of the playlist; [key] stays put while the row moves, [item] carries its position. */
private data class Entry(val key: String, val item: PlaylistItem)

@Composable
private fun PlaylistBody(app: AppState, data: PlaylistData, list: LazyListState) {
    val p = data.playlist
    val player by app.c.player.state.collectAsState()
    val liked by app.c.liked.uris.collectAsState()
    var query by rememberSaveable(p.id) { mutableStateOf("") }
    var sort by rememberSaveable(p.id) { mutableStateOf(PlaylistSort.Custom) }
    var editing by remember { mutableStateOf(false) }
    val own = p.owner.id == app.me?.id
    val followed = app.rememberSaved(p.uri)
    val shuffle = app.shuffleOn()
    val haptics = LocalHapticFeedback.current

    // The playlist as shown, which a drag reorders in place before Spotify hears about it.
    val entries = remember(data) { data.items.orEmpty().mapIndexed { i, it -> Entry("e$i", it) }.toMutableStateList() }
    var snapshot by remember(data) { mutableStateOf(p.snapshotId) }
    val writes = remember(data) { Mutex() }
    // Long-press drags only in the playlist's own order, unfiltered, and only where it can be changed.
    val canReorder = p.canEdit(app.me) && sort == PlaylistSort.Custom && query.isBlank() && entries.size > 1

    val reorder = rememberReorderState(
        list,
        canMoveTo = { key -> entries.any { it.key == key } },
        onStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
        onMove = { from, to ->
            val i = entries.indexOfFirst { it.key == from }
            val j = entries.indexOfFirst { it.key == to }
            if (i >= 0 && j >= 0) entries.add(j, entries.removeAt(i))
        },
        onDrop = { key ->
            val i = entries.indexOfFirst { it.key == key }
            val from = entries.getOrNull(i)?.item?.position ?: return@rememberReorderState
            val before = PlaylistOrder.insertBefore(from, entries.getOrNull(i - 1)?.item?.position, entries.getOrNull(i + 1)?.item?.position)
                ?: return@rememberReorderState
            // Positions as Spotify will have them, so later moves and playback line up.
            entries.replaceAll { it.copy(item = it.item.copy(position = PlaylistOrder.shift(it.item.position, from, before))) }
            val moved = entries.map { it.item }
            app.launch {
                writes.withLock {
                    try {
                        snapshot = app.c.catalog.reorderPlaylist(p.id, from, before, snapshot)
                        app.c.screens.update("playlist:${p.id}", PlaylistData(p.copy(snapshotId = snapshot), moved))
                    } catch (e: CatalogException) {
                        // Reload to show the order Spotify actually has.
                        app.playlistsChanged()
                        throw e
                    }
                }
            }
        },
    )

    // Sorting keeps each track's position in the playlist: playback starts from it whatever the order.
    val shown = remember(entries.toList(), query, sort) {
        val matching = entries.filter { it.item.track.matches(query) }
        when (sort) {
            PlaylistSort.Custom -> matching
            PlaylistSort.Added -> matching.sortedByDescending { it.item.addedAtEpochMs ?: 0 }
            PlaylistSort.Title -> matching.sortedBy { it.item.track.name.lowercase() }
            PlaylistSort.Artist -> matching.sortedBy { it.item.track.artistNames.lowercase() }
        }
    }
    val duration = data.items?.sumOf { it.track.durationMs }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = ScreenPadding) {
        item(key = "header") {
            CollectionHeader(
                imageUrl = p.images.best(600)?.url,
                title = p.name,
                description = p.description,
                ownerName = p.owner.displayName,
                ownerImage = p.owner.images.best(64)?.url,
                meta = dotted(songs(p.trackCount), duration?.let(::durationText)),
                playing = app.isPlaying(p.uri),
                onPlay = { app.togglePlay(p.uri) },
                shuffle = shuffle,
                onShuffle = { app.c.player.setShuffle(!shuffle) },
            ) {
                if (!own) {
                    val on = followed.value == true
                    IconButton(if (on) Icons.Check else Icons.Plus, if (on) "Remove from Your Library" else "Add to Your Library",
                        { app.setSaved(p.uri, !on) { followed.value = it } }, tint = if (on) Palette.accent else Palette.text, iconSize = 24.dp)
                }
                // Spotify lets only the owner change a playlist's details, not collaborators.
                if (own) IconButton(Icons.Edit, "Edit details", { editing = true }, tint = Palette.text)
                IconButton(Icons.Share, "Share", { app.share(p.uri, p.name) }, tint = Palette.text)
            }
        }
        if (data.items == null) {
            item { EmptyMessage("Songs aren't available here", "Spotify only shares the songs of playlists you own or collaborate on. Play it to listen in Spotify.") }
            return@LazyColumn
        }
        if (data.items.isEmpty()) {
            item { EmptyMessage("This playlist is empty", "Add songs from their ⋯ menu.") }
            return@LazyColumn
        }
        item(key = "tools") {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterField(query, { query = it }, "Find in playlist", Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Chip(sort.label, selected = false, icon = Icons.Sort, onClick = { sort = PlaylistSort.entries[(sort.ordinal + 1) % PlaylistSort.entries.size] })
            }
        }
        itemsIndexed(shown, key = { _, it -> it.key }) { index, entry ->
            val t = entry.item.track
            val menu = { app.menu = app.trackMenu(t, p.copy(snapshotId = snapshot).takeIf { it.canEdit(app.me) }) }
            TrackRow(
                t,
                onClick = { app.playAt(p.uri, entry.item.position.takeIf { it >= 0 } ?: index) },
                onMore = menu,
                modifier = reorderItem(reorder, entry.key).reorderable(reorder, entry.key, canReorder),
                playing = player?.track?.uri == t.uri && player?.context?.uri == p.uri,
                liked = t.uri in liked,
                // While the order can change, a long press picks the row up; the ⋯ button still opens the menu.
                onLongClick = menu.takeUnless { canReorder },
            )
        }
        if (shown.isEmpty()) item { EmptyMessage("No matches", "Nothing in this playlist matches \"$query\".") }
    }
    if (editing) EditPlaylistSheet(app, p) { editing = false }
}
