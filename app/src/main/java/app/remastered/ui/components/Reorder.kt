package app.remastered.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.remastered.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-press-and-drag reordering of a LazyColumn's rows. The dragged row follows the finger and
 * swaps places with the row under its middle ([onMove], which reorders the list the column shows);
 * near the top or bottom edge the list scrolls. [onDrop] runs once the finger lifts.
 *
 * Rows opt in with [Modifier.reorderable] (the gesture) and [reorderItem] (drawing and animation).
 * Rows that aren't reorderable (headers) are never swapped with: [canMoveTo] says which keys are.
 */
@Stable
class ReorderState internal constructor(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val topEdgePx: Float,
    private val bottomEdgePx: Float,
    private val maxSpeedPx: Float,
    private val canMoveTo: (Any) -> Boolean,
    private val onStart: () -> Unit,
    private val onMove: (from: Any, to: Any) -> Unit,
    private val onDrop: (Any) -> Unit,
) {
    /** The key of the row being dragged, or null. */
    var dragging by mutableStateOf<Any?>(null)
        private set
    private var delta by mutableFloatStateOf(0f)
    // Where the dragged row was laid out when the drag began, in viewport pixels.
    private var startOffset = 0
    // A swap is in flight until the list lays the dragged row out at this index.
    private var pendingIndex: Int? = null
    private var scroller: Job? = null

    /** How far to draw the dragged row from where the list lays it out. */
    val offset: Float get() = info()?.let { startOffset + delta - it.offset } ?: 0f

    private fun info(key: Any? = dragging): LazyListItemInfo? =
        key?.let { k -> list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == k } }

    internal fun start(key: Any) {
        val item = info(key) ?: return
        dragging = key
        delta = 0f
        startOffset = item.offset
        pendingIndex = null
        onStart()
        scroller = scope.launch {
            while (isActive) {
                val speed = edgeSpeed()
                if (speed != 0f) {
                    list.scrollBy(speed)
                    swap()
                }
                withFrameNanos { }
            }
        }
    }

    internal fun drag(dy: Float) {
        if (dragging == null) return
        delta += dy
        swap()
    }

    internal fun end(key: Any) {
        if (dragging != key) return
        scroller?.cancel()
        scroller = null
        dragging = null
        delta = 0f
        onDrop(key)
    }

    private fun swap() {
        val item = info() ?: return
        pendingIndex?.let { if (item.index != it) return else pendingIndex = null }
        val middle = startOffset + delta + item.size / 2f
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != item.key && middle >= it.offset && middle < it.offset + it.size && canMoveTo(it.key)
        } ?: return
        // Moving the first visible row would otherwise make the list scroll along with it.
        if (item.index == list.firstVisibleItemIndex || target.index == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        pendingIndex = target.index
        onMove(item.key, target.key)
    }

    /** Pixels to scroll this frame: faster the further the row is past an edge. */
    private fun edgeSpeed(): Float {
        val item = info() ?: return 0f
        val top = startOffset + delta
        val bottom = top + item.size
        val layout = list.layoutInfo
        val over = when {
            top < layout.viewportStartOffset + topEdgePx -> top - (layout.viewportStartOffset + topEdgePx)
            bottom > layout.viewportEndOffset - bottomEdgePx -> bottom - (layout.viewportEndOffset - bottomEdgePx)
            else -> return 0f
        }
        return (over / item.size * maxSpeedPx).coerceIn(-maxSpeedPx, maxSpeedPx)
    }
}

/**
 * [topEdge] and [bottomEdge] are how close to the list's edges a dragged row starts it
 * scrolling: allow for anything drawn over the list there (top bar, mini player).
 */
@Composable
fun rememberReorderState(
    list: LazyListState,
    canMoveTo: (Any) -> Boolean,
    onMove: (from: Any, to: Any) -> Unit,
    onDrop: (Any) -> Unit,
    onStart: () -> Unit = {},
    topEdge: Dp = 120.dp,
    bottomEdge: Dp = 200.dp,
): ReorderState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val canMove by rememberUpdatedState(canMoveTo)
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    val start by rememberUpdatedState(onStart)
    return remember(list, density) {
        with(density) {
            ReorderState(list, scope, topEdge.toPx(), bottomEdge.toPx(), 18.dp.toPx(), { canMove(it) }, { start() }, { a, b -> move(a, b) }, { drop(it) })
        }
    }
}

/** Starts dragging the row with [key] on a long press, when [enabled]. */
fun Modifier.reorderable(state: ReorderState, key: Any, enabled: Boolean): Modifier =
    if (!enabled) this else pointerInput(state, key) {
        detectDragGesturesAfterLongPress(
            onDragStart = { state.start(key) },
            onDrag = { change, amount -> change.consume(); state.drag(amount.y) },
            onDragEnd = { state.end(key) },
            // Also called when any row's handler is torn down (a row scrolled out of the list
            // mid-drag), so only the dragged row's own cancel ends the drag.
            onDragCancel = { state.end(key) },
        )
    }

/** Draws the dragged row lifted under the finger; the other rows animate into their new places. */
@Composable
fun LazyItemScope.reorderItem(state: ReorderState, key: Any): Modifier =
    if (state.dragging == key) {
        Modifier.zIndex(1f).graphicsLayer { translationY = state.offset }.shadow(12.dp).background(Palette.raised)
    } else {
        Modifier.animateItem()
    }
