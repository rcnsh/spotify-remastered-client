package app.remastered.core

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Name and art of the playlists, albums and artists Home shows, kept in [file] for [maxAgeMs]
 * so a start doesn't look each one up again: Home looks up a dozen or more, one request each.
 * Contexts the Web API won't return (Spotify's own playlists) are kept as misses, for less long.
 * Decodes leniently; a file it can't read counts as empty.
 */
class ContextTiles(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 7 * DAY_MS,
    private val missMaxAgeMs: Long = DAY_MS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Serializable
    data class Tile(val title: String, val subtitle: String, val imageUrl: String? = null, val round: Boolean = false)

    @Serializable private class Entry(val savedAt: Long, val tile: Tile? = null)
    @Serializable private class Saved(val version: Int = 0, val entries: Map<String, Entry> = emptyMap())

    /** What a lookup found: a [tile], or a miss (null) remembered so it isn't asked again. */
    class Hit(val tile: Tile?)

    private val lock = Mutex()
    private var entries: MutableMap<String, Entry>? = null
    private var dirty = false

    /** The tile for [uri], a remembered miss, or null when it has to be looked up. */
    suspend fun get(uri: String): Hit? = lock.withLock {
        val e = loaded()[uri] ?: return null
        val age = now() - e.savedAt
        if (age < 0 || age > if (e.tile == null) missMaxAgeMs else maxAgeMs) null else Hit(e.tile)
    }

    /** Remembers [tile] for [uri]; null records that the Web API won't return it. */
    suspend fun put(uri: String, tile: Tile?) = lock.withLock {
        loaded()[uri] = Entry(now(), tile)
        dirty = true
    }

    /** Writes what changed since the last save, dropping expired entries. */
    suspend fun save() = lock.withLock {
        val all = entries ?: return@withLock
        if (!dirty) return@withLock
        val t = now()
        all.values.removeAll { t - it.savedAt > maxAgeMs }
        val text = json.encodeToString(Saved.serializer(), Saved(VERSION, all.toMap()))
        withContext(io) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
        dirty = false
    }

    /** Forgets every tile, on disk too (signing out). */
    suspend fun clear() = lock.withLock {
        entries = mutableMapOf()
        dirty = false
        withContext(io) { runCatching { file.delete() } }
    }

    private suspend fun loaded(): MutableMap<String, Entry> = entries ?: withContext(io) {
        runCatching { json.decodeFromString<Saved>(file.readText()) }.getOrNull()
            ?.takeIf { it.version == VERSION }?.entries.orEmpty().toMutableMap()
    }.also { entries = it }

    private companion object {
        const val VERSION = 1
        const val DAY_MS = 24 * 60 * 60 * 1000L
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}
