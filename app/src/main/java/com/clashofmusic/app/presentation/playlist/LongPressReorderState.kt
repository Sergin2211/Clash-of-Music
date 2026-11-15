package com.clashofmusic.app.presentation.playlist

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlin.math.abs

/** One detector belongs to the list, so moving a keyed row never cancels its gesture. */
@Stable
class LongPressReorderState(private val list: LazyListState, private val edgePx: Float,
    private val keys: () -> List<String>, private val onStart: () -> Unit,
    private val onMove: (String, String) -> Unit, private val onFinish: (Boolean) -> Unit) {
    var draggedKey by mutableStateOf<String?>(null)
        private set
    private var pointerY by mutableFloatStateOf(0f)
    private var grabOffset = 0f

    private fun moveToPointer() {
        val key = draggedKey ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == key } ?: return
        val center = pointerY - grabOffset + dragged.size / 2f
        val rows = visible.filter { it.key is String && it.key in keys() }
        val target = if (center > dragged.offset + dragged.size / 2f)
            rows.lastOrNull { it.index > dragged.index && center > it.offset + it.size / 2f }
        else rows.firstOrNull { it.index < dragged.index && center < it.offset + it.size / 2f }
        if (target != null) {
            val order = keys()
            val from = order.indexOf(key)
            val to = order.indexOf(target.key)
            val movingDown = center > dragged.offset + dragged.size / 2f
            if (from >= 0 && to >= 0 && (if (movingDown) to > from else to < from))
                onMove(key, target.key as String)
        }
    }

    suspend fun autoScroll() {
        var previousFrame = withFrameNanos { it }
        while (draggedKey != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, .05f)
            previousFrame = frame
            val info = list.layoutInfo
            val top = info.viewportStartOffset.toFloat()
            val bottom = info.viewportEndOffset.toFloat()
            val delta = when {
                pointerY < top + edgePx -> -(top + edgePx - pointerY).coerceAtMost(edgePx) * 10f * seconds
                pointerY > bottom - edgePx -> (pointerY - bottom + edgePx).coerceAtMost(edgePx) * 10f * seconds
                else -> 0f
            }
            if (abs(delta) > .5f) list.scrollBy(delta)
            moveToPointer()
        }
    }

    fun detector(enabled: Boolean): Modifier = if (!enabled) Modifier else Modifier.pointerInput(this) {
        detectDragGesturesAfterLongPress(
            onDragStart = { position ->
                val row = list.layoutInfo.visibleItemsInfo.firstOrNull {
                    position.y >= it.offset && position.y < it.offset + it.size && it.key in keys()
                }
                if (row != null) {
                    pointerY = position.y; grabOffset = position.y - row.offset
                    onStart(); draggedKey = row.key as String
                }
            },
            onDrag = { change, amount ->
                if (draggedKey != null) { change.consume(); pointerY += amount.y; moveToPointer() }
            },
            onDragEnd = { finish(false) },
            onDragCancel = { finish(true) }
        )
    }

    fun finish(cancelled: Boolean) {
        if (draggedKey != null) { draggedKey = null; onFinish(cancelled) }
    }

    fun row(key: String): Modifier = Modifier.zIndex(if (draggedKey == key) 1f else 0f).graphicsLayer {
        if (draggedKey == key) {
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            translationY = if (item != null) pointerY - grabOffset - item.offset else 0f
            shadowElevation = 12f
        } else { translationY = 0f; shadowElevation = 0f }
    }
}
