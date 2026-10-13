package app.remastered.ui.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remastered.AppContainer
import app.remastered.core.Connection
import app.remastered.core.warehouse.Wrapped
import app.remastered.core.wrapped.WrappedTimeline
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.load
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Pressing longer than this pauses instead of skipping (web Story.tsx `HOLD_MS`). */
private const val HOLD_MS = 220L

/**
 * The Wrapped story for one range (`Route.WrappedStory`), full screen: RemasteredRoot hides the
 * mini player and tabs here. Loads the range (a range nobody opened yet is computed by the
 * warehouse first, which can take a minute), preloads every picture, then plays.
 */
@Composable
fun WrappedStoryScreen(app: AppState, from: LocalDate, to: LocalDate) =
    WrappedStory(app.c, from, to, onClose = { app.back() }, message = app::message)

/**
 * The story for [from]..[to] on its own, needing only the warehouse: the screen above, and the
 * debug preview that opens it before sign-in (MainActivity `wrapped_preview`). Without a Spotify
 * session the artist photos fall back to song art, as on the web, and the soundtrack stays off.
 * [startFrame] opens it paused at that frame (debug, for comparing stills).
 */
@Composable
fun WrappedStory(c: AppContainer, from: LocalDate, to: LocalDate, onClose: () -> Unit, message: (String) -> Unit, startFrame: Int? = null, dump: List<Int>? = null) {
    val context = LocalContext.current
    var progress by remember(from, to) { mutableFloatStateOf(0f) }
    val story = load(from, to) {
        val data = c.warehouse.api.wrapped(from, to)
        // Photos are decoration: never wait long for them, and don't ask without a session.
        val photos = if (c.spotify.signedIn) withTimeoutOrNull(8_000) { WrappedAssets.artistPhotos(c.catalog) }.orEmpty() else emptyMap()
        data to WrappedAssets.load(context, data, photos) { progress = it }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = story.state) {
            Load.Loading -> StoryLoading(from, to, progress, onClose = onClose)
            is Load.Failed -> Column(Modifier.align(Alignment.Center)) {
                ErrorMessage(s.message, story.retry)
                StoryLink("Close", onClose)
            }
            is Load.Done -> {
                val (data, assets) = s.value
                if (data.totals.plays == 0) {
                    Column(Modifier.align(Alignment.Center)) {
                        ErrorMessage("Nothing was played in this range.", null)
                        StoryLink("Close", onClose)
                    }
                } else {
                    StoryPlayer(c, data, assets, onClose, message, startFrame, dump)
                }
            }
        }
    }
}

