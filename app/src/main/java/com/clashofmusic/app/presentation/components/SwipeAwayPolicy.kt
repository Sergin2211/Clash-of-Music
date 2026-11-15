package com.clashofmusic.app.presentation.components

import kotlin.math.abs

internal enum class SwipeAwayDirection(val sign: Float, val vertical: Boolean = false) {
    LEFT(-1f), RIGHT(1f), DOWN(1f, true);

    fun constrain(offset: Float): Float = if (sign > 0f) offset.coerceAtLeast(0f) else offset.coerceAtMost(0f)
}

internal object SwipeAwayPolicy {
    fun direction(dx: Float, dy: Float, allowDown: Boolean): SwipeAwayDirection? = when {
        abs(dx) >= abs(dy) && dx != 0f -> if (dx > 0f) SwipeAwayDirection.RIGHT else SwipeAwayDirection.LEFT
        allowDown && dy > 0f -> SwipeAwayDirection.DOWN
        else -> null
    }

    fun dismiss(direction: SwipeAwayDirection, offset: Float, velocity: Float, extent: Float, density: Float, availableTravel: Float = Float.POSITIVE_INFINITY): Boolean {
        val distance = offset * direction.sign
        val speed = velocity * direction.sign
        val flingSpeed = 1200f * density
        // A deliberate flick back towards the origin cancels even a long drag.
        if (speed <= -flingSpeed) return false
        val usualDistance = maxOf(72f * density, extent * .42f)
        // Bottom notices must be dismissible before the finger reaches the system navigation area.
        val distanceLimit = (if (direction.vertical) minOf(usualDistance, maxOf(16f * density, availableTravel * .55f)) else usualDistance) * .75f
        return distance >= distanceLimit ||
            (distance >= minOf(24f * density, distanceLimit) && speed >= flingSpeed)
    }
}
