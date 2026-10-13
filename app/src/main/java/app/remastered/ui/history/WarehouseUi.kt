package app.remastered.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import app.remastered.core.warehouse.WarehouseException
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.ui.AppState
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Chip
import app.remastered.ui.components.EqBars
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.SectionHeader
import app.remastered.ui.components.SubText
import app.remastered.ui.components.TitleText
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Building blocks shared by History, the history pages, Home's history sections and Stats.

/** A warehouse load: like [app.remastered.ui.Load], plus [Busy] while the warehouse computes a stat. */
sealed interface WLoad<out T> {
    data object Loading : WLoad<Nothing>
    /** 503 that outlasted the API's own retries: still computing; retried automatically. */
    data object Busy : WLoad<Nothing>
    data class Done<T>(val value: T) : WLoad<T>
    data class Failed(val message: String, val problem: WarehouseException.Problem?) : WLoad<Nothing>
}

class WLoaded<T>(val state: WLoad<T>, val retry: () -> Unit) {
    val value: T? get() = (state as? WLoad.Done)?.value
}

/** How many times a screen asks again for a stat that's still being computed. */
private const val BUSY_RETRIES = 20

/** Loads [block] when [keys] change. While the warehouse answers Busy, says so and tries again. */
@Composable
fun <T> loadWarehouse(vararg keys: Any?, block: suspend () -> T): WLoaded<T> {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(*keys) { mutableStateOf<WLoad<T>>(WLoad.Loading) }
    LaunchedEffect(*keys, attempt) {
        var busy = 0
        while (true) {
            state = try {
                WLoad.Done(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: WarehouseException) {
                if (e.problem == WarehouseException.Problem.Busy && busy++ < BUSY_RETRIES) {
                    state = WLoad.Busy
                    delay((e.retryAfterS ?: 15L).coerceIn(5L, 60L) * 1000)
                    continue
                }
                WLoad.Failed(e.userMessage, e.problem)
            } catch (e: Exception) {
                WLoad.Failed(AppState.describe(e), null)
            }
            break
        }
    }
    return WLoaded(state) { attempt++ }
}

/** Whether to show what's built on [feature] (or on the warehouse at all); follows the warehouse's state. */
@Composable
fun AppState.warehouseSupports(feature: WarehouseFeature? = null): Boolean {
    val state by c.warehouse.state.collectAsState()
    val missing by c.warehouse.missing.collectAsState()
    return state.isUsable && (feature == null || feature !in missing)
}

/** Plays [uri], or says why it can't (plays imported from an export may have no Spotify id). */
fun AppState.playTrack(uri: String?) {
    if (uri == null) message("Spotify has no id for this one, so it can't be played from here.") else play(uri)
}

/** The uri of the track that's playing now, for highlighting rows. */
@Composable
fun AppState.playingUri(): String? = c.player.state.collectAsState().value?.track?.uri

/** A titled section whose body follows [data]: loading, "computing…", an error with retry, or [content]. */
@Composable
fun <T> WarehouseSection(
    title: String,
    data: WLoaded<T>,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = 30.dp)) {
        SectionHeader(title, action = action, onAction = onAction, small = true)
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            when (val s = data.state) {
                WLoad.Loading -> SectionStatus(null, spinner = true)
                WLoad.Busy -> SectionStatus("Computing… the warehouse is working this out.", spinner = true)
                is WLoad.Failed -> SectionStatus(s.message, onRetry = data.retry)
                is WLoad.Done -> content(s.value)
            }
        }
    }
}

@Composable
fun SectionStatus(text: String?, spinner: Boolean = false, onRetry: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(if (text == null) 96.dp else 72.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (text == null) Arrangement.Center else Arrangement.Start,
    ) {
        if (spinner) CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        if (text != null) Text(text, style = Type.caption, color = Palette.sub, modifier = Modifier.weight(1f).padding(start = if (spinner) 12.dp else 0.dp))
        if (onRetry != null) Chip("Try again", selected = false, onClick = onRetry, modifier = Modifier.padding(start = 10.dp))
    }
}

/** A rounded surface panel, like the artist page's "In your library" card. */
@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.surface)
            .border(1.dp, Palette.line, RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(16.dp),
        content = content,
    )
}

/** A big number over a small label. */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = Type.sectionSmall, color = Palette.text, maxLines = 1)
        Text(label, style = Type.caption, color = Palette.sub, maxLines = 1)
    }
}

/** A small colour swatch for chart legends. */
@Composable
fun Swatch(color: Color, modifier: Modifier = Modifier) = Box(modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))

/**
 * One song in a history list, styled like [app.remastered.ui.components.TrackRow]. Tap plays;
 * long-press or ⋯ runs [onMore] (usually: open its history). [trailing] text replaces the ⋯ button.
 */
@Composable
fun PlayRow(
    imageUrl: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    playing: Boolean = false,
    liked: Boolean = false,
    leading: String? = null,
    trailing: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).combinedClickable(onClick = onClick, onLongClick = onMore).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) Text(leading, style = Type.label, color = Palette.sub, modifier = Modifier.width(28.dp))
        Artwork(imageUrl, Modifier.size(48.dp), RoundedCornerShape(9.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) { EqBars(); Spacer(Modifier.width(6.dp)) }
                TitleText(title, color = if (playing) Palette.accent else Palette.text)
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (liked) Icon(Icons.HeartFilled, "Liked", Modifier.padding(end = 5.dp).size(13.dp), tint = Palette.accent)
                SubText(subtitle)
            }
        }
        when {
            trailing != null -> Text(trailing, style = Type.caption, color = Palette.dim, modifier = Modifier.padding(start = 10.dp))
            onMore != null -> IconButton(Icons.More, "More options for $title", onMore, tint = Palette.dim, iconSize = 20.dp)
        }
    }
}

// --- Formatting ---

fun count(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "%,d $many".format(n)

fun plays(n: Int) = count(n, "play")

/** "45 min", "12 hr", "1,204 hr". */
fun listened(minutes: Int): String = if (minutes < 120) "$minutes min" else "%,d hr".format(minutes / 60)

private val DayFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm")

fun dayText(day: LocalDate): String = day.format(DayFormat)

fun dayText(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String = dayText(instant.atZone(zone).toLocalDate())

fun timeText(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(ms).atZone(zone).format(TimeFormat)

/** "Today", "Yesterday", "Saturday 10 October", or "Saturday 10 October 2024" in an earlier year. */
fun dayHeader(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "EEEE d MMMM" else "EEEE d MMMM yyyy"))
}

/** "today", "3 days ago", "2 years ago": how long since [instant]. */
fun ago(instant: Instant, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val days = ChronoUnit.DAYS.between(instant.atZone(zone).toLocalDate(), today)
    return when {
        days <= 0 -> "today"
        days == 1L -> "yesterday"
        days < 60 -> "$days days ago"
        days < 730 -> "${days / 30} months ago"
        else -> "${days / 365} years ago"
    }
}

/** Icons the theme doesn't have, drawn like its own (24 px grid, 2 px round strokes). */
object HistoryIcons {
    private fun icon(name: String, vararg stroke: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        stroke.forEach {
            addPath(PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

    val Calendar = icon("calendar", "M5.5 4.5h13a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-12a2 2 0 0 1 2-2z", "M3.5 9.5h17M8 2.5v4M16 2.5v4")
    val History = icon("history", "M3 12a9 9 0 1 0 2.6-6.4L3 8", "M3 3.5V8h4.5", "M12 7.5V12l3 2")
}
