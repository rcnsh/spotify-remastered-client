package app.remastered.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.remastered.core.warehouse.MonthPlays
import app.remastered.core.warehouse.TrackPlays
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Chip
import app.remastered.ui.components.EmptyMessage
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.SectionHeader
import app.remastered.ui.components.SubText
import app.remastered.ui.screens.TopBar
import app.remastered.ui.stats.AxisLabels
import app.remastered.ui.stats.Bars
import app.remastered.ui.stats.Sparkline
import app.remastered.ui.stats.monthSeries
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// One artist's and one track's history (`Route.HistoryArtist`, `Route.HistoryTrack`), and the
// compact "Your plays" card on the artist page.

private val MonthFormat = DateTimeFormatter.ofPattern("MMM yyyy")

/** Plays per month from the first play to now, every month shown; tapping one opens History at its end. */
@Composable
fun MonthsChart(app: AppState, months: List<MonthPlays>, modifier: Modifier = Modifier) {
    val now = remember { YearMonth.now() }
    val series = remember(months) { monthSeries(months, now) }
    if (series.isEmpty()) return
    val peak = series.maxBy { it.plays }
    Column(modifier) {
        Text("Most in ${peak.month.format(MonthFormat)}: ${plays(peak.plays)}", style = Type.caption, color = Palette.sub)
        Bars(series.map { it.plays.toFloat() }, Modifier.fillMaxWidth().height(120.dp).padding(top = 10.dp), gridLines = 2,
            highlight = series.indexOf(peak)) { i ->
            val m = series[i].month
            app.open(Route.HistoryDay(if (m == now) LocalDate.now() else m.atEndOfMonth()))
        }
        AxisLabels(listOf(series.first().month.format(MonthFormat), series.last().month.format(MonthFormat)), Modifier.padding(top = 6.dp))
    }
}

/** Plays, time listened, first and last play. */
@Composable
private fun Totals(plays: Int, minutes: Int, first: Instant?, last: Instant?) {
    Panel(Modifier.padding(top = 16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Stat("%,d".format(plays), "plays", Modifier.weight(1f))
            Stat(listened(minutes), "listened", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Stat(first?.let { dayText(it) } ?: "–", "first played", Modifier.weight(1f))
            Stat(last?.let { dayText(it) } ?: "–", "last played", Modifier.weight(1f))
        }
    }
}

private fun LazyListScope.topTracks(app: AppState, tracks: List<TrackPlays>, playing: String?, trackHistory: Boolean, title: String) {
    if (tracks.isEmpty()) return
    item(key = "top-title") { SectionHeader(title, Modifier.padding(top = 30.dp, bottom = 6.dp), small = true) }
    itemsIndexed(tracks, key = { i, t -> "top-$i-${t.id}" }) { i, t ->
        PlayRow(
            t.imageUrl, t.name, plays(t.plays),
            onClick = { app.playTrack(t.uri) },
            onMore = t.id?.takeIf { trackHistory }?.let { id -> { app.open(Route.HistoryTrack(id)) } },
            playing = t.uri != null && t.uri == playing, leading = "${i + 1}",
        )
    }
}

/** One artist's history: totals, plays by month, top tracks. [id] may be null for name-only artists. */
@Composable
fun HistoryArtistScreen(app: AppState, id: String?, name: String) {
    val data = loadWarehouse(id, name) { app.c.warehouse.api.artist(id, name) }
    val playing = app.playingUri()
    val trackHistory = app.warehouseSupports(WarehouseFeature.TrackHistory)
    Column(Modifier.fillMaxSize().background(Palette.base)) {
        TopBar(app, title = name, solid = true)
        LazyColumn(Modifier.weight(1f), contentPadding = ScreenPadding) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp)) {
                    Text(data.value?.name ?: name, style = Type.title, color = Palette.text, maxLines = 2)
                    SubText("Your plays", Modifier.padding(top = 4.dp))
                    val uri = data.value?.uri
                    if (uri != null) Chip("Artist page", selected = false, onClick = { app.openUri(uri) }, icon = Icons.User,
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
            when (val s = data.state) {
                WLoad.Loading, WLoad.Busy, is WLoad.Failed -> item(key = "state") {
                    SectionStatus((s as? WLoad.Failed)?.message, spinner = s !is WLoad.Failed, onRetry = data.retry.takeIf { s is WLoad.Failed })
                }
                is WLoad.Done -> {
                    val a = s.value
                    if (a.plays == 0) item(key = "empty") { EmptyMessage("No plays yet", "Nothing by ${a.name} is in your history.") }
                    else {
                        item(key = "totals") { Totals(a.plays, a.minutes, a.firstPlayed, a.lastPlayed) }
                        item(key = "months") {
                            Column {
                                SectionHeader("By month", Modifier.padding(top = 30.dp, bottom = 12.dp), small = true)
                                Panel { MonthsChart(app, a.months) }
                            }
                        }
                        topTracks(app, a.topTracks, playing, trackHistory, "Your top tracks")
                    }
                }
            }
        }
    }
}