@Composable
private fun StoryLink(text: String, onClick: () -> Unit) {
    Text(text, style = Type.label, color = Palette.sub, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun StoryLoading(from: LocalDate, to: LocalDate, progress: Float, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.CircularProgressIndicator(color = Pop.acid, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
        Text(
            if (progress == 0f) "Wrapping $from → $to… a range nobody has opened yet can take a minute." else "Getting the pictures ready…",
            style = Type.bodyRegular, color = Palette.sub, modifier = Modifier.padding(top = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (progress > 0f) {
            LinearProgressIndicator({ progress }, Modifier.padding(top = 16.dp).width(200.dp), color = Pop.acid, trackColor = Color(0xFF333333))
        }
        StoryLink("Cancel", onClose)
    }
}

/**
 * The story as Spotify presents it (web Story.tsx): segmented progress bars, tap the right two
 * thirds for the next card and the left third for the previous one, hold to pause, swipe down to
 * close. Paused, a scrubber appears. The clock is the display's frame clock (withFrameNanos) at
 * the story's FPS; the frame drawn is always WrappedFrame at an exact frame number.
 */
@Composable
private fun StoryPlayer(c: AppContainer, data: Wrapped, assets: WrappedAssets, onClose: () -> Unit, message: (String) -> Unit, startFrame: Int?, dump: List<Int>?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val timeline = remember(data) { WrappedTimeline.of(data) }
    val last = timeline.durationInFrames - 1
    var frame by remember { mutableIntStateOf(startFrame?.coerceIn(0, last) ?: 0) }
    var paused by remember { mutableStateOf(startFrame != null) }
    /** Bumped on every seek, so the clock restarts from the new frame. */
    var seeks by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val renderer = rememberWrappedRenderer()
    val soundtrack = remember(data) {
        StorySoundtrack(c.player, c.catalog, c.lyricsSource, c.clock, c.scope, data, timeline)
    }

    LaunchedEffect(paused, seeks) {
        if (paused) return@LaunchedEffect
        val startFrame = frame
        var start = -1L
        while (frame < last) {
            withFrameNanos { now ->
                if (start < 0) start = now
                frame = min(last, startFrame + ((now - start) * WrappedTimeline.FPS / 1_000_000_000L).toInt())
            }
        }
        // The web story leaves at the end; here it rests on the summary so it can be shared.
        paused = true
    }

    val index = timeline.indexAt(frame)
    val connection by c.player.connection.collectAsState()
    LaunchedEffect(Unit) { soundtrack.prefetch() }
    LaunchedEffect(index) { soundtrack.onSlide(index) }
    DisposableEffect(soundtrack) { onDispose { soundtrack.restore() } }

    fun go(to: Int) {
        frame = timeline.startOf(to)
        paused = false
        seeks++
    }

    fun share(card: Boolean) {
        if (sharing) return
        sharing = true
        val at = if (card) timeline.summaryCardFrame ?: frame else frame
        scope.launch {
            try {
                val image = renderer.renderWrappedFrame(data, at, assets, WrappedTimeline.WIDTH, WrappedTimeline.HEIGHT)
                val name = if (card) "wrapped-${data.from}-to-${data.to}" else "wrapped-${data.from}-to-${data.to}-$at"
                WrappedShare.share(context, WrappedShare.savePng(context, image, name), "Your Wrapped")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message("Couldn't make the image.")
            } finally {
                sharing = false
            }
        }
    }

    if (dump != null) LaunchedEffect(dump) {
        val dir = java.io.File(context.getExternalFilesDir(null), "wrapped").apply { mkdirs() }
        for (f in dump) {
            val image = renderer.renderWrappedFrame(data, f, assets, WrappedTimeline.WIDTH, WrappedTimeline.HEIGHT)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                java.io.File(dir, "frame-%05d.png".format(f)).outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        message("Rendered ${dump.size} frames")
        android.util.Log.i("WrappedDump", "done ${dump.size}")
    }
    WrappedRendererHost(renderer)
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        // The story fills the screen at 9:16, phone-style.
        Box(Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp))) {
            WrappedFrame(data, frame, assets, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().pointerInput(timeline) {
                    var holdJob: Job? = null
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val pressedAt = down.uptimeMillis
                        paused = true
                        holdJob = scope.launch { delay(HOLD_MS); soundtrack.hold() }
                        var dy = 0f
                        var up = down
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            dy += change.positionChange().y
                            up = change
                            if (!change.pressed) break
                        }
                        holdJob?.cancel()
                        val held = up.uptimeMillis - pressedAt > HOLD_MS
                        when {
                            dy > 120.dp.toPx() && abs(dy) > abs(up.position.x - down.position.x) -> { soundtrack.release(); onClose() }
                            held -> { soundtrack.release(); paused = frame >= last }
                            down.position.x < size.width / 3f -> go(timeline.indexAt(frame) - 1)
                            else -> go(timeline.indexAt(frame) + 1)
                        }
                    }
                },
            )
            // Bars, then one slim row of controls under them (web: .story-bars, .story-controls).
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                timeline.slides.indices.forEach { i ->
                    val fill = timeline.barFill(i, frame).toFloat()
                    Box(Modifier.weight(1f).height(3.dp).shadow(1.dp, RoundedCornerShape(2.dp)).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 20.dp).height(32.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (connection == Connection.Connected) {
                    Pill(if (soundtrack.muted) Icons.VolumeLow else Icons.Volume, if (soundtrack.muted) "Unmute" else "Mute") { soundtrack.mute(!soundtrack.muted) }
                }
                if (paused) {
                    Text("Paused", fontSize = 11.sp, color = Color.White, fontFamily = WrappedFonts.mono, modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 10.dp, vertical = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    Pill(Icons.Share, "Share", enabled = !sharing) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Share this frame") }, { menu = false; share(card = false) })
                        if (timeline.summaryCardFrame != null) DropdownMenuItem({ Text("Share summary card") }, { menu = false; share(card = true) })
                    }
                }
                Pill(Icons.Close, "Close", onClick = onClose)
            }
            if (paused) {
                Slider(
                    value = frame.toFloat(), valueRange = 0f..last.toFloat(),
                    onValueChange = { frame = it.toInt() },
                    onValueChangeFinished = { seeks++ },
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Pill(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(18.dp), tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f))
    }
}
