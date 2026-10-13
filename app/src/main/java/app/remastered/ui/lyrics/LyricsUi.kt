package app.remastered.ui.lyrics

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remastered.AppContainer
import app.remastered.core.NowPlaying
import app.remastered.core.PlaybackState
import app.remastered.core.lyrics.CreditPart
import app.remastered.core.lyrics.LineState
import app.remastered.core.lyrics.LyricLine
import app.remastered.core.lyrics.Lyrics
import app.remastered.core.lyrics.LyricsResult
import app.remastered.core.lyrics.LyricsState
import app.remastered.core.lyrics.LyricsTimeline
import app.remastered.core.lyrics.Vocal
import app.remastered.core.lyrics.credit
import app.remastered.ui.AppState
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Chip
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.SubText
import app.remastered.ui.components.TitleText
import app.remastered.ui.components.rememberArtworkTint
import app.remastered.ui.player.SeekBar
import app.remastered.ui.player.rememberPosition
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.delay

private val Bright = Color.White
private val Dim = Color.White.copy(alpha = .36f)
private val Sung = Color.White.copy(alpha = .6f)

private class LyricStyle(val lead: TextStyle, val background: TextStyle, val extra: TextStyle, val gap: Dp)

private val Full = LyricStyle(
    lead = Type.headline.copy(fontSize = 27.sp, lineHeight = 35.sp),
    background = Type.headline.copy(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight(700)),
    extra = Type.bodyRegular.copy(fontSize = 15.sp, lineHeight = 20.sp),
    gap = 18.dp,
)
private val Compact = LyricStyle(
    lead = Type.headline.copy(fontSize = 21.sp, lineHeight = 28.sp),
    background = Type.headline.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight(700)),
    extra = Type.caption,
    gap = 10.dp,
)

/** The playback position plus the user's timing offset, as lyrics see it. */
@Composable
private fun rememberLyricsTime(c: AppContainer, position: State<Long>): State<Long> {
    val offset by c.settings.lyricsOffsetMs.value.collectAsState()
    return remember(position, offset) { derivedStateOf { position.value + offset } }
}

/**
 * Lyrics lines that follow [time]: the current line is kept in view, word-synced lines light up
 * syllable by syllable. Tapping a line seeks there when [onSeek] is given. Only active lines read
 * [time], so a frame recomposes one or two lines, not the list.
 */
@Composable
private fun LyricLines(
    lyrics: Lyrics,
    time: State<Long>,
    listState: LazyListState,
    style: LyricStyle,
    showExtras: Boolean,
    follow: Boolean,
    onSeek: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    userScroll: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
) {
    val synced = lyrics.sync != Lyrics.Sync.Static
    val states by remember(lyrics, time) { derivedStateOf { if (synced) LyricsTimeline.states(lyrics.lines, time.value) else null } }
    val focus by remember(lyrics, time) { derivedStateOf { if (synced) LyricsTimeline.focus(lyrics.lines, time.value) else -1 } }
    var placed by remember(lyrics) { mutableStateOf(false) }
    LaunchedEffect(lyrics, focus, follow) {
        if (!follow || focus < 0) return@LaunchedEffect
        // Jump on first show (opening mid-song), glide after that.
        if (placed) listState.animateScrollToItem(focus) else listState.scrollToItem(focus)
        placed = true
    }
    LazyColumn(modifier, listState, contentPadding, userScrollEnabled = userScroll) {
        itemsIndexed(lyrics.lines) { i, line ->
            LineView(line, lyrics.sync, states?.getOrNull(i), time, style, showExtras,
                onClick = if (synced && onSeek != null) ({ onSeek(line.startMs) }) else null)
        }
        footer?.let { item { it() } }
    }
}

