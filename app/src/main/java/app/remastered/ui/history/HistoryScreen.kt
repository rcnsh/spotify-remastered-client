package app.remastered.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.remastered.core.warehouse.Play
import app.remastered.core.warehouse.PlayPager
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.core.warehouse.endOfDayMs
import app.remastered.core.warehouse.groupByDay
import app.remastered.core.warehouse.mergeRecent
import app.remastered.core.warehouse.toPlay
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.components.Chip
import app.remastered.ui.components.EmptyMessage
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.Loading
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.SubText
import app.remastered.ui.components.dotted
import app.remastered.ui.screens.TopBar
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private const val PAGE = 100

/** Load the next page when the list is this close to its end. */
private const val PREFETCH = 20

/**
 * Every play, newest first (`Route.History`), or from the end of [day] back (`Route.HistoryDay`).
 * Pages back by `before` as you scroll. Without a day, the Web API's recently-played fills in what
 * the warehouse hasn't polled yet. Tap plays; long-press or ⋯ opens the track's history.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(app: AppState, day: LocalDate? = null) {
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val api = app.c.warehouse.api
    /** Where the list starts; null means now. A day of today or later is the same as now. */
    var anchor by remember { mutableStateOf(day?.takeIf { it.isBefore(today) }) }
    var pager by remember { mutableStateOf<PlayPager?>(null) }
    var stored by remember { mutableStateOf(emptyList<Play>()) }
    var recent by remember { mutableStateOf(emptyList<Play>()) }
    var done by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val trackHistory = app.warehouseSupports(WarehouseFeature.TrackHistory)
    val playing = app.playingUri()
    val liked by app.c.liked.uris.collectAsState()

    /**
     * Starts over from [from]. With [keep], a failure leaves the current list (pull to refresh).
     * A reload overtaken by a jump to another day (a pull to refresh still running, say) is dropped.
     */
    suspend fun reload(from: LocalDate?, keep: Boolean) = coroutineScope {
        val p = PlayPager(from?.let { endOfDayMs(it, zone) }, PAGE) { before, limit -> api.playsPage(limit, before) }
        val latest = if (from == null) async {
            try {
                app.c.catalog.recentlyPlayed(50).map { it.toPlay() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        } else null
        try {
            p.loadMore()
            val fresh = latest?.await().orEmpty()
            if (from != anchor) return@coroutineScope
            pager = p
            stored = p.plays
            done = p.done
            error = null
            recent = fresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            latest?.cancel()
            if (from != anchor) return@coroutineScope
            if (keep && stored.isNotEmpty()) app.message(AppState.describe(e))
            else { pager = null; stored = emptyList(); recent = emptyList(); error = AppState.describe(e) }
        }
    }

    fun loadMore() {
        val p = pager ?: return
        if (loading || p.done || error != null) return
        loading = true
        scope.launch {
            try {
                p.loadMore()
                if (pager === p) { stored = p.plays; done = p.done }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (pager === p) error = AppState.describe(e)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(anchor) {
        stored = emptyList(); recent = emptyList(); pager = null; error = null; done = false
        list.scrollToItem(0)
        reload(anchor, keep = false)
    }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) to it.totalItemsCount } }
            .collect { (last, total) -> if (total > 0 && last >= total - PREFETCH) loadMore() }
    }

    val shown = remember(stored, recent, done, anchor) { if (anchor == null) mergeRecent(stored, recent, done) else stored }
    val days = remember(shown) { groupByDay(shown, zone) }

    Column(Modifier.fillMaxSize().background(Palette.base)) {
        TopBar(app, title = "Listening history", solid = true)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; try { reload(anchor, keep = true) } finally { refreshing = false } } },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = ScreenPadding) {
                item(key = "head") {
                    Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 8.dp)) {
                        SubText(anchor?.let { "From ${dayText(it)} back" } ?: "Every play, newest first")
                        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Jump to date", selected = false, onClick = { picking = true }, icon = HistoryIcons.Calendar)
                            if (anchor != null) Chip("Latest", selected = false, onClick = { anchor = null })
                        }
                    }
                }
                if (stored.isEmpty() && recent.isEmpty()) {
                    item(key = "state") {
                        when {
                            error != null -> ErrorMessage(error!!, { error = null; scope.launch { reload(anchor, keep = false) } })
                            pager == null -> Loading()
                            else -> EmptyMessage("No plays", if (anchor == null) "Nothing's in your warehouse yet." else "Nothing on or before ${dayText(anchor!!)}.")
                        }
                    }
                }
                days.forEachIndexed { i, (d, dayPlays) ->
                    stickyHeader(key = "day-$d") {
                        // The last day may still be loading: only whole days get a count.
                        val whole = i < days.lastIndex || done
                        Row(Modifier.fillMaxWidth().background(Palette.base).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
                            Text(dayHeader(d, today), style = Type.body, color = Palette.text, modifier = Modifier.weight(1f))
                            if (whole) Text(plays(dayPlays.size), style = Type.caption, color = Palette.sub)
                        }
                    }
                    items(dayPlays, key = { "${it.playedAtMs}:${it.trackId}" }) { p ->
                        PlayRow(
                            p.imageUrl, p.name, dotted(timeText(p.playedAtMs, zone), p.artists),
                            onClick = { app.playTrack(p.trackUri) },
                            onMore = if (trackHistory) ({ app.open(Route.HistoryTrack(p.trackId)) }) else null,
                            playing = p.trackUri != null && p.trackUri == playing,
                            liked = p.trackUri in liked,
                        )
                    }
                }
                if (stored.isNotEmpty()) item(key = "foot") {
                    when {
                        error != null -> ErrorMessage(error!!, { error = null; loadMore() })
                        done -> SubText("That's everything: your first stored play.", Modifier.fillMaxWidth().padding(24.dp), color = Palette.dim)
                        else -> Loading()
                    }
                }
            }
        }
    }

    if (picking) DayPickerDialog(anchor ?: today, today, onDismiss = { picking = false }) { picked ->
        picking = false
        anchor = picked.takeIf { it.isBefore(today) }
    }
}

/** Picks one local day up to [today]. Material's picker works in UTC midnights, so convert at the edges. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPickerDialog(initial: LocalDate, today: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val last = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 2008..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= last
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("Go", color = Palette.accentLight) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.sub) } },
    ) {
        DatePicker(state, title = { Text("Jump to a day", style = Type.label, color = Palette.sub, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}
