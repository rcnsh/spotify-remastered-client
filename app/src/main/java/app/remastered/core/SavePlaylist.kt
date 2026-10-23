package app.remastered.core

import app.remastered.core.model.Playlist

/**
 * Turns a list of songs the app shows from the history (On repeat, top songs for a range) into a
 * new private playlist in the user's account.
 *
 * Two requests, whatever the length: one create, then one add of up to [MAX_SONGS] songs (ADR
 * 0008: never a request per song). Songs without a Spotify id (plays imported from an export can
 * lack one), repeats and anything past [MAX_SONGS] are left out; [Saved.skipped] counts them.
 */
object SavePlaylist {
    /** One add request's worth (Spotify takes 100 uris per call); longer lists are cut here. */
    const val MAX_SONGS = 100

    const val DESCRIPTION = "Made by Remastered from your listening history."

    data class Saved(val playlist: Playlist, val added: Int, val skipped: Int)

    /**
     * The playlist was created, or not, and then something failed. [created] is the new (empty)
     * playlist when only adding the songs failed, so the message can offer to open it.
     */
    class Failed(val created: Playlist?, override val cause: CatalogException) : Exception(cause.message, cause)

    /** Track uris worth sending: real Spotify ids only, in order, each once, at most [MAX_SONGS]. */
    fun uris(uris: List<String?>): List<String> =
        uris.filterNotNull().filter { TRACK_URI.matches(it) }.distinct().take(MAX_SONGS)

    /**
     * Creates [name] and adds [uris] to it. Throws [Failed]; an empty list throws
     * [IllegalArgumentException] before anything is sent.
     */
    suspend fun save(catalog: Catalog, name: String, uris: List<String?>, description: String = DESCRIPTION): Saved {
        val songs = uris(uris)
        require(songs.isNotEmpty()) { "No songs with a Spotify id to save" }
        val playlist = try {
            catalog.createPlaylist(name.trim().ifEmpty { "My playlist" }, description)
        } catch (e: CatalogException) {
            throw Failed(null, e)
        }
        try {
            catalog.addToPlaylist(playlist.id, songs)
        } catch (e: CatalogException) {
            throw Failed(playlist, e)
        }
        return Saved(playlist.copy(trackCount = songs.size), songs.size, uris.size - songs.size)
    }

    private val TRACK_URI = Regex("spotify:track:[0-9A-Za-z]{22}")
}
