package app.remastered.ui.wrapped

import androidx.compose.runtime.Composable
import app.remastered.ui.AppState
import app.remastered.ui.load
import app.remastered.ui.screens.PlaceholderScreen
import app.remastered.ui.screens.line
import java.time.LocalDate

/**
 * The Wrapped story for one range (`Route.WrappedStory`), full screen: RemasteredRoot hides the
 * mini player and tabs on this route. TODO(wrapped): the story player.
 */
@Composable
fun WrappedStoryScreen(app: AppState, from: LocalDate, to: LocalDate) {
    val wrapped = load(from, to) { app.c.warehouse.api.wrapped(from, to) }
    PlaceholderScreen(app, "Wrapped $from – $to", "the story: totals, top artists, songs, albums, months, age.", wrapped) {
        line("${it.totals.minutes} minutes, ${it.totals.plays} plays, ${it.totals.artists} artists")
        it.topArtists.firstOrNull()?.let { a -> line("Top artist: ${a.name}") }
        it.topSongs.firstOrNull()?.let { s -> line("Top song: ${s.name} · ${s.artist}") }
    }
}
