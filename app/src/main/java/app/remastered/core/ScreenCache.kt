package app.remastered.core

import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The last data each screen loaded, kept in memory so going back to a screen shows it at once
 * while it refreshes in the background (see [app.remastered.ui.load]). Holds the [capacity]
 * most recently used entries; resetting the app builds a new container and drops them all.
 *
 * Screens that cost several requests (Your Library) can also keep theirs in [dir], so a start
 * shows what they had and only asks Spotify again once it's too old. [wallClock] dates those
 * files; [clock] (which may be time since boot) dates entries in memory.
 */
class ScreenCache(
    private val clock: () -> Long,
    private val capacity: Int = 40,
    private val dir: File? = null,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    /** A cached value, when it was loaded, and the reload keys it was loaded with. */
    class Entry(val value: Any?, val loadedAt: Long, val version: List<Any?>)

    private val entries = object : LinkedHashMap<String, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }

    @Synchronized fun get(key: String): Entry? = entries[key]

    @Synchronized fun put(key: String, value: Any?, version: List<Any?>) {
        entries[key] = Entry(value, clock(), version)
    }

    /**
     * Replaces the value under [key] after the screen changed it locally (a playlist reordered),
     * keeping when it was loaded and its keys; does nothing if it's no longer cached.
     */
    @Synchronized fun update(key: String, value: Any?) {
        entries[key]?.let { entries[key] = Entry(value, it.loadedAt, it.version) }
    }

    /**
     * Whether [entry] can be shown without refreshing: loaded with the same [version], less than
     * [maxAgeMs] ago. Keys compare as text, so an entry read back from disk compares too.
     */
    fun isFresh(entry: Entry, version: List<Any?>, maxAgeMs: Long) =
        entry.version.map(Any?::toString) == version.map(Any?::toString) && clock() - entry.loadedAt in 0 until maxAgeMs

    @Synchronized fun clear() = entries.clear()

    val size: Int @Synchronized get() = entries.size

    /** Writes [value] for [key] to disk. Blocking: call off the main thread. */
    fun <T> save(key: String, value: T, version: List<Any?>, serializer: KSerializer<T>) {
        val file = file(key) ?: return
        runCatching {
            val saved = Saved(VERSION, wallClock(), version.map(Any?::toString), json.encodeToJsonElement(serializer, value))
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), saved))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    /**
     * Reads [key] back from disk into memory (unless something is there already) and returns
     * it, dated by when it was saved. Blocking: call off the main thread.
     */
    fun <T> restore(key: String, serializer: KSerializer<T>): Entry? {
        get(key)?.let { return it }
        val file = file(key)?.takeIf { it.exists() } ?: return null
        val saved = runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull()?.takeIf { it.version == VERSION } ?: return null
        val value = runCatching { json.decodeFromJsonElement(serializer, saved.value) }.getOrNull() ?: return null
        val entry = Entry(value, clock() - (wallClock() - saved.savedAt), saved.keys)
        synchronized(this) { entries.getOrPut(key) { entry } }
        return entry
    }

    /** Deletes everything saved to disk (signing out). Blocking: call off the main thread. */
    fun clearDisk() { dir?.listFiles()?.forEach { it.delete() } }

    private fun file(key: String) = dir?.let { File(it, key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json") }

    @Serializable private class Saved(val version: Int = 0, val savedAt: Long = 0, val keys: List<String> = emptyList(), val value: JsonElement)

    private companion object {
        const val VERSION = 1
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    }
}
