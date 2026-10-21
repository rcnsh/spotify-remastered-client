package app.remastered.core

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {
    /** SharedPreferences over [values], enough for getString/putString/apply. */
    private class MemoryPrefs(val values: MutableMap<String, String?> = mutableMapOf()) {
        val prefs: SharedPreferences = proxy { name, args ->
            when (name) {
                "getString" -> values[args[0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("unused: $name")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, String?>()
            lateinit var e: SharedPreferences.Editor
            e = proxy { name, args ->
                when (name) {
                    "putString" -> { pending[args[0] as String] = args[1] as String?; e }
                    "apply" -> { values += pending; null }
                    "commit" -> { values += pending; true }
                    else -> error("unused: $name")
                }
            }
            return e
        }

        private inline fun <reified T> proxy(crossinline answer: (String, Array<Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args -> answer(m.name, args ?: emptyArray()) } as T
    }

    private fun entry(n: Int) = RecentSearches.Entry("spotify:album:$n", "Album $n", "Artist", null, round = false)
    private fun RecentSearches.uris() = entries.value.map { it.uri }

    @Test fun newestFirstWithoutDuplicates() {
        val recent = RecentSearches(MemoryPrefs().prefs)
        recent.add(entry(1))
        recent.add(entry(2))
        recent.add(entry(1))
        assertEquals(listOf("spotify:album:1", "spotify:album:2"), recent.uris())
    }

    @Test fun keepsOnlyTheNewest() {
        val recent = RecentSearches(MemoryPrefs().prefs, max = 3)
        (1..5).forEach { recent.add(entry(it)) }
        assertEquals(listOf("spotify:album:5", "spotify:album:4", "spotify:album:3"), recent.uris())
    }

    @Test fun removeAndClear() {
        val recent = RecentSearches(MemoryPrefs().prefs)
        (1..3).forEach { recent.add(entry(it)) }
        recent.remove("spotify:album:2")
        assertEquals(listOf("spotify:album:3", "spotify:album:1"), recent.uris())
        recent.clear()
        assertEquals(emptyList<String>(), recent.uris())
    }

    @Test fun survivesARestart() {
        val prefs = MemoryPrefs()
        RecentSearches(prefs.prefs).apply { add(entry(1)); add(entry(2)) }
        assertEquals(listOf(entry(2), entry(1)), RecentSearches(prefs.prefs).entries.value)
    }

    @Test fun switchedOffRecordsNothingButCanStillBeCleared() {
        val prefs = MemoryPrefs()
        RecentSearches(prefs.prefs).add(entry(1))
        val recent = RecentSearches(prefs.prefs, enabled = { false })
        recent.add(entry(2))
        assertEquals(listOf("spotify:album:1"), recent.uris())
        recent.clear()
        assertEquals(emptyList<String>(), RecentSearches(prefs.prefs).uris())
    }

    @Test fun anUnreadableSavedListStartsEmpty() {
        val recent = RecentSearches(MemoryPrefs(mutableMapOf("recent" to "{not json")).prefs)
        assertEquals(emptyList<String>(), recent.uris())
        recent.add(entry(1))
        assertEquals(listOf("spotify:album:1"), recent.uris())
    }
}
