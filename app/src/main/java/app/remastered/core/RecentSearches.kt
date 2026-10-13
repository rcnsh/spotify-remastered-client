package app.remastered.core

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the user opened from search, newest first. Kept on the device only. */
class RecentSearches(private val prefs: SharedPreferences, private val max: Int = 12, private val enabled: () -> Boolean = { true }) {
    @Serializable
    data class Entry(val uri: String, val title: String, val subtitle: String, val imageUrl: String?, val round: Boolean)

    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow(read())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private fun read(): List<Entry> =
        runCatching { json.decodeFromString<List<Entry>>(prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())

    private fun write(list: List<Entry>) {
        _entries.value = list
        prefs.edit().putString(KEY, json.encodeToString(list)).apply()
    }

    fun add(entry: Entry) {
        if (enabled()) write((listOf(entry) + _entries.value.filterNot { it.uri == entry.uri }).take(max))
    }
    fun remove(uri: String) = write(_entries.value.filterNot { it.uri == uri })
    fun clear() = write(emptyList())

    private companion object { const val KEY = "recent" }
}
