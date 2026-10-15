package app.remastered.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.remastered.core.model.Episode
import app.remastered.core.model.Page
import app.remastered.core.model.Show
import app.remastered.core.model.best
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.EmptyMessage
import app.remastered.ui.components.EqBars
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.Loading
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.TitleText
import app.remastered.ui.components.dotted
import app.remastered.ui.load
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

private class ShowData(val show: Show, val episodes: Page<Episode>)

/** A podcast: header, then its episodes newest first, loading more as the list nears its end. */
@Composable
fun ShowScreen(app: AppState, id: String) {
    val data = load(app.c.screens, "show:$id", maxAgeMs = 10 * 60_000) {
        coroutineScope {
            val show = async { app.c.catalog.show(id) }
            val episodes = async { app.c.catalog.showEpisodes(id, 0, 50) }
            ShowData(show.await(), episodes.await())
        }
    }
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 700 } }
    Box(Modifier.fillMaxSize()) {
        when (val s = data.state) {
            Load.Loading -> Loading(Modifier.padding(top = 120.dp))
            is Load.Failed -> ErrorMessage(s.message, data.retry, Modifier.padding(top = 120.dp))
            is Load.Done -> ShowBody(app, s.value, list)
        }
        TopBar(app, title = data.value?.show?.name, solid = scrolled)
    }
}

@Composable
private fun ShowBody(app: AppState, data: ShowData, list: LazyListState) {
    val show = data.show
    val player by app.c.player.state.collectAsState()
    val saved = app.rememberSaved(show.uri)
    val shuffle = app.shuffleOn()
    // Later pages, fetched as the user scrolls; the first comes with the screen.
    var more by remember(data) { mutableStateOf(emptyList<Episode>()) }
    var hasMore by remember(data) { mutableStateOf(data.episodes.hasMore) }
    var failed by remember(data) { mutableStateOf(false) }
    val episodes = data.episodes.items + more
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 5 } == true } }
    LaunchedEffect(nearEnd, hasMore, failed) {
        if (!nearEnd || !hasMore || failed) return@LaunchedEffect
        runCatching { app.c.catalog.showEpisodes(show.id, episodes.size, 50) }
            .onSuccess { more = more + it.items; hasMore = it.hasMore && it.items.isNotEmpty() }
            .onFailure { failed = true }
    }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = ScreenPadding) {
        item(key = "header") {
            CollectionHeader(
                imageUrl = show.images.best(600)?.url,
                title = show.name,
                description = show.description,
                meta = dotted("Podcast", episodesText(show.totalEpisodes)),
                playing = app.isPlaying(show.uri),
                onPlay = { app.togglePlay(show.uri) },
                shuffle = shuffle,
                onShuffle = { app.c.player.setShuffle(!shuffle) },
            ) {
                val on = saved.value == true
                IconButton(if (on) Icons.Check else Icons.Plus, if (on) "Remove from Your Library" else "Add to Your Library",
                    { app.setSaved(show.uri, !on) { saved.value = it } }, tint = if (on) Palette.accent else Palette.text, iconSize = 24.dp)
                IconButton(Icons.Share, "Share", { app.share(show.uri, show.name) }, tint = Palette.text)
            }
        }
        if (episodes.isEmpty()) item { EmptyMessage("No episodes", "Spotify doesn't list any episodes of this show.") }
        items(episodes, key = { it.track.id }) { e ->
            val t = e.track
            EpisodeRow(e, playing = player?.track?.uri == t.uri, onClick = { app.play(t.uri) }, onMore = { app.menu = app.trackMenu(t) })
        }
        if (hasMore) item(key = "more") {
            if (failed) ErrorMessage("Couldn't load more episodes.", { failed = false }) else Loading()
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, playing: Boolean, onClick: () -> Unit, onMore: () -> Unit) {
    val t = episode.track
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMore).padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Artwork(t.album?.images?.best(120)?.url, Modifier.size(56.dp), RoundedCornerShape(10.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) { EqBars(); Spacer(Modifier.width(6.dp)) }
                TitleText(t.name, color = if (playing) Palette.accent else Palette.text, lines = 2)
            }
            if (episode.description.isNotBlank()) Text(episode.description, style = Type.caption, color = Palette.sub, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(dotted(releaseText(episode.releaseDate), durationText(t.durationMs)), style = Type.caption, color = Palette.dim,
                modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(Icons.More, "More options for ${t.name}", onMore, tint = Palette.dim, iconSize = 20.dp)
    }
}

private fun episodesText(n: Int) = when (n) {
    0 -> null
    1 -> "1 episode"
    else -> "%,d episodes".format(n)
}

private val thisYear = DateTimeFormatter.ofPattern("d MMM")
private val otherYear = DateTimeFormatter.ofPattern("d MMM yyyy")

/** "3 Oct", or "3 Oct 2024" outside this year; Spotify's coarser dates ("2024") as they come. */
private fun releaseText(date: String): String? = try {
    val d = LocalDate.parse(date)
    d.format(if (d.year == LocalDate.now().year) thisYear else otherYear)
} catch (_: DateTimeParseException) {
    date.ifBlank { null }
}
