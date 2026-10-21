package app.remastered.spotify.web

import app.remastered.core.model.AlbumRef
import app.remastered.core.model.AlbumType
import app.remastered.core.model.DeviceType
import app.remastered.core.model.Image
import app.remastered.core.model.UserRef
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DTO -> model mapping on its own, from JSON shaped like the Web API's. */
class MappingTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private inline fun <reified T> decode(s: String) = json.decodeFromString<T>(s)

    @Test fun descriptionEntitiesAreDecoded() {
        assertEquals("It's <b>loud</b> & \"fun\" 'ok'", "It&#x27;s &lt;b&gt;loud&lt;/b&gt; &amp; &quot;fun&quot; &apos;ok&#39;".htmlText())
        assertEquals("Café 🎵", "Caf&#233; &#x1F3B5;".htmlText())
    }

    @Test fun descriptionDecodesOnce() {
        // A user who typed "&lt;" gets "&lt;" back, not "<".
        assertEquals("&lt;", "&amp;lt;".htmlText())
    }

    @Test fun descriptionKeepsWhatIsntAnEntity() {
        assertEquals("AT&T &nbsp; &#99999999; &#xZZ;", "AT&T &nbsp; &#99999999; &#xZZ;".htmlText())
    }

    @Test fun timestampsParseOrAreNull() {
        assertEquals(1_790_000_000_000, "2026-09-21T14:13:20Z".toEpochMs())
        assertEquals(1_790_000_000_123, "2026-09-21T14:13:20.123Z".toEpochMs())
        assertNull("2026-09-21".toEpochMs())
        assertNull("".toEpochMs())
        assertNull((null as String?).toEpochMs())
    }

    @Test fun imagesWithoutAUrlAreDropped() {
        val images = decode<ArtistDto>("""{"id":"a","images":[null,{"url":null,"width":64},{"url":"https://i/1","width":640,"height":640}]}""").images
        assertEquals(listOf(Image("https://i/1", 640, 640)), images.toImages())
        assertEquals(emptyList<Image>(), (null as List<ImageDto?>?).toImages())
    }

    @Test fun userWithoutADisplayNameShowsTheId() {
        assertEquals(UserRef("u1", "u1"), decode<UserDto>("""{"id":"u1","display_name":null}""").toModel())
    }

    @Test fun trackMapsAndKeepsOnlyArtistsWithIds() {
        val t = decode<TrackDto>(
            """{"id":"t1","name":"Song","type":"track","duration_ms":201000,"explicit":true,"track_number":3,"disc_number":2,
               "is_playable":false,"artists":[{"id":"a1","name":"One"},{"name":"No id"},null],
               "album":{"id":"al1","name":"Album","images":[{"url":"https://i/al1"}]}}""",
        ).toModel()!!
        assertEquals("spotify:track:t1", t.uri)
        assertEquals(listOf("a1"), t.artists.map { it.id })
        assertEquals(AlbumRef("al1", "Album", listOf(Image("https://i/al1"))), t.album)
        assertEquals(201_000, t.durationMs)
        assertTrue(t.explicit)
        assertEquals(3, t.trackNumber)
        assertEquals(2, t.discNumber)
        assertFalse(t.isPlayable)
        assertFalse(t.isEpisode)
    }

    @Test fun tracksTheAppCantPlayAreDropped() {
        assertNull(decode<TrackDto>("""{"name":"No id","type":"track"}""").toModel())
        assertNull(decode<TrackDto>("""{"id":"l1","name":"Local","type":"track","is_local":true}""").toModel())
        assertNull(decode<TrackDto>("""{"id":"x1","name":"Advert","type":"ad"}""").toModel())
    }

    @Test fun trackWithoutAnAlbumTakesTheFallback() {
        val parent = AlbumRef("al9", "Parent")
        assertEquals(parent, decode<TrackDto>("""{"id":"t1","type":"track"}""").toModel(parent)!!.album)
        assertNull(decode<TrackDto>("""{"id":"t1","type":"track"}""").toModel()!!.album)
    }

    @Test fun episodeHasItsShowAsAlbumAndNoArtists() {
        val e = decode<TrackDto>(
            """{"id":"e1","name":"Ep","type":"episode","duration_ms":1000,"images":[{"url":"https://i/e1"}],
               "artists":[{"id":"a1","name":"Ignored"}],"show":{"id":"s1","name":"Show","images":[]}}""",
        ).toModel()!!
        assertTrue(e.isEpisode)
        assertEquals("spotify:episode:e1", e.uri)
        assertEquals(emptyList<Any>(), e.artists)
        // The show has no art, so the episode's stands in.
        assertEquals(AlbumRef("s1", "Show", listOf(Image("https://i/e1"))), e.album)
    }

    @Test fun onlyEpisodesBecomeEpisodes() {
        val show = AlbumRef("s1", "Show")
        val e = decode<TrackDto>("""{"id":"e1","type":"episode","description":"About","release_date":"2026-10-01"}""").toEpisode(show)!!
        assertEquals(show, e.track.album)
        assertEquals("About", e.description)
        assertEquals("2026-10-01", e.releaseDate)
        assertNull(decode<TrackDto>("""{"id":"t1","type":"track"}""").toEpisode(show))
    }

    @Test fun albumTypes() {
        fun type(t: String?) = AlbumDto(id = "a", albumType = t).toModel()!!.type
        assertEquals(AlbumType.Single, type("single"))
        assertEquals(AlbumType.Single, type("SINGLE"))
        assertEquals(AlbumType.Compilation, type("compilation"))
        assertEquals(AlbumType.Album, type("album"))
        assertEquals(AlbumType.Album, type("appears_on"))
        assertEquals(AlbumType.Album, type(null))
        assertNull(AlbumDto(id = null).toModel())
    }

    @Test fun playlistWithoutAnOwnerOrCount() {
        val p = decode<PlaylistDto>("""{"id":"p1","name":"Mix","images":null,"description":null}""").toModel()!!
        assertEquals(UserRef("", ""), p.owner)
        assertEquals(0, p.trackCount)
        assertEquals("", p.description)
        assertEquals(emptyList<Image>(), p.images)
    }

    @Test fun playlistEntriesSkipLocalFilesAndEmptySlots() {
        val entries = decode<PagingDto<PlaylistEntryDto>>(
            """{"items":[
                {"added_at":"2026-09-21T14:13:20Z","item":{"id":"t1","type":"track"}},
                {"is_local":true,"item":{"id":"l1","type":"track"}},
                {"added_at":"2026-09-21T14:13:20Z","item":null,"track":null},
                {"added_at":"not a date","track":{"id":"t2","type":"track"}}
            ],"total":4}""",
        ).items.map { it?.toModel(position = 7) }
        assertEquals(listOf("t1", null, null, "t2"), entries.map { it?.track?.id })
        assertEquals(1_790_000_000_000, entries[0]!!.addedAtEpochMs)
        assertEquals(7, entries[0]!!.position)
        assertNull(entries[3]!!.addedAtEpochMs)
    }

    @Test fun playHistoryNeedsATrackAndATime() {
        val ok = decode<PlayHistoryDto>(
            """{"track":{"id":"t1","type":"track"},"played_at":"2026-09-21T14:13:20Z","context":{"uri":"spotify:album:al1","type":"album"}}""",
        ).toModel()!!
        assertEquals("spotify:album:al1", ok.context?.uri)
        assertNull(decode<PlayHistoryDto>("""{"track":{"id":"t1","type":"track"}}""").toModel())
        assertNull(decode<PlayHistoryDto>("""{"played_at":"2026-09-21T14:13:20Z"}""").toModel())
        assertNull(decode<PlayHistoryDto>("""{"track":{"id":"t1","type":"track"},"played_at":"2026-09-21T14:13:20Z","context":null}""").toModel()!!.context)
    }

    @Test fun deviceTypesAndVolume() {
        fun device(type: String, volume: Int? = null, supports: Boolean? = null) =
            DeviceDto("d", "Device", type, volumePercent = volume, supportsVolume = supports).toModel()
        assertEquals(DeviceType.Speaker, device("AVR").type)
        assertEquals(DeviceType.Speaker, device("AudioDongle").type)
        assertEquals(DeviceType.TV, device("STB").type)
        assertEquals(DeviceType.CastAudio, device("CastAudio").type)
        assertEquals(DeviceType.Tablet, device("Tablet").type)
        assertEquals(DeviceType.Other, device("Automobile").type)
        // Older responses leave out supports_volume: a volume means it has one.
        assertTrue(device("Computer", volume = 40).supportsVolume)
        assertFalse(device("Computer").supportsVolume)
        assertFalse(device("Computer", volume = 40, supports = false).supportsVolume)
    }

    @Test fun pagesDropWhatDoesntMapAndKnowIfThereIsMore() {
        val page = decode<PagingDto<TrackDto>>(
            """{"items":[{"id":"t1","type":"track"},null,{"type":"track"}],"offset":50,"total":120,"next":"https://api.spotify.com/v1/x?offset=100"}""",
        ).toPage { it.toModel() }
        assertEquals(listOf("t1"), page.items.map { it.id })
        assertEquals(50, page.offset)
        assertEquals(120, page.total)
        assertTrue(page.hasMore)
        assertFalse(PagingDto<TrackDto>().toPage { it.toModel() }.hasMore)
    }
}
