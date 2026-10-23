package app.remastered.core

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryLayoutTest {
    private fun pl(n: Int) = "spotify:playlist:p$n"
    private fun al(n: Int) = "spotify:album:a$n"
    private val library = listOf(pl(1), pl(2), pl(3), al(1), al(2))

    private fun LibraryLayout.top(items: List<String> = library, byName: Boolean = false) =
        arrange(items, { it }, byName).map { e ->
            when (e) {
                is LibraryEntry.Item -> (if (e.pinned) "*" else "") + e.item
                is LibraryEntry.Folder -> (if (e.pinned) "*" else "") + "[${e.folder.name}: ${e.members.joinToString()}]"
            }
        }

    @Test fun noLayoutKeepsTheGivenOrder() {
        assertEquals(library, LibraryLayout().top())
    }

    @Test fun pinsComeFirstNewestFirst() {
        val l = LibraryLayout().pin(al(1)).pin(pl(3))
        assertEquals(listOf("*" + pl(3), "*" + al(1), pl(1), pl(2), al(2)), l.top())
        assertEquals(listOf("*" + al(1), pl(1), pl(2), pl(3), al(2)), l.unpin(pl(3)).top())
    }

    @Test fun pinningAgainMovesToTheTop() {
        val l = LibraryLayout().pin(pl(1)).pin(pl(2)).pin(pl(1))
        assertEquals(listOf(pl(1), pl(2)), l.pins)
    }

    @Test fun foldersHoldTheirItemsAndComeAfterPins() {
        val l = LibraryLayout().createFolder("f1", "Mix", listOf(pl(2), al(2))).pin(pl(3))
        assertEquals(listOf("*" + pl(3), "[Mix: ${al(2)}, ${pl(2)}]", pl(1), al(1)), l.top())
    }

    @Test fun foldersCanBePinnedAndSortedByName() {
        val l = LibraryLayout().createFolder("f1", "Zed").createFolder("f2", "Alpha")
        assertEquals(listOf("[Alpha: ]", "[Zed: ]"), l.top(emptyList()))
        assertEquals(listOf("[Alpha: ]", "[Zed: ]"), l.top(emptyList(), byName = true))
        assertEquals(listOf("*[Zed: ]", "[Alpha: ]"), l.pin(folderUri("f1")).top(emptyList(), byName = true))
    }

    @Test fun anItemIsInOneFolderAtATime() {
        val l = LibraryLayout().createFolder("a", "A").createFolder("b", "B").moveTo(pl(1), "a").moveTo(pl(1), "b")
        assertEquals(emptyList<String>(), l.folder("a")!!.items)
        assertEquals(listOf(pl(1)), l.folder("b")!!.items)
        assertEquals("b", l.folderOf(pl(1))?.id)
        assertNull(l.moveTo(pl(1), null).folderOf(pl(1)))
    }

    @Test fun movingToAnUnknownFolderDoesNothing() {
        val l = LibraryLayout().createFolder("a", "A", listOf(pl(1)))
        assertSame(l, l.moveTo(pl(1), "missing"))
    }

    @Test fun renameTrimsAndDeleteReleasesItemsAndPin() {
        val l = LibraryLayout().createFolder("a", "A", listOf(pl(1))).pin(folderUri("a")).renameFolder("a", "  Road trip ")
        assertEquals("Road trip", l.folder("a")!!.name)
        val deleted = l.deleteFolder("a")
        assertEquals(emptyList<LibraryFolder>(), deleted.folders)
        assertEquals(emptyList<String>(), deleted.pins)
        assertEquals(library, deleted.top())
    }

    @Test fun missingItemsAreSkippedWhenShown() {
        val l = LibraryLayout().pin(pl(9)).createFolder("a", "A", listOf(pl(8), pl(1)))
        assertEquals(listOf("[A: ${pl(1)}]", pl(2), pl(3), al(1), al(2)), l.top())
    }

    @Test fun folderContentsArePinnedFirstInTheGivenOrder() {
        val l = LibraryLayout().createFolder("a", "A", listOf(pl(1), pl(2), pl(3))).pin(pl(3))
        assertEquals(listOf(pl(3) to true, pl(1) to false, pl(2) to false), l.contents("a", library) { it }.map { it.item to it.pinned })
        assertEquals(emptyList<LibraryEntry.Item<String>>(), l.contents("missing", library) { it })
    }

    @Test fun pruneDropsGoneItemsOfKindsThatLoaded() {
        val l = LibraryLayout().pin(pl(9)).pin(al(9)).pin(folderUri("a"))
            .createFolder("a", "A", listOf(pl(1), pl(9), al(9)))
        // Albums didn't load at all: keep album uris, they may well still be saved.
        val pruned = l.prune(setOf(pl(1), pl(2)))
        assertEquals(listOf(folderUri("a"), al(9)), pruned.pins)
        assertEquals(listOf(al(9), pl(1)), pruned.folder("a")!!.items)
        // With albums loaded, the gone one goes too.
        assertEquals(listOf(pl(1)), pruned.prune(setOf(pl(1), al(1))).folder("a")!!.items)
    }

    @Test fun pruneKeepsPinsOfExistingFoldersOnly() {
        val l = LibraryLayout(pins = listOf(folderUri("gone"))).createFolder("a", "A").pin(folderUri("a"))
        assertEquals(listOf(folderUri("a")), l.prune(setOf(pl(1))).pins)
    }

    @Test fun pruneWithNothingToDropIsTheSameLayout() {
        val l = LibraryLayout().pin(pl(1)).createFolder("a", "A", listOf(pl(2)))
        assertSame(l, l.prune(library.toSet()))
    }

    @Test fun storeSurvivesARestartAndToleratesBadData() {
        val prefs = MemoryPrefs()
        var n = 0
        val store = LibraryLayoutStore(prefs.prefs) { "id${n++}" }
        val id = store.createFolder("Mix", listOf(pl(1)))
        store.update { it.pin(id.let(::folderUri)) }
        val again = LibraryLayoutStore(prefs.prefs).layout.value
        assertEquals(listOf(LibraryFolder("id0", "Mix", listOf(pl(1)))), again.folders)
        assertEquals(listOf(folderUri("id0")), again.pins)

        prefs.values["layout"] = "not json"
        assertEquals(LibraryLayout(), LibraryLayoutStore(prefs.prefs).layout.value)
    }

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
                    else -> error("unused: $name")
                }
            }
            return e
        }

        private inline fun <reified T> proxy(crossinline answer: (String, Array<Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args -> answer(m.name, args ?: emptyArray()) } as T
    }
}
