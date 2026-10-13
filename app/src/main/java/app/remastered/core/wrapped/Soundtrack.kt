package app.remastered.core.wrapped

import app.remastered.core.lyrics.Lyrics
import app.remastered.core.warehouse.Wrapped
import java.util.Locale

/**
 * What plays under the story while it's viewed in the app (ADR 0006). The web story has one
 * soundtrack file; the phone can play the real songs through App Remote, so each slide that is
 * *about* a song cues it, and the rest keep whatever is playing.
 *
 * The warehouse's Wrapped has names, not ids, so a cue is a name and artist that the player
 * resolves to a `spotify:track:` uri (a Web API search). A cue that can't be resolved is skipped.
 */
data class SongCue(val name: String, val artist: String?) {
    /** Stable key, for caching resolved uris. */
    val key get() = "${name.lowercase(Locale.ROOT)}|${artist?.lowercase(Locale.ROOT).orEmpty()}"
}

object WrappedSoundtrack {
    /**
     * The song [slide] cues, or null to keep the current one.
     *
     * - Intro → the favourite of the most recent month (the year's last chapter as a curtain-raiser),
     *   else the top song. Minutes and Clock keep it.
     * - Artist tease, Artist → the top artist's top song; Artists keeps it.
     * - Song → the top song; Songs keeps it.
     * - Album → the most-played of the top songs from that album, if any.
     * - Discoveries → the biggest find (a search by artist).
     * - Summary → the top song again, to close on it.
     */
    fun cue(slide: SlideId, data: Wrapped): SongCue? {
        val top = data.topSongs.firstOrNull()?.let { SongCue(it.name, it.artist) }
        return when (slide) {
            SlideId.Intro -> data.months.lastOrNull()?.let { m -> m.topSong?.let { SongCue(it, m.topArtist) } } ?: top
            SlideId.ArtistTease, SlideId.Artist -> data.topArtists.firstOrNull()?.let { a -> a.topSong?.let { SongCue(it, a.name) } }
            SlideId.Song, SlideId.Summary -> top
            SlideId.Album -> data.topAlbums.firstOrNull()?.let { album ->
                data.topSongs.firstOrNull { it.album.equals(album.name, ignoreCase = true) && it.artist.equals(album.artist, ignoreCase = true) }
                    ?.let { SongCue(it.name, it.artist) }
            }
            SlideId.Discoveries -> data.discoveries.top.firstOrNull()?.let { SongCue("", it.name) }
            else -> null
        }
    }

    /** The cue in effect on [slide]: its own, else the nearest earlier slide's. */
    fun cueAt(timeline: WrappedTimeline, index: Int, data: Wrapped): SongCue? {
        for (i in index downTo 0) cue(timeline.slides[i].id, data)?.let { return it }
        // Nothing before it cues a song (a story without the intro's data): look ahead instead.
        return timeline.slides.drop(index + 1).firstNotNullOfOrNull { cue(it.id, data) }
    }

    /** A hook after this point is too late to be worth jumping to. */
    private const val MIN_TAIL_MS = 20_000L

    /**
     * Where to start a song: the first time its chorus is sung, taken as the most repeated lyric
     * line (needs at least two repeats and a few words, so ad-libs don't count); without synced
     * lyrics, about 30% in, where most pop songs reach their first chorus.
     */
    fun hookMs(lyrics: Lyrics?, durationMs: Long): Long {
        val fallback = if (durationMs > 0) (durationMs * 0.3).toLong() else 0L
        if (lyrics == null || lyrics.sync == Lyrics.Sync.Static) return fallback
        val seen = LinkedHashMap<String, Pair<Int, Long>>()
        for (line in lyrics.lines) {
            val key = normalise(line.lead.text)
            if (key.length < 12 && key.count { it == ' ' } < 2) continue
            val (count, first) = seen[key] ?: (0 to line.startMs)
            seen[key] = (count + 1) to first
        }
        val best = seen.values.filter { it.first >= 2 }.maxWithOrNull(compareBy<Pair<Int, Long>> { it.first }.thenByDescending { it.second }) ?: return fallback
        val start = best.second
        if (durationMs > 0 && start > durationMs - MIN_TAIL_MS) return fallback
        return start
    }

    private fun normalise(text: String) = text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N} ]"), "").replace(Regex("\\s+"), " ").trim()
}
