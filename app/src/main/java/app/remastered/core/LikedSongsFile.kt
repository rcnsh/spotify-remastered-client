package app.remastered.core

import app.remastered.core.model.AlbumRef
import app.remastered.core.model.ArtistRef
import app.remastered.core.model.Image
import app.remastered.core.model.Track
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [LikedIndex.Store] as one JSON file. Decodes leniently; a file it can't read counts as nothing
 * saved. A [legacy] file (where earlier versions kept it, in the cache) is moved to [file].
 */
class LikedSongsFile(private val file: File, private val legacy: File? = null) : LikedIndex.Store {
    override fun load(): List<Track>? {
        val old = legacy
        if (!file.exists() && old != null && old.exists()) {
            file.parentFile?.mkdirs()
            if (!old.renameTo(file)) runCatching { old.copyTo(file, overwrite = true); old.delete() }
        }
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<Saved>(file.readText()) }.getOrNull()
            ?.takeIf { it.version == VERSION }?.tracks?.map { it.toModel() }
    }

    override fun save(tracks: List<Track>) {
        file.parentFile?.mkdirs()
        // Write then rename, so a crash mid-write can't leave half a file.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Saved.serializer(), Saved(VERSION, tracks.map { it.toSaved() })))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    override fun clear() {
        file.delete()
        legacy?.delete()
    }

    @Serializable private class Saved(val version: Int = 0, val tracks: List<SavedTrack> = emptyList())

    @Serializable private class SavedTrack(
        val id: String,
        val name: String = "",
        val artists: List<SavedArtist> = emptyList(),
        val album: SavedAlbum? = null,
        val durationMs: Long = 0,
        val explicit: Boolean = false,
        val isPlayable: Boolean = true,
        val isEpisode: Boolean = false,
    ) {
        fun toModel() = Track(id, name, artists.map { ArtistRef(it.id, it.name) }, album?.let { a ->
            AlbumRef(a.id, a.name, a.images.map { Image(it.url, it.width, it.height) })
        }, durationMs, explicit, isPlayable = isPlayable, isEpisode = isEpisode)
    }

    @Serializable private class SavedArtist(val id: String, val name: String = "")
    @Serializable private class SavedAlbum(val id: String, val name: String = "", val images: List<SavedImage> = emptyList())
    @Serializable private class SavedImage(val url: String, val width: Int? = null, val height: Int? = null)

    private fun Track.toSaved() = SavedTrack(
        id, name, artists.map { SavedArtist(it.id, it.name) },
        album?.let { a -> SavedAlbum(a.id, a.name, a.images.map { SavedImage(it.url, it.width, it.height) }) },
        durationMs, explicit, isPlayable, isEpisode,
    )

    private companion object {
        const val VERSION = 1
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}
