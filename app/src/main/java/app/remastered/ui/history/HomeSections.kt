package app.remastered.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.remastered.core.warehouse.OnRepeat
import app.remastered.core.warehouse.OnThisDay
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.components.Card
import app.remastered.ui.components.SectionHeader
import app.remastered.ui.components.dotted
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Home's sections from the listening history: "On repeat" from the warehouse (replacing the Web
 * API's top tracks while it has some) and "On this day".
 */
@Stable
class HomeHistory(
    /** Null while loading, unavailable or failed. */
    val onRepeat: OnRepeat?,
    /** True when Home should show its Web API "On repeat" instead (no warehouse, an error, or nothing on repeat). */
    val useWebOnRepeat: Boolean,
    val onThisDay: OnThisDay?,
)

@Composable
fun rememberHomeHistory(app: AppState): HomeHistory {
    val usable = app.warehouseSupports()
    val days = app.warehouseSupports(WarehouseFeature.OnThisDay)
    val today = remember { LocalDate.now() }
    val repeat = loadWarehouse(usable) { if (usable) app.c.warehouse.api.onRepeat() else null }
    val onThisDay = loadWarehouse(days, today) { if (days) app.c.warehouse.api.onThisDay(today) else null }
    val r = repeat.state
    val tracks = (r as? WLoad.Done)?.value?.takeIf { it.tracks.isNotEmpty() }
    return HomeHistory(
        onRepeat = tracks,
        useWebOnRepeat = !usable || r is WLoad.Failed || r is WLoad.Busy || (r is WLoad.Done && tracks == null),
        onThisDay = (onThisDay.state as? WLoad.Done)?.value?.takeIf { d -> d.years.any { it.topTracks.isNotEmpty() } },
    )
}

/** The warehouse's "On repeat" songs, looking like Home's own section. Nothing unless [HomeHistory.onRepeat]. */
fun LazyListScope.warehouseOnRepeat(app: AppState, history: HomeHistory, liked: Set<String>) {
    val r = history.onRepeat ?: return
    item(key = "rep-head") {
        SectionHeader("On repeat", Modifier.padding(top = 30.dp, bottom = 6.dp), action = "Last ${r.windowDays} days", onAction = { app.open(Route.Stats) })
    }
    items(r.tracks.take(8), key = { "rep-" + it.trackId }) { t ->
        val playing = app.playingUri()
        PlayRow(
            t.imageUrl, t.name, dotted(t.artists, plays(t.plays)),
            onClick = { app.playTrack(t.uri) },
            onMore = { app.open(Route.HistoryTrack(t.trackId)) },
            playing = t.uri != null && t.uri == playing,
            liked = t.uri in liked,
        )
    }
}

/** "On this day": a card per earlier year with that day's top track, opening History on that day. */
fun LazyListScope.onThisDay(app: AppState, history: HomeHistory) {
    val d = history.onThisDay ?: return
    item(key = "otd") {
        Column(Modifier.padding(top = 30.dp)) {
            SectionHeader("On this day", action = d.date.format(DateTimeFormatter.ofPattern("d MMMM")))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 14.dp)) {
                items(d.years.filter { it.topTracks.isNotEmpty() }, key = { it.year }) { y ->
                    val top = y.topTracks.first()
                    Card(
                        "${y.year}  ·  ${plays(y.plays)}", dotted(top.name, top.artist), top.imageUrl,
                        { app.open(Route.HistoryDay(d.date.withYear(y.year))) }, size = 148.dp,
                    )
                }
            }
        }
    }
}
