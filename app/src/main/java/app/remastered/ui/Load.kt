package app.remastered.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class Loaded<T>(val state: Load<T>, val retry: () -> Unit) {
    val value: T? get() = (state as? Load.Done)?.value
}

/** Loads [block] when [keys] change; [Loaded.retry] runs it again. Keeps the last value while reloading. */
@Composable
fun <T> load(vararg keys: Any?, block: suspend () -> T): Loaded<T> {
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(*keys, attempt) {
        state = try {
            Load.Done(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(AppState.describe(e))
        }
    }
    return Loaded(state) { attempt++ }
}
