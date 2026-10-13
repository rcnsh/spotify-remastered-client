package app.remastered.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remastered.core.PlaybackState
import app.remastered.core.RepeatMode
import app.remastered.core.model.Queue
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.Tab
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.EqBars
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.SubText
import app.remastered.ui.components.TitleText
import app.remastered.ui.components.TrackRow
import app.remastered.ui.components.rememberArtworkTint
import app.remastered.ui.lyrics.LyricsCard
import app.remastered.ui.lyrics.LyricsPeek
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.delay

/** The playback position, advancing every frame while playing (no polling of Spotify). */
@Composable
fun rememberPosition(state: PlaybackState?, clock: () -> Long): State<Long> = produceState(state?.positionAt(clock()) ?: 0L, state) {
    value = state?.positionAt(clock()) ?: 0L
    if (state == null || state.isPaused) return@produceState
    while (true) withFrameMillis { value = state.positionAt(clock()) }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun MiniPlayer(app: AppState, modifier: Modifier = Modifier) {
    val s by app.c.player.state.collectAsState()
    val track = s?.track ?: return
    val position by rememberPosition(s, app.c.clock)
    val tint = rememberArtworkTint(track.imageUrl)
    val bg by animateColorAsState(lerp(Palette.raised, tint, .16f), tween(600), label = "mini")
    Box(
        modifier.padding(horizontal = 10.dp).padding(bottom = 8.dp).fillMaxWidth().height(62.dp)
            .shadow(18.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.horizontalGradient(listOf(bg, Palette.raised)))
            .clickable(onClickLabel = "Open Now Playing") { app.nowPlayingOpen = true },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 9.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.imageUrl, Modifier.size(44.dp), RoundedCornerShape(10.dp))
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                TitleText(track.name, Type.body.copy(fontSize = 14.sp))
                SubText(track.artistNames, Modifier.padding(top = 2.dp))
            }
            IconButton(Icons.Devices, "Devices", { app.devicesOpen = true }, tint = Palette.accentLight, size = 38.dp, iconSize = 20.dp)
            IconButton(if (track.liked) Icons.HeartFilled else Icons.Heart, if (track.liked) "Remove from Liked Songs" else "Add to Liked Songs",
                { app.setLiked(track.uri, !track.liked) }, tint = if (track.liked) Palette.heart else Palette.text, size = 38.dp, iconSize = 20.dp)
            IconButton(if (s!!.isPaused) Icons.Play else Icons.Pause, if (s!!.isPaused) "Play" else "Pause",
                { app.c.player.togglePlay() }, tint = Palette.text, size = 40.dp, iconSize = 22.dp)
        }
        ProgressLine(position, track.durationMs, Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp))
    }
}

@Composable
private fun ProgressLine(position: Long, duration: Long, modifier: Modifier) {
    val f = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(modifier.fillMaxWidth().height(2.5.dp).clip(CircleShape).background(Palette.track)) {
        Box(Modifier.fillMaxWidth(f).height(2.5.dp).clip(CircleShape).background(Palette.progress))
    }
}

@Composable
fun BottomBar(app: AppState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        Tab.entries.forEach { tab ->
            val on = app.nav.tab == tab
            Column(
                Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).clickable { app.nowPlayingOpen = false; app.nav.select(tab) }
                    .semantics { stateDescription = if (on) "Selected" else "" }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(58.dp, 30.dp).clip(CircleShape).background(if (on) Palette.accentSoft else Color.Transparent), contentAlignment = Alignment.Center) {
                    val icon = when (tab) {
                        Tab.Home -> if (on) Icons.HomeFilled else Icons.Home
                        Tab.Search -> if (on) Icons.SearchBold else Icons.Search
                        Tab.Library -> if (on) Icons.LibraryBold else Icons.Library
                    }
                    Icon(icon, null, Modifier.size(24.dp), tint = if (on) Color(0xFFC9D3FF) else Palette.dim)
                }
                Text(tab.label, style = Type.tiny.copy(letterSpacing = 0.sp, fontSize = 11.5.sp), color = if (on) Palette.text else Palette.dim, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Draggable seek bar: moves instantly under the finger, seeks once on release. */
@Composable
fun SeekBar(position: Long, duration: Long, enabled: Boolean, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var widthPx by remember { mutableFloatStateOf(1f) }
    Column(modifier) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(28.dp)
                .semantics { contentDescription = "Seek"; stateDescription = "${formatTime(position)} of ${formatTime(duration)}" }
                .pointerInput(enabled, duration) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { o -> onSeek((o.x / size.width * duration).toLong()) }
                }
                .pointerInput(enabled, duration) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragging?.let { onSeek((it * duration).toLong()) }; dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, delta -> dragging = ((dragging ?: 0f) + delta / size.width).coerceIn(0f, 1f); change.consume() }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            val knob = if (dragging != null) 19.dp else 15.dp
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Palette.track)) {
                Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(CircleShape).background(Palette.progress))
            }
            Box(Modifier.offset(x = maxWidth * fraction - knob / 2).size(knob).shadow(4.dp, CircleShape).background(Color.White, CircleShape))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            val shown = dragging?.let { (it * duration).toLong() } ?: position
            Text(formatTime(shown), style = Type.label.copy(fontSize = 12.sp), color = Color.White.copy(alpha = .65f))
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), style = Type.label.copy(fontSize = 12.sp), color = Color.White.copy(alpha = .65f))
        }
    }
}