/** One track's history, folding its other editions: totals and plays by month. */
@Composable
fun HistoryTrackScreen(app: AppState, id: String) {
    val data = loadWarehouse(id) { app.c.warehouse.api.track(id) }
    val playing = app.playingUri()
    val artistHistory = app.warehouseSupports(WarehouseFeature.ArtistHistory)
    Column(Modifier.fillMaxSize().background(Palette.base)) {
        TopBar(app, title = data.value?.name ?: "Track history", solid = true)
        LazyColumn(Modifier.weight(1f), contentPadding = ScreenPadding) {
            when (val s = data.state) {
                WLoad.Loading, WLoad.Busy, is WLoad.Failed -> item(key = "state") {
                    SectionStatus((s as? WLoad.Failed)?.message, spinner = s !is WLoad.Failed, onRetry = data.retry.takeIf { s is WLoad.Failed })
                }
                is WLoad.Done -> {
                    val t = s.value
                    item(key = "head") {
                        Row(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(t.imageUrl, Modifier.size(112.dp), RoundedCornerShape(14.dp))
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(t.name ?: "Unknown track", style = Type.headline, color = Palette.text, maxLines = 3)
                                t.artist?.let { SubText(it, Modifier.padding(top = 4.dp)) }
                                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val uri = t.uri
                                    if (uri != null) Chip(if (uri == playing) "Playing" else "Play", selected = true, onClick = { app.play(uri) }, icon = Icons.Play)
                                    val artist = t.artist
                                    if (artist != null && artistHistory) Chip("Artist", selected = false, icon = Icons.User,
                                        onClick = { app.open(Route.HistoryArtist(null, artist)) })
                                }
                            }
                        }
                    }
                    if (t.plays == 0) item(key = "empty") { EmptyMessage("No plays yet", "This track isn't in your history.") }
                    else {
                        item(key = "totals") { Totals(t.plays, t.minutes, t.firstPlayed, t.lastPlayed) }
                        item(key = "months") {
                            Column {
                                SectionHeader("By month", Modifier.padding(top = 30.dp, bottom = 12.dp), small = true)
                                Panel { MonthsChart(app, t.months) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Your plays" on an artist page: totals, a months sparkline and the top three tracks, opening
 * the full [HistoryArtistScreen]. Shows nothing without the warehouse, while loading, on failure
 * or with no plays: it's an extra, so it never gets in the way of the page.
 */
@Composable
fun YourPlays(app: AppState, id: String, name: String) {
    if (!app.warehouseSupports(WarehouseFeature.ArtistHistory)) return
    val data = loadWarehouse(id, name) { app.c.warehouse.api.artist(id, name) }
    val a = data.value ?: return
    if (a.plays == 0) return
    val playing = app.playingUri()
    val trackHistory = app.warehouseSupports(WarehouseFeature.TrackHistory)
    val open = { app.open(Route.HistoryArtist(id, name)) }
    val series = remember(a.months) { monthSeries(a.months, YearMonth.now()) }
    Column(Modifier.padding(top = 22.dp)) {
        SectionHeader("Your plays", action = "See all", onAction = open, small = true)
        Panel(Modifier.padding(top = 14.dp), onClick = open) {
            Row(Modifier.fillMaxWidth()) {
                Stat("%,d".format(a.plays), "plays", Modifier.weight(1f))
                Stat(listened(a.minutes), "listened", Modifier.weight(1f))
                Stat(a.lastPlayed?.let { ago(it) } ?: "–", "last played", Modifier.weight(1f))
            }
            if (series.size > 1) {
                Sparkline(series.map { it.plays }, Modifier.fillMaxWidth().height(44.dp).padding(top = 14.dp))
                AxisLabels(listOf("Since ${series.first().month.format(MonthFormat)}", "Now"), Modifier.padding(top = 4.dp))
            }
        }
        a.topTracks.take(3).forEachIndexed { i, t ->
            PlayRow(
                t.imageUrl, t.name, plays(t.plays),
                onClick = { app.playTrack(t.uri) },
                onMore = t.id?.takeIf { trackHistory }?.let { tid -> { app.open(Route.HistoryTrack(tid)) } },
                playing = t.uri != null && t.uri == playing, leading = "${i + 1}",
            )
        }
    }
}
