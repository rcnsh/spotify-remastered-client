package app.remastered

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import app.remastered.core.warehouse.WarehouseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Where an outside entry point asks MainActivity to go: the media notification and the widget
 * (Now Playing), and the launcher shortcuts (`res/xml/shortcuts.xml`, plus Wrapped at run time).
 */
enum class Launch(val action: String) {
    NowPlaying(MainActivity.ACTION_OPEN_NOW_PLAYING),
    LikedSongs("app.remastered.OPEN_LIKED_SONGS"),
    Search("app.remastered.OPEN_SEARCH"),
    Wrapped("app.remastered.OPEN_WRAPPED");

    companion object {
        fun of(action: String?): Launch? = entries.firstOrNull { it.action == action }
    }
}

object Shortcuts {
    private const val WRAPPED = "wrapped"

    /**
     * Offers the Wrapped shortcut while the warehouse is usable (configured, token accepted), and
     * takes it away (disabling any copy pinned to the home screen) when it isn't.
     */
    fun follow(context: Context, warehouse: StateFlow<WarehouseState>, scope: CoroutineScope) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        scope.launch {
            warehouse.map { it.isUsable }.distinctUntilChanged().collect { usable ->
                try {
                    if (usable) {
                        manager.enableShortcuts(listOf(WRAPPED))
                        manager.addDynamicShortcuts(listOf(wrapped(context)))
                    } else {
                        manager.removeDynamicShortcuts(listOf(WRAPPED))
                        manager.disableShortcuts(listOf(WRAPPED), "Set up listening history first")
                    }
                } catch (e: RuntimeException) {
                    // Rate-limited (a background app updating too often), or the user is locked.
                    Log.w("Shortcuts", "Can't update shortcuts: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun wrapped(context: Context) = ShortcutInfo.Builder(context, WRAPPED)
        .setShortLabel(context.getString(R.string.shortcut_wrapped))
        .setLongLabel(context.getString(R.string.shortcut_wrapped_long))
        .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_wrapped))
        .setIntent(Intent(context, MainActivity::class.java).setAction(Launch.Wrapped.action))
        .setRank(3)
        .build()
}
