package app.remastered.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Library : Route
    data object LikedSongs : Route
    data class Playlist(val id: String) : Route
    data class Album(val id: String) : Route
    data class Artist(val id: String) : Route
    data object Settings : Route

    // Listening history, from the user's music-warehouse. Entry points show only while
    // AppContainer.warehouse.state.isUsable; each screen lives in its own package (ui.history,
    // ui.stats, ui.wrapped) so features can grow without touching each other.
    /** Every play, newest first. */
    data object History : Route
    /** One artist in the history; [id] may be null for artists only known by name. */
    data class HistoryArtist(val id: String?, val name: String) : Route
    /** One track in the history, by Spotify track id. */
    data class HistoryTrack(val id: String) : Route
    /** Listening stats: on repeat, rhythm, top over time, eras, albums. */
    data object Stats : Route
    /** Wrapped: pick a range. */
    data object Wrapped : Route
    /** The Wrapped story for one range (full screen: no mini player or tabs). */
    data class WrappedStory(val from: LocalDate, val to: LocalDate) : Route

    companion object {
        /** Opens a `spotify:` uri as a screen, or null if the app has no screen for it. */
        fun of(uri: String): Route? {
            val parts = uri.split(':')
            if (parts.size < 3 || parts[0] != "spotify") return null
            return when (parts[1]) {
                "playlist" -> Playlist(parts[2])
                "album" -> Album(parts[2])
                "artist" -> Artist(parts[2])
                "collection" -> LikedSongs
                "user" -> if (parts.getOrNull(3) == "collection") LikedSongs else if (parts.getOrNull(3) == "playlist") parts.getOrNull(4)?.let(::Playlist) else null
                else -> null
            }
        }
    }
}

enum class Tab(val root: Route, val label: String) {
    Home(Route.Home, "Home"), Search(Route.Search, "Search"), Library(Route.Library, "Your Library")
}

/** One back stack per tab, like Spotify: switching tabs keeps each tab where you left it. */
class Navigator {
    var tab by mutableStateOf(Tab.Home)
        private set
    private val stacks = Tab.entries.associateWith { mutableStateListOf(it.root) }

    val current: Route get() = stacks.getValue(tab).last()
    val depth: Int get() = stacks.getValue(tab).size

    fun select(t: Tab) {
        // Tapping the current tab again goes back to its root.
        if (t == tab) stacks.getValue(t).let { s -> while (s.size > 1) s.removeAt(s.lastIndex) }
        tab = t
    }

    fun push(route: Route) {
        val s = stacks.getValue(tab)
        if (s.last() != route) s.add(route)
    }

    /** Returns false when there's nothing to go back to (let the system handle it). */
    fun pop(): Boolean {
        val s = stacks.getValue(tab)
        if (s.size > 1) { s.removeAt(s.lastIndex); return true }
        if (tab != Tab.Home) { tab = Tab.Home; return true }
        return false
    }
}
