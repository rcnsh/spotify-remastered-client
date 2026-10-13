package app.remastered.ui.stats

import androidx.compose.runtime.Composable
import app.remastered.ui.AppState
import app.remastered.ui.load
import app.remastered.ui.screens.PlaceholderScreen
import app.remastered.ui.screens.line

/**
 * Listening stats from the warehouse (`Route.Stats`): onRepeat, rhythm, topOverTime, eras, topAlbums.
 * TODO(stats): the real screen.
 */
@Composable
fun StatsScreen(app: AppState) {
    val repeat = load(Unit) { app.c.warehouse.api.onRepeat() }
    PlaceholderScreen(app, "Stats", "on repeat, rhythm, top over time, eras, albums.", repeat) {
        line("On repeat: ${it.tracks.size} songs, ${it.albums.size} albums")
        it.tracks.forEach { t -> line("${t.name} · ${t.plays} plays on ${t.days} days") }
    }
}