@Composable
fun NowPlaying(app: AppState, modifier: Modifier = Modifier) {
    val s by app.c.player.state.collectAsState()
    val state = s
    val track = state?.track
    val tint = rememberArtworkTint(track?.imageUrl)
    val top by animateColorAsState(lerp(tint, Color.Black, .38f), tween(700), label = "npTop")
    val mid by animateColorAsState(lerp(tint, Palette.base, .72f), tween(700), label = "npMid")
    val positionState = rememberPosition(state, app.c.clock)
    val position by positionState

    var queue by remember { mutableStateOf<Queue?>(null) }
    LaunchedEffect(track?.uri) {
        delay(400) // let Spotify update its queue after a skip
        queue = runCatching { app.c.catalog.queue() }.getOrNull()
    }

    val lyricsOn by app.c.settings.lyrics.value.collectAsState()
    val showLyrics = lyricsOn && track != null
    // The player fills the screen; the lyrics card peeks below it and scrolls into view.
    BoxWithConstraints(
        modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to top, .55f to mid, 1f to Palette.base))
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        val pageHeight = if (showLyrics) maxHeight - LyricsPeek else maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = showLyrics)) {
            Column(Modifier.height(pageHeight)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Icons.ChevronDown, "Close", { app.nowPlayingOpen = false }, tint = Palette.text)
                    Column(Modifier.weight(1f).clickable(enabled = state?.context != null) { app.openUri(state?.context?.uri) },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        state?.context?.let { ctx ->
                            Text("PLAYING FROM ${ctx.type.uppercase()}", style = Type.tiny.copy(fontSize = 10.5.sp), color = Color.White.copy(alpha = .7f))
                            TitleText(ctx.title, Type.body.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    IconButton(Icons.More, "More", { track?.let { app.menuForNowPlaying() } }, tint = Palette.text, iconSize = 20.dp)
                }
                if (track == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Nothing playing", style = Type.bodyRegular, color = Palette.sub)
                    }
                } else {
                    Artwork(track.imageUrl,
                        Modifier.padding(horizontal = 24.dp).padding(top = 20.dp).fillMaxWidth().aspectRatio(1f)
                            .shadow(30.dp, RoundedCornerShape(18.dp)), RoundedCornerShape(18.dp))
                    Row(Modifier.padding(start = 24.dp, end = 14.dp, top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            TitleText(track.name, Type.headline)
                            Text(track.artistNames, style = Type.bodyRegular, color = Color.White.copy(alpha = .72f), maxLines = 1,
                                modifier = Modifier.padding(top = 5.dp).clickable { app.openUri(track.artists.firstOrNull()?.uri) })
                        }
                        IconButton(if (track.liked) Icons.HeartFilled else Icons.Heart, if (track.liked) "Remove from Liked Songs" else "Add to Liked Songs",
                            { app.setLiked(track.uri, !track.liked) }, tint = if (track.liked) Palette.heart else Palette.text, size = 48.dp, iconSize = 28.dp)
                    }
                    SeekBar(position, track.durationMs, state.canSeek, { app.c.player.seekTo(it) }, Modifier.padding(horizontal = 24.dp).padding(top = 18.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(Icons.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", { app.c.player.setShuffle(!state.shuffle) },
                            tint = if (state.shuffle) Palette.accentLight else Palette.text, active = state.shuffle, size = 48.dp, iconSize = 24.dp)
                        IconButton(Icons.Previous, "Previous", { app.c.player.previous() }, tint = Palette.text, size = 56.dp, iconSize = 30.dp, enabled = state.canSkipPrevious)
                        Box(Modifier.size(76.dp).shadow(16.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Color.White)
                            .clickable(onClickLabel = if (state.isPaused) "Play" else "Pause") { app.c.player.togglePlay() }, contentAlignment = Alignment.Center) {
                            Icon(if (state.isPaused) Icons.Play else Icons.Pause, if (state.isPaused) "Play" else "Pause", Modifier.size(32.dp), tint = Palette.base)
                        }
                        IconButton(Icons.Next, "Next", { app.c.player.next() }, tint = Palette.text, size = 56.dp, iconSize = 30.dp, enabled = state.canSkipNext)
                        IconButton(if (state.repeat == RepeatMode.One) Icons.RepeatOne else Icons.Repeat, "Repeat ${state.repeat.name.lowercase()}",
                            { app.c.player.setRepeat(state.repeat.next()) }, tint = if (state.repeat != RepeatMode.Off) Palette.accentLight else Color.White.copy(alpha = .75f),
                            active = state.repeat != RepeatMode.Off, size = 48.dp, iconSize = 24.dp)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.clip(CircleShape).clickable { app.devicesOpen = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Devices, null, Modifier.size(20.dp), tint = Palette.accentLight)
                            Text("Devices", style = Type.label, color = Palette.accentLight, modifier = Modifier.padding(start = 8.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(Icons.Share, "Share", { app.share(track.uri, track.name) }, tint = Color.White.copy(alpha = .8f), iconSize = 20.dp)
                        IconButton(Icons.Queue, "Queue", { app.queueOpen = true }, tint = Color.White.copy(alpha = .8f), iconSize = 20.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    queue?.upNext?.firstOrNull()?.let { next ->
                        Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                            .background(Palette.base.copy(alpha = .72f)).clickable { app.queueOpen = true }.padding(top = 14.dp, bottom = 6.dp)) {
                            Row(Modifier.padding(horizontal = 16.dp)) {
                                Text("Up next", style = Type.body.copy(fontWeight = Type.section.fontWeight), color = Palette.text, modifier = Modifier.weight(1f))
                                Text("Queue", style = Type.label, color = Palette.sub)
                            }
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Artwork(next.album?.images?.firstOrNull()?.url, Modifier.size(40.dp), RoundedCornerShape(8.dp))
                                Column(Modifier.padding(start = 12.dp)) {
                                    TitleText(next.name)
                                    SubText(next.artistNames)
                                }
                            }
                        }
                    }
                }
            }
            if (showLyrics) LyricsCard(app, positionState, tint, Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 24.dp))
        }
    }
}

