package com.clashofmusic.app.presentation.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

/** Track taps live on the stable viewport: recycling/moving a row cannot cancel the gesture. */
internal fun Modifier.queueTrackTaps(
    listState: LazyListState,
    trackKeys: Set<String>,
    manualKeys: Set<String> = emptySet(),
    endContentPadding: Dp = 0.dp,
    onClick: (String) -> Unit
): Modifier = composed {
    val currentTracks = rememberUpdatedState(trackKeys)
    val currentManual = rememberUpdatedState(manualKeys)
    val currentClick = rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    val layout = remember { arrayOfNulls<LayoutCoordinates>(1) }
    onGloballyPositioned { layout[0] = it }.pointerInput(listState, endContentPadding) {
        fun windowPosition(position: Offset) = layout[0]?.takeIf { it.isAttached }?.localToWindow(position) ?: position
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val y = down.position.y
            fun distance(offset: Int, height: Int) = when {
                y < offset -> offset - y
                y > offset + height -> y - offset - height
                else -> 0f
            }
            val item = listState.layoutInfo.visibleItemsInfo
                .filter { it.key in currentTracks.value }
                .minByOrNull { distance(it.offset, it.size) } ?: return@awaitEachGesture
            if (distance(item.offset, item.size) > 6.dp.toPx()) return@awaitEachGesture
            val key = item.key as String
            if (key in currentManual.value && down.position.x >= size.width - (60.dp + endContentPadding).toPx()) return@awaitEachGesture
            val origin = windowPosition(down.position)
            down.consume() // Rows retain accessible click semantics; physical taps are handled once here.
            scope.launch { listState.stopScroll() }
            var moved = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((windowPosition(change.position) - origin).getDistance() > viewConfiguration.touchSlop || event.changes.size > 1) moved = true
                if (!change.pressed) {
                    if (!moved && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis && key in currentTracks.value) {
                        change.consume()
                        currentClick.value(key)
                    }
                    break
                }
            } while (true)
        }
    }
}
