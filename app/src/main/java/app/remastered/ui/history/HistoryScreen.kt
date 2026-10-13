package app.remastered.ui.history

import androidx.compose.runtime.Composable
import app.remastered.ui.AppState
import app.remastered.ui.load
import app.remastered.ui.screens.PlaceholderScreen
import app.remastered.ui.screens.line
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every play, newest first, from the warehouse (`Route.History`).
 * TODO(history): the real screen. Page back with `plays(beforeMs = last.playedAtMs)`; days from `daily`.
 */
@Composable
fun HistoryScreen(app: AppState) {
    val plays = load(Unit) { app.c.warehouse.api.plays(limit = 20) }
    PlaceholderScreen(app, "Listening history", "list every play, grouped by day, paging back.", plays) { list ->
        val time = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())
        list.forEach { line("${time.format(it.playedAt)}  ${it.name} · ${it.artists}") }
    }
}

/** One artist's history (`Route.HistoryArtist`). TODO(history): the real screen. */
@Composable
fun HistoryArtistScreen(app: AppState, id: String?, name: String) {
    val artist = load(id, name) { app.c.warehouse.api.artist(id, name) }
    PlaceholderScreen(app, name, "plays by month, first and last play, top tracks.", artist) {
        line("${it.plays} plays, ${it.minutes} minutes, ${it.months.size} months")
    }
}

/** One track's history (`Route.HistoryTrack`). TODO(history): the real screen. */
@Composable
fun HistoryTrackScreen(app: AppState, id: String) {
    val track = load(id) { app.c.warehouse.api.track(id) }
    PlaceholderScreen(app, "Track history", "plays by month, first and last play.", track) {
        line("${it.name ?: id}: ${it.plays} plays, ${it.minutes} minutes")
    }
}
