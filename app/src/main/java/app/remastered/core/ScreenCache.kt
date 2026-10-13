package app.remastered.core

/**
 * The last data each screen loaded, kept in memory so going back to a screen shows it at once
 * while it refreshes in the background (see [app.remastered.ui.load]). Holds the [capacity]
 * most recently used entries; resetting the app builds a new container and drops them all.
 */
class ScreenCache(private val clock: () -> Long, private val capacity: Int = 40) {
    /** A cached value, when it was loaded, and the reload keys it was loaded with. */
    class Entry(val value: Any?, val loadedAt: Long, val version: List<Any?>)

    private val entries = object : LinkedHashMap<String, Entry>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
    }

    @Synchronized fun get(key: String): Entry? = entries[key]

    @Synchronized fun put(key: String, value: Any?, version: List<Any?>) {
        entries[key] = Entry(value, clock(), version)
    }

    /** Whether [entry] can be shown without refreshing: loaded with the same [version], less than [maxAgeMs] ago. */
    fun isFresh(entry: Entry, version: List<Any?>, maxAgeMs: Long) =
        entry.version == version && clock() - entry.loadedAt < maxAgeMs

    @Synchronized fun clear() = entries.clear()

    val size: Int @Synchronized get() = entries.size
}
