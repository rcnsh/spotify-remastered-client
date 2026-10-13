package app.remastered.core.wrapped

import app.remastered.core.lyrics.Attribution
import app.remastered.core.lyrics.LyricLine
import app.remastered.core.lyrics.Lyrics
import app.remastered.core.lyrics.Provider
import app.remastered.core.lyrics.Syllable
import app.remastered.core.lyrics.Vocal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundtrackTest {
    private val data = WrappedFixture.load()
    private val timeline = WrappedTimeline.of(data)

    private fun lyrics(vararg lines: Pair<Long, String>, sync: Lyrics.Sync = Lyrics.Sync.Line) = Lyrics(
        "t", sync,
        lines.map { (at, text) -> LyricLine(at, at + 3000, Vocal(at, at + 3000, listOf(Syllable(text, at, at + 3000)))) },
        emptyList(), Attribution(Provider.SpicyLyrics),
    )

    @Test fun `the hook is the first time the chorus is sung`() {
        val l = lyrics(
            10_000L to "Walking out the door again",
            30_000L to "Oh",
            42_000L to "And we're dancing in the kitchen light",
            50_000L to "Oh",
            60_000L to "Oh",
            90_000L to "Second verse is here now",
            120_000L to "And we're dancing in the kitchen light!",
            150_000L to "and we're DANCING in the kitchen light",
        )
        assertEquals(42_000L, WrappedSoundtrack.hookMs(l, 200_000))
    }

    @Test fun `an outro chant sung back to back doesn't outnumber the chorus`() {
        // Chlorine's shape: the chorus line three times, then a chant sung twice in a row per bar.
        val l = lyrics(
            17_000L to "Sipping on straight chlorine",
            22_000L to "This beat is a chemical",
            70_000L to "Sipping on straight chlorine",
            156_000L to "Sipping on straight chlorine",
            179_000L to "Let the vibe, let the vibe",
            182_000L to "Let the vibe, let the vibe",
            185_000L to "Beat is a chemical, yeah",
            190_000L to "Let the vibe, let the vibe",
            193_000L to "Let the vibe, let the vibe",
            196_000L to "Moment is medical, yeah",
            201_000L to "Let the vibe, let the vibe",
            204_000L to "Let the vibe, let the vibe",
        )
        assertEquals(17_000L, WrappedSoundtrack.hookMs(l, 324_000))
    }

    @Test fun `without a repeated line or lyrics it starts 30 percent in`() {
        assertEquals(60_000L, WrappedSoundtrack.hookMs(null, 200_000))
        assertEquals(60_000L, WrappedSoundtrack.hookMs(lyrics(1_000L to "one line only here", 5_000L to "and another different one"), 200_000))
        assertEquals(60_000L, WrappedSoundtrack.hookMs(lyrics(1_000L to "static static static", 5_000L to "static static static", sync = Lyrics.Sync.Static), 200_000))
        // A chorus that first arrives in the last 20 s isn't a hook.
        assertEquals(60_000L, WrappedSoundtrack.hookMs(lyrics(185_000L to "la la la la la", 190_000L to "la la la la la"), 200_000))
    }

    @Test fun `each music slide cues its own song and the rest keep playing`() {
        assertEquals(SongCue("Glasshouse", "The Night Bus"), WrappedSoundtrack.cue(SlideId.Intro, data))
        assertNull(WrappedSoundtrack.cue(SlideId.Minutes, data))
        assertEquals(SongCue("Glasshouse", "Paper Lanterns"), WrappedSoundtrack.cue(SlideId.Artist, data))
        assertEquals(SongCue("Glasshouse", "Paper Lanterns"), WrappedSoundtrack.cue(SlideId.Song, data))
        // The top album "Lanterns" is the top song's album.
        assertEquals(SongCue("Glasshouse", "Paper Lanterns"), WrappedSoundtrack.cue(SlideId.Album, data))
        assertEquals(SongCue("", "Mira Vale"), WrappedSoundtrack.cue(SlideId.Discoveries, data))
        // Clock (index 2) carries on with the intro's song; Artists (5) with the artist's.
        assertEquals(WrappedSoundtrack.cue(SlideId.Intro, data), WrappedSoundtrack.cueAt(timeline, 2, data))
        assertEquals(WrappedSoundtrack.cue(SlideId.Artist, data), WrappedSoundtrack.cueAt(timeline, 5, data))
    }
}