@Composable
fun QueueScreen(app: AppState, modifier: Modifier = Modifier) {
    val s by app.c.player.state.collectAsState()
    var queue by remember { mutableStateOf<Queue?>(null) }
    val liked by app.c.liked.uris.collectAsState()
    LaunchedEffect(s?.track?.uri) {
        delay(300)
        queue = runCatching { app.c.catalog.queue() }.getOrNull()
    }
    Column(modifier.fillMaxSize().background(Palette.base).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icons.ChevronDown, "Close queue", { app.queueOpen = false }, tint = Palette.text)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("QUEUE", style = Type.tiny.copy(fontSize = 10.5.sp), color = Palette.sub)
                s?.context?.let { TitleText(it.title, Type.body.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 2.dp)) }
            }
            Spacer(Modifier.width(40.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            item {
                Text("Now playing", style = Type.body.copy(fontWeight = Type.section.fontWeight), color = Palette.text, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
                s?.track?.let { t ->
                    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(t.imageUrl, Modifier.size(48.dp), RoundedCornerShape(9.dp))
                        Column(Modifier.weight(1f).padding(start = 13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { EqBars(animate = !s!!.isPaused); Spacer(Modifier.width(6.dp)); TitleText(t.name, color = Palette.accent) }
                            SubText(t.artistNames, Modifier.padding(top = 3.dp))
                        }
                    }
                }
                Text("Up next", style = Type.body.copy(fontWeight = Type.section.fontWeight), color = Palette.text, modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 4.dp))
            }
            itemsIndexed(queue?.upNext.orEmpty(), key = { i, t -> "$i${t.uri}" }) { i, t ->
                TrackRow(t, onClick = { repeat(i + 1) { app.c.player.next() } }, onMore = { app.menu = app.trackMenu(t) }, liked = t.uri in liked)
            }
            item {
                Text("Spotify doesn't let apps remove or reorder queued songs.", style = Type.caption, color = Palette.dim,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceAround) {
            val st = s
            QueueToggle(Icons.Shuffle, "Shuffle", st?.shuffle == true) { app.c.player.setShuffle(st?.shuffle != true) }
            QueueToggle(if (st?.repeat == RepeatMode.One) Icons.RepeatOne else Icons.Repeat, "Repeat", st?.repeat != RepeatMode.Off && st != null) {
                st?.let { app.c.player.setRepeat(it.repeat.next()) }
            }
        }
    }
}

@Composable
private fun QueueToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (on) Palette.accentLight else Palette.sub)
        Text(label, style = Type.label.copy(fontSize = 12.sp), color = if (on) Palette.accentLight else Palette.sub, modifier = Modifier.padding(top = 6.dp))
    }
}
