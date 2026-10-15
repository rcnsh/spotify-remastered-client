package app.remastered.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.remastered.core.CatalogException
import app.remastered.core.ScreenCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    /** [retryAt] (wall-clock ms) is when Spotify allows requests again, for a rate limit. */
    data class Failed(val message: String, val retryAt: Long? = null) : Load<Nothing> {
        constructor(e: Throwable) : this(
            AppState.describe(e, withWait = false),
            (e as? CatalogException)?.takeIf { it.isRateLimited }?.let { System.currentTimeMillis() + (it.retryAfterMs ?: 60_000) },
        )
    }
}

class Loaded<T>(val state: Load<T>, val retry: () -> Unit) {
    val value: T? get() = (state as? Load.Done)?.value
}

/**
 * Loads the data a screen shows under [key] (for example `"album:<id>"`), going through [cache]:
 * a screen opened again shows what it loaded last time straight away, and only loads again
 * (keeping that on screen) when it's [maxAgeMs] old or [keys] changed since; with a [saver], also
 * after a restart. A failed refresh keeps the old data; a failed first load shows the error.
 * [Loaded.retry] always loads again (while Spotify's rate limit holds, that fails at once
 * without a request).
 */
@Composable
fun <T> load(
    cache: ScreenCache, key: String, vararg keys: Any?, maxAgeMs: Long = 60_000, saver: KSerializer<T>? = null, block: suspend () -> T,
): Loaded<T> {
    val version = keys.toList()
    var attempt by remember(key) { mutableIntStateOf(0) }
    var state by remember(key) {
        @Suppress("UNCHECKED_CAST")
        mutableStateOf<Load<T>>(cache.get(key)?.let { Load.Done(it.value as T) } ?: Load.Loading)
    }
    LaunchedEffect(key, *keys, attempt) {
        // With a [saver], what a previous run saved stands in until it's too old (see ScreenCache).
        if (saver != null && state is Load.Loading) withContext(Dispatchers.IO) { cache.restore(key, saver) }?.let {
            @Suppress("UNCHECKED_CAST")
            state = Load.Done(it.value as T)
        }
        val cached = cache.get(key)
        if (attempt == 0 && cached != null && cache.isFresh(cached, version, maxAgeMs)) return@LaunchedEffect
        try {
            val value = block()
            cache.put(key, value, version)
            if (saver != null) withContext(Dispatchers.IO) { cache.save(key, value, version, saver) }
            state = Load.Done(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state !is Load.Done) state = Load.Failed(e)
        }
    }
    return Loaded(state) { attempt++ }
}

/**
 * Loads [block] when [keys] change, without [ScreenCache]: for data too big to keep around once
 * the screen closes (the Wrapped story's decoded images). [Loaded.retry] runs it again.
 */
@Composable
fun <T> loadUncached(vararg keys: Any?, block: suspend () -> T): Loaded<T> {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys, attempt) {
        state = try {
            Load.Done(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e)
        }
    }
    return Loaded(state) { attempt++ }
}
