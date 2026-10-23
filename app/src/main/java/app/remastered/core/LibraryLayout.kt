package app.remastered.core

import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A folder in Your Library: the app's own (Spotify never exposed its folders, ADR 0004). */
@Serializable
data class LibraryFolder(val id: String, val name: String, val items: List<String> = emptyList()) {
    val uri get() = folderUri(id)
}

fun folderUri(id: String) = "$FOLDER_PREFIX$id"
private const val FOLDER_PREFIX = "remastered:folder:"

/**
 * How the user arranged Your Library: pinned uris (newest pin first) and folders of uris. Kept
 * on the phone. An item is in at most one folder; folders can be pinned, but don't nest. Uris that
 * leave Spotify are skipped when shown and dropped by [prune].
 */
@Serializable
data class LibraryLayout(val pins: List<String> = emptyList(), val folders: List<LibraryFolder> = emptyList()) {
    fun isPinned(uri: String) = uri in pins
    fun folder(id: String) = folders.firstOrNull { it.id == id }
    fun folderOf(uri: String) = folders.firstOrNull { uri in it.items }

    fun pin(uri: String) = copy(pins = listOf(uri) + (pins - uri))
    fun unpin(uri: String) = copy(pins = pins - uri)

    /** A new folder, listed first, holding [items] (taken out of any other folder). */
    fun createFolder(id: String, name: String, items: List<String> = emptyList()): LibraryLayout =
        copy(folders = listOf(LibraryFolder(id, name.trim())) + folders).let { l -> items.fold(l) { acc, uri -> acc.moveTo(uri, id) } }

    fun renameFolder(id: String, name: String) = copy(folders = folders.map { if (it.id == id) it.copy(name = name.trim()) else it })

    /** Deletes the folder only: what was in it goes back to the top of Your Library. */
    fun deleteFolder(id: String) = copy(folders = folders.filterNot { it.id == id }, pins = pins - folderUri(id))

    /** Moves [uri] into folder [folderId], or out of any folder with null. Unknown folders are ignored. */
    fun moveTo(uri: String, folderId: String?): LibraryLayout {
        if (folderId != null && folder(folderId) == null) return this
        return copy(folders = folders.map { f ->
            when {
                f.id == folderId -> if (uri in f.items) f else f.copy(items = listOf(uri) + f.items)
                else -> f.copy(items = f.items - uri)
            }
        })
    }

    /**
     * Drops pins and folder entries for Spotify uris no longer in [present]. Only kinds with at least
     * one uri in [present] are pruned: an empty kind more likely means its request failed than that
     * the user removed everything of it, and a stale uri costs nothing (it's skipped when shown).
     */
    fun prune(present: Set<String>): LibraryLayout {
        val kinds = present.mapNotNullTo(HashSet()) { kind(it) }
        val folderUris = folders.mapTo(HashSet()) { it.uri }
        fun keep(uri: String) = if (uri.startsWith(FOLDER_PREFIX)) uri in folderUris else kind(uri) !in kinds || uri in present
        val pruned = copy(pins = pins.filter(::keep), folders = folders.map { f -> f.copy(items = f.items.filter(::keep)) })
        return if (pruned == this) this else pruned
    }

    /**
     * The top level of Your Library from [items] (in the order the user sorted them): pinned entries
     * first, newest pin first, then folders in [folders] order (or by name with [foldersByName]),
     * then everything not in a folder.
     */
    fun <T> arrange(items: List<T>, uri: (T) -> String, foldersByName: Boolean = false): List<LibraryEntry<T>> {
        val byUri = items.associateBy(uri)
        val inFolders = folders.flatMapTo(HashSet()) { it.items }
        val folderEntries = (if (foldersByName) folders.sortedBy { it.name.lowercase() } else folders).map { f ->
            LibraryEntry.Folder<T>(f, f.items.mapNotNull(byUri::get), isPinned(f.uri))
        }
        val loose = items.filter { uri(it) !in inFolders }.map { LibraryEntry.Item(it, isPinned(uri(it))) }
        val all = folderEntries + loose
        val pinned = pins.mapNotNull { p -> all.firstOrNull { it.uri(uri) == p } }
        return pinned + all.filterNot { it.pinned }
    }

    /** What's in folder [id] that's still in [items], pinned first, otherwise in [items] order. */
    fun <T> contents(id: String, items: List<T>, uri: (T) -> String): List<LibraryEntry.Item<T>> {
        val members = folder(id)?.items?.toHashSet() ?: return emptyList()
        val inside = items.filter { uri(it) in members }.map { LibraryEntry.Item(it, isPinned(uri(it))) }
        val pinned = pins.mapNotNull { p -> inside.firstOrNull { uri(it.item) == p } }
        return pinned + inside.filterNot { it.pinned }
    }
}

/** `spotify:playlist:…` → "playlist"; null for anything that isn't a Spotify uri. */
private fun kind(uri: String) = uri.split(':').takeIf { it.size >= 3 && it[0] == "spotify" }?.get(1)

sealed interface LibraryEntry<T> {
    val pinned: Boolean
    data class Item<T>(val item: T, override val pinned: Boolean) : LibraryEntry<T>
    /** [members] are the folder's items still in the library, in the folder's own order. */
    data class Folder<T>(val folder: LibraryFolder, val members: List<T>, override val pinned: Boolean) : LibraryEntry<T>

    fun uri(of: (T) -> String) = when (this) { is Item -> of(item); is Folder -> folder.uri }
}

/** [LibraryLayout], kept in SharedPreferences `library` and observable. */
class LibraryLayoutStore(private val prefs: SharedPreferences, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _layout = MutableStateFlow(read())
    val layout: StateFlow<LibraryLayout> = _layout.asStateFlow()

    private fun read(): LibraryLayout =
        runCatching { json.decodeFromString<LibraryLayout>(prefs.getString(KEY, null) ?: "{}") }.getOrDefault(LibraryLayout())

    /** Applies [change] and saves the result if it changed anything. */
    fun update(change: (LibraryLayout) -> LibraryLayout) {
        val next = change(_layout.value)
        if (next == _layout.value) return
        _layout.value = next
        prefs.edit().putString(KEY, json.encodeToString(next)).apply()
    }

    /** Creates a folder with [items] in it and returns its id. */
    fun createFolder(name: String, items: List<String> = emptyList()): String = newId().also { id -> update { it.createFolder(id, name, items) } }

    private companion object { const val KEY = "layout" }
}
