package com.clashofmusic.app.presentation.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One locked direction per drag; removal happens only after the content has slid offscreen. */
@Composable
internal fun SwipeAwayBox(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    allowDown: Boolean = false,
    dismissLabel: String = "Cerrar aviso",
    content: @Composable BoxScope.() -> Unit
) {
    val gestureOwner = remember { Any() }
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    var offset by remember { mutableFloatStateOf(0f) }
    var direction by remember { mutableStateOf<SwipeAwayDirection?>(null) }
    var exiting by remember { mutableStateOf(false) }
    var width by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    var leftExit by remember { mutableFloatStateOf(0f) }
    var rightExit by remember { mutableFloatStateOf(0f) }
    var downExit by remember { mutableFloatStateOf(0f) }
    var downTravel by remember { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }

    fun settle(velocity: Float, cancelled: Boolean = false) {
        val axis = direction ?: return
        val remove = !cancelled && SwipeAwayPolicy.dismiss(axis, offset, velocity,
            if (axis.vertical) height else width, density, availableTravel = downTravel)
        val target = if (!remove) 0f else when (axis) {
            SwipeAwayDirection.LEFT -> leftExit
            SwipeAwayDirection.RIGHT -> rightExit
            SwipeAwayDirection.DOWN -> downExit
        }
        exiting = remove
        settling?.cancel()
        settling = scope.launch {
            animate(offset, target, initialVelocity = velocity.coerceIn(-5000f * density, 5000f * density),
                animationSpec = spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = .5f)) { value, _ ->
                offset = axis.constrain(value)
            }
            if (remove) dismiss() else { offset = 0f; direction = null }
        }
    }

    Box(modifier.onGloballyPositioned { coordinates ->
        // Measure the stationary wrapper, never the translated content.
        val position = coordinates.positionInRoot()
        val root = coordinates.findRootCoordinates().size
        width = coordinates.size.width.toFloat()
        height = coordinates.size.height.toFloat()
        val margin = 16f * density
        leftExit = -(position.x + width + margin)
        rightExit = root.width - position.x + margin
        downExit = root.height - position.y + margin
        downTravel = (root.height - position.y - height / 2f).coerceAtLeast(0f)
    }.semantics {
        customActions = listOf(CustomAccessibilityAction(dismissLabel) { if (!exiting) dismiss(); true })
    }.pointerInput(gestureOwner, allowDown, density) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (exiting) {
                do { val event = awaitPointerEvent(); event.changes.forEach { it.consume() } }
                while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            val start = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                val movement = change.position - down.position
                val locked = if (offset != 0f) direction else SwipeAwayPolicy.direction(movement.x, movement.y, allowDown)
                if (locked != null) {
                    settling?.cancel()
                    direction = locked
                    change.consume()
                    offset = locked.constrain(offset + if (locked.vertical) overSlop.y else overSlop.x)
                    tracker.addPosition(change.uptimeMillis, change.position)
                }
            }
            if (start != null) {
                var completed = false
                try {
                    completed = drag(start.id) { change ->
                        val axis = direction!!
                        offset = axis.constrain(offset + if (axis.vertical) change.positionChange().y else change.positionChange().x)
                        tracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                    }
                } finally {
                    // System cancellation can restart pointer input without removing this component.
                    // It must also animate back, rather than leave a partially translated card.
                    currentEvent.changes.firstOrNull { it.id == start.id }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                    val velocity = tracker.calculateVelocity()
                    settle(if (!completed) 0f else if (direction?.vertical == true) velocity.y else velocity.x,
                        cancelled = !completed)
                }
            }
        }
    }) {
        Box(Modifier.graphicsLayer {
            translationX = if (direction?.vertical != true) offset else 0f
            translationY = if (direction?.vertical == true) offset else 0f
        }, content = content)
    }
}
