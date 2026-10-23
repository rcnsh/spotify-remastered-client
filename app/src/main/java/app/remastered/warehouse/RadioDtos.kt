package app.remastered.warehouse

import app.remastered.core.warehouse.Radio
import app.remastered.core.warehouse.RadioTrack
import app.remastered.core.warehouse.spotifyUri
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire shape of /api/radio (src/radio.ts in music-warehouse; README "Radio" and docs/openapi.yaml
// there). Kept by hand on both sides, as Dtos.kt is, and decoded as leniently: every field is
// optional, and an entry the player can't use is dropped rather than failing the radio.

@Serializable
internal data class RadioDto(val seed: RadioSeedDto? = null, val tracks: List<RadioTrackDto?> = emptyList())

@Serializable
internal data class RadioSeedDto(val type: String? = null, val id: String? = null, val name: String? = null)

@Serializable
internal data class RadioTrackDto(
    val id: String? = null,
    val name: String? = null,
    val artist: String? = null,
    @SerialName("artist_id") val artistId: String? = null,
    val album: String? = null,
    val image: String? = null,
    val sessions: Int = 0,
)

/** Tracks with a real Spotify id only (the export's `name:` placeholders can't be queued), each once. */
internal fun RadioDto.toModel() = Radio(
    seedName = seed?.name?.takeIf { it.isNotBlank() },
    tracks = tracks.mapNotNull { t ->
        val id = t?.id?.takeIf { spotifyUri("track", it) != null } ?: return@mapNotNull null
        RadioTrack(id, t.name.orEmpty(), t.artist, t.album, t.image, t.sessions)
    }.distinctBy { it.id },
)