@Composable
private fun LineView(
    line: LyricLine,
    sync: Lyrics.Sync,
    state: LineState?,
    time: State<Long>,
    style: LyricStyle,
    showExtras: Boolean,
    onClick: (() -> Unit)?,
) {
    val end = line.oppositeAligned
    val textAlign = if (end) TextAlign.End else TextAlign.Start
    val scale by animateFloatAsState(if (state == null || state == LineState.Active) 1f else .96f, tween(350), label = "lineScale")
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Play from here", onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = style.gap / 2)
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(if (end) 1f else 0f, .5f) },
        horizontalAlignment = if (end) Alignment.End else Alignment.Start,
    ) {
        VocalText(line.lead, sync, state, time, style.lead, textAlign)
        line.background.forEach { VocalText(it, sync, state, time, style.background, textAlign, Modifier.padding(top = 2.dp)) }
        if (showExtras) {
            val extraColor = if (state == null || state == LineState.Active) Color.White.copy(alpha = .75f) else Dim
            line.lead.transliteration?.takeIf { it != line.lead.text }?.let {
                Text(it, style = style.extra, color = extraColor, textAlign = textAlign, modifier = Modifier.padding(top = 4.dp))
            }
            line.translation?.let {
                Text(it, style = style.extra, color = extraColor, textAlign = textAlign, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun VocalText(
    vocal: Vocal,
    sync: Lyrics.Sync,
    state: LineState?,
    time: State<Long>,
    style: TextStyle,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(
        when (state) {
            null, LineState.Active -> Bright
            LineState.Past -> Sung
            LineState.Upcoming -> Dim
        },
        tween(300), label = "lyric",
    )
    if (sync == Lyrics.Sync.Syllable && state == LineState.Active) {
        val t = time.value
        val text = buildAnnotatedString {
            vocal.syllables.forEachIndexed { i, s ->
                withStyle(SpanStyle(color = lerp(Dim, Bright, LyricsTimeline.progress(s.startMs, s.endMs, t)))) { append(s.text) }
                if (!s.joinsNext && i < vocal.syllables.lastIndex) append(' ')
            }
        }
        Text(text, modifier, style = style, textAlign = textAlign)
    } else {
        Text(vocal.text, modifier, style = style, color = color, textAlign = textAlign)
    }
}

/**
 * Who to credit, from the response itself (Spicy Lyrics' attribution rules): the provider, and for
 * community syncs the maker and uploader, linked. Must be visible wherever lyrics are.
 */
@Composable
fun LyricsCredit(lyrics: Lyrics, modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = .62f)) {
    val text = buildAnnotatedString {
        lyrics.attribution.credit().forEach { part ->
            when (part) {
                is CreditPart.Plain -> append(part.text)
                is CreditPart.Link -> withLink(LinkAnnotation.Url(part.url, TextLinkStyles(SpanStyle(color = Color.White, fontWeight = FontWeight(700), textDecoration = TextDecoration.Underline)))) {
                    append(part.text)
                }
            }
        }
    }
    Text(text, modifier, style = Type.caption, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun SongWriters(lyrics: Lyrics, modifier: Modifier = Modifier) {
    if (lyrics.songWriters.isEmpty()) return
    Text("Written by ${lyrics.songWriters.joinToString(", ")}", modifier, style = Type.caption, color = Color.White.copy(alpha = .55f))
}

/** What to say when there are no lyrics to show. Null when lyrics are switched off. */
private fun message(state: LyricsState): String? = when (state) {
    LyricsState.Off -> null
    LyricsState.Idle -> "Play a song to see its lyrics."
    is LyricsState.Loading -> "Loading lyrics…"
    is LyricsState.Ready -> when (val r = state.result) {
        is LyricsResult.Found -> null
        LyricsResult.NotFound -> "No lyrics for this song yet."
        LyricsResult.Unsupported -> "Lyrics are only available for songs."
        LyricsResult.NotConfigured -> "Lyrics aren't set up: add spicylyrics.key to client/local.properties and rebuild."
        is LyricsResult.Failed -> when (r.problem) {
            LyricsResult.Problem.Offline -> "Can't reach Spicy Lyrics. Check your connection."
            LyricsResult.Problem.RateLimited -> "Too many lyrics requests." + (r.retryAfterMs?.let { " Try again in ${(it + 999) / 1000} s." } ?: "")
            LyricsResult.Problem.KeyRejected -> "Spicy Lyrics rejected this app's key."
            LyricsResult.Problem.ServiceDown -> "Spicy Lyrics isn't available right now."
            LyricsResult.Problem.BadResponse -> "Couldn't read these lyrics."
        }
    }
}

@Composable
private fun LyricsStatus(state: LyricsState, onRetry: () -> Unit, modifier: Modifier = Modifier, center: Boolean = false) {
    val text = message(state) ?: return
    Column(modifier, horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        Text(text, style = Type.bodyRegular, color = Color.White.copy(alpha = .75f), textAlign = if (center) TextAlign.Center else TextAlign.Start)
        if ((state as? LyricsState.Ready)?.result is LyricsResult.Failed) {
            Chip("Try again", false, onRetry, Modifier.padding(top = 12.dp))
        }
    }
}

private val LyricsState.lyrics: Lyrics? get() = ((this as? LyricsState.Ready)?.result as? LyricsResult.Found)?.lyrics

/** Lyrics card below the player on Now Playing; tap to open [LyricsScreen]. Hidden when lyrics are off. */
@Composable
fun LyricsCard(app: AppState, position: State<Long>, tint: Color, modifier: Modifier = Modifier) {
    val state by app.c.currentLyrics.state.collectAsState()
    if (state == LyricsState.Off) return
    val extras by app.c.settings.lyricsExtras.value.collectAsState()
    val time = rememberLyricsTime(app.c, position)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(lerp(tint, Palette.base, .4f))
            .clickable(onClickLabel = "Open lyrics") { app.lyricsOpen = true }
            .padding(vertical = 16.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Lyrics", style = Type.sectionSmall, color = Palette.text, modifier = Modifier.weight(1f).semantics { heading() })
            Icon(Icons.Forward, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = .8f))
        }
        val lyrics = state.lyrics
        if (lyrics == null) {
            LyricsStatus(state, app.c.currentLyrics::retry, Modifier.padding(horizontal = 20.dp).padding(top = 12.dp))
            return@Column
        }
        LyricLines(
            lyrics, time, remember(lyrics) { LazyListState() }, Compact, extras, follow = true, onSeek = null,
            modifier = Modifier.fillMaxWidth().height(250.dp).padding(top = 8.dp),
            userScroll = false,
        )
        LyricsCredit(lyrics, Modifier.padding(horizontal = 20.dp).padding(top = 10.dp))
    }
}

/** Full-screen lyrics for what's playing. */
@Composable
fun LyricsScreen(app: AppState) {
    val s by app.c.player.state.collectAsState()
    val state by app.c.currentLyrics.state.collectAsState()
    val position = rememberPosition(s, app.c.clock)
    val track = s?.track
    LyricsView(
        app.c, state, s, position,
        title = track?.name ?: "Lyrics", subtitle = track?.artistNames, imageUrl = track?.imageUrl,
        onClose = { app.lyricsOpen = false },
        onSeek = { app.c.player.seekTo(it) },
        onToggle = { app.c.player.togglePlay() },
        onRetry = app.c.currentLyrics::retry,
    )
}

/**
 * Developer preview: a track's lyrics on a local clock, without Spotify playing it. For the
 * emulator, where App Remote can't connect (Settings → Open a Spotify uri → Lyrics, or a debug
 * build's launch extra, see MainActivity).
 */
@Composable
fun LyricsPreviewScreen(c: AppContainer, trackUri: String, onClose: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<LyricsState>(LyricsState.Loading(trackUri), trackUri, attempt) {
        value = LyricsState.Loading(trackUri)
        value = LyricsState.Ready(trackUri, c.lyricsSource.lyrics(trackUri))
    }
    val duration = state.lyrics?.lines?.maxOfOrNull { it.endMs }?.plus(10_000) ?: 600_000
    var playback by remember(trackUri) {
        mutableStateOf(PlaybackState(
            track = NowPlaying(trackUri, "Lyrics preview", emptyList(), "", null, null, duration),
            isPaused = false, positionMs = 0, positionAtMs = c.clock(),
        ))
    }
    LaunchedEffect(duration) { playback = playback.copy(track = playback.track?.copy(durationMs = duration)) }
    val position = rememberPosition(playback, c.clock)
    LyricsView(
        c, state, playback, position,
        title = "Lyrics preview", subtitle = "${trackUri.substringAfterLast(':')} · local clock, not Spotify", imageUrl = null,
        onClose = onClose,
        onSeek = { playback = playback.copy(positionMs = it, positionAtMs = c.clock()) },
        onToggle = {
            val now = c.clock()
            playback = playback.copy(isPaused = !playback.isPaused, positionMs = playback.positionAt(now), positionAtMs = now)
        },
        onRetry = { attempt++ },
    )
}

@Composable
private fun LyricsView(
    c: AppContainer,
    state: LyricsState,
    playback: PlaybackState?,
    position: State<Long>,
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
) {
    val tint = rememberArtworkTint(imageUrl)
    val top by animateColorAsState(lerp(tint, Color.Black, .3f), tween(700), label = "lyricsTop")
    val bottom by animateColorAsState(lerp(tint, Palette.base, .65f), tween(700), label = "lyricsBottom")
    val extras by c.settings.lyricsExtras.value.collectAsState()
    val time = rememberLyricsTime(c, position)
    val lyrics = state.lyrics

    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom))).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icons.ChevronDown, "Close lyrics", onClose, tint = Palette.text)
            if (imageUrl != null) Artwork(imageUrl, Modifier.padding(start = 4.dp).size(40.dp), RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                TitleText(title, Type.body)
                subtitle?.let { SubText(it, Modifier.padding(top = 2.dp), color = Color.White.copy(alpha = .7f)) }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (lyrics == null) {
                LyricsStatus(state, onRetry, Modifier.align(Alignment.Center).padding(32.dp), center = true)
                return@BoxWithConstraints
            }
            val listState = remember(lyrics) { LazyListState() }
            var follow by remember(lyrics) { mutableStateOf(true) }
            // A drag means the user is reading ahead or back: stop following until they tap
            // "Back to now", tap a line, or leave the list alone for a few seconds.
            LaunchedEffect(listState) {
                listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
            }
            LaunchedEffect(follow, listState.isScrollInProgress) {
                if (!follow && !listState.isScrollInProgress) { delay(6_000); follow = true }
            }
            val synced = lyrics.sync != Lyrics.Sync.Static
            LyricLines(
                lyrics, time, listState, Full, extras, follow = follow && synced,
                onSeek = { follow = true; onSeek(it) },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = if (synced) maxHeight * .22f else 12.dp, bottom = maxHeight * .5f),
                footer = { SongWriters(lyrics, Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) },
            )
            if (!synced) {
                Text("These lyrics aren't synced to the song.", style = Type.caption, color = Color.White.copy(alpha = .6f),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
            }
            val backAlpha by animateFloatAsState(if (!follow && synced) 1f else 0f, tween(200), label = "backToNow")
            if (backAlpha > 0f) {
                Chip("Back to now", true, { follow = true }, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).graphicsLayer { alpha = backAlpha })
            }
        }
        if (lyrics != null) LyricsCredit(lyrics, Modifier.padding(horizontal = 24.dp).padding(top = 8.dp))
        val track = playback?.track
        if (playback != null && track != null) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SeekBar(position.value, track.durationMs, playback.canSeek, onSeek, Modifier.weight(1f))
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White).clickable(onClickLabel = if (playback.isPaused) "Play" else "Pause", onClick = onToggle),
                    contentAlignment = Alignment.Center) {
                    Icon(if (playback.isPaused) Icons.Play else Icons.Pause, if (playback.isPaused) "Play" else "Pause", Modifier.size(24.dp), tint = Palette.base)
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** How tall the peek of the lyrics card is at the bottom of Now Playing. */
val LyricsPeek: Dp = 64.dp
