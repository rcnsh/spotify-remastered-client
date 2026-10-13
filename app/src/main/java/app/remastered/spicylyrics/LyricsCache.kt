package app.remastered.spicylyrics

import java.io.File

/**
 * Spicy Lyrics responses on disk, one file per track. Spicy Lyrics' terms allow caching but
 * require every stored response to be refetched or discarded within 30 days, so an entry is
 * deleted once it is [MAX_AGE_MS] old. "No lyrics" answers expire sooner ([NOT_FOUND_AGE_MS]) so
 * new community syncs show up. [now] is wall-clock time.
 */
class LyricsCache(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    sealed interface Entry {
        data class Found(val json: String) : Entry
        data object NotFound : Entry
    }

    @Synchronized
    fun get(trackId: String): Entry? {
        val found = file(trackId, FOUND)
        if (found.exists()) {
            if (fresh(found, MAX_AGE_MS)) return runCatching { Entry.Found(found.readText()) }.getOrNull()
            found.delete()
        }
        val none = file(trackId, NONE)
        if (none.exists()) {
            if (fresh(none, NOT_FOUND_AGE_MS)) return Entry.NotFound
            none.delete()
        }
        return null
    }

    @Synchronized
    fun put(trackId: String, json: String) {
        file(trackId, NONE).delete()
        write(file(trackId, FOUND), json)
    }

    @Synchronized
    fun putNotFound(trackId: String) {
        file(trackId, FOUND).delete()
        write(file(trackId, NONE), "")
    }

    @Synchronized
    fun remove(trackId: String) {
        file(trackId, FOUND).delete()
        file(trackId, NONE).delete()
    }

    /** Deletes expired entries. Returns how many. */
    @Synchronized
    fun sweep(): Int = files().count { f ->
        val maxAge = if (f.name.endsWith(FOUND)) MAX_AGE_MS else NOT_FOUND_AGE_MS
        !fresh(f, maxAge) && f.delete()
    }

    @Synchronized
    fun clear() { files().forEach { it.delete() } }

    /** Songs with lyrics stored, and their size in bytes. */
    @Synchronized
    fun stats(): Pair<Int, Long> = files().filter { it.name.endsWith(FOUND) }.let { it.size to it.sumOf(File::length) }

    private fun fresh(f: File, maxAge: Long): Boolean = now() - f.lastModified() in 0 until maxAge

    private fun write(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        target.setLastModified(now())
    }

    private fun files(): List<File> = dir.listFiles()?.filter { it.isFile && (it.name.endsWith(FOUND) || it.name.endsWith(NONE)) }.orEmpty()

    private fun file(trackId: String, suffix: String): File {
        require(SpicyLyricsApi.isTrackId(trackId)) { "Not a Spotify track id" }
        return File(dir, trackId + suffix)
    }

    companion object {
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val NOT_FOUND_AGE_MS = 24L * 60 * 60 * 1000
        private const val FOUND = ".json"
        private const val NONE = ".none"
    }
}
