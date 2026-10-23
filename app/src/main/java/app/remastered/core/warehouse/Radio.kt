package app.remastered.core.warehouse

/**
 * A radio built from the history (warehouse `/api/radio`): songs the user plays near a seed song
 * or artist, best first. Stands in for Spotify's recommendations and related artists, which the
 * Web API no longer gives apps (ADR 0004). A track seed's own editions are left out; an artist
 * seed's songs stay in, so its radio opens with them. Empty when the seed has too few plays.
 */
data class Radio(val seedName: String?, val tracks: List<RadioTrack>)

/** One song to queue. [id] is a real base-62 id: entries without one are dropped when mapping. */
data class RadioTrack(
    val id: String,
    val name: String,
    /** Primary artist. */
    val artist: String?,
    val album: String?,
    val image: String?,
    /** How many of the seed's listening sessions it was played in. */
    val sessions: Int,
) {
    val uri: String get() = "spotify:track:$id"
}
