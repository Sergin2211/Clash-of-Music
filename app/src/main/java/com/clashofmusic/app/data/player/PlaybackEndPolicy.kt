package com.clashofmusic.app.data.player

enum class SleepTimerMode(val displayName: String, val minutes: Int) {
    OFF("Desactivado", 0),
    MINUTES_15("15 minutos", 15),
    MINUTES_30("30 minutos", 30),
    MINUTES_45("45 minutos", 45),
    MINUTES_60("60 minutos", 60),
    END_OF_TRACK("Fin de la pista actual", -1)
}

/** Retains the sleep-stop latch even after the visible timer resets to Off. */
internal class PlaybackEndPolicy {
    var endOfTrackArmed = false
        private set
    var autoplayBlocked = false
        private set

    fun armEndOfTrack(armed: Boolean) {
        endOfTrackArmed = armed
        if (armed) autoplayBlocked = false
    }

    fun consumeTrackBoundary(): Boolean {
        if (!endOfTrackArmed) return false
        endOfTrackArmed = false
        autoplayBlocked = true
        return true
    }

    fun timedSleep() { autoplayBlocked = true }
    fun userPlayback() { autoplayBlocked = false }
    fun reset() { endOfTrackArmed = false; autoplayBlocked = false }
}
