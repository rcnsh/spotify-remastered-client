package app.remastered.ui.screens

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.remastered.core.model.best
import app.remastered.ui.AppState
import app.remastered.ui.components.EmptyMessage
import app.remastered.ui.components.LikedArt
import app.remastered.ui.components.Loading
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.TrackRow
import app.remastered.ui.theme.Palette

@Composable
fun LikedSongsScreen(app: AppState) {
    val tracks by app.c.liked.tracks.collectAsState()
    val complete by app.c.liked.complete.collectAsState()
    val player by app.c.player.state.collectAsState()
    val shuffle = app.shuffleOn()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(tracks, query) { tracks.withIndex().filter { it.value.matches(query) } }
    // Spotify's uri for the user's Liked Songs; App Remote can play it from an index.
    val collection = app.me?.let { "spotify:user:${it.id}:collection" }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 700 } }
    // Songs are fetched as the list nears its end (a page ahead of what's on screen), all of them
    // only to find one: a first run doesn't spend 60 requests on 3,000 songs nobody scrolls to.
    val reached by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 } }
    LaunchedEffect(reached, query.isBlank()) {
        if (query.isBlank()) app.c.liked.want(reached + LIKED_AHEAD) else app.c.liked.load()
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = ScreenPadding) {
            item(key = "header") {
                CollectionHeader(
                    imageUrl = null,
                    tintOverride = Palette.accent,
                    cover = { LikedArt(Modifier.size(232.dp), RoundedCornerShape(16.dp)) },
                    title = "Liked Songs",
                    ownerName = app.me?.displayName,
                    ownerImage = app.me?.images?.best(64)?.url,
                    meta = if (complete) songs(tracks.size) else "${songs(tracks.size)} so far",
                    playing = app.isPlaying(collection),
                    onPlay = { collection?.let(app::togglePlay) ?: tracks.firstOrNull()?.let { app.play(it.uri) } },
                    shuffle = shuffle,
                    onShuffle = { app.c.player.setShuffle(!shuffle) },
                )
            }
            if (tracks.isNotEmpty()) item(key = "find") {
                FilterField(query, { query = it }, "Find in Liked Songs", Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
            }
            items(shown, key = { "l" + it.value.id }) { (index, t) ->
                TrackRow(
                    t,
                    onClick = { if (collection != null) app.playAt(collection, index) else app.play(t.uri) },
                    onMore = { app.menu = app.trackMenu(t) },
                    playing = player?.track?.uri == t.uri,
                    liked = true,
                )
            }
            when {
                !complete -> item { Loading() }
                tracks.isEmpty() -> item { EmptyMessage("Songs you like will appear here", "Tap the heart on any song to save it.") }
                shown.isEmpty() -> item { EmptyMessage("No matches", "None of your liked songs match \"$query\".") }
            }
        }
        TopBar(app, title = "Liked Songs", solid = scrolled)
    }
}

/** Songs Liked Songs keeps loaded past the last one on screen. */
private const val LIKED_AHEAD = 40
