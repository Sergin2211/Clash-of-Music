package com.clashofmusic.app.domain.model

enum class ToneTarget(val label: String) {
    RINGTONE("Tono de llamada"), ALARM("Alarma predeterminada")
}

data class ToneSelection(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    fun validate(totalMs: Long): ToneSelection {
        require(totalMs > 0 && startMs >= 0 && endMs <= totalMs && endMs > startMs) { "El fragmento debe estar dentro de la canción." }
        require(durationMs >= minOf(1000L, totalMs)) { "Selecciona al menos un segundo." }
        return this
    }
}

object ToneTime {
    private val time = Regex("[0-9]+(?::[0-9]{1,2}){0,2}(?:[.,][0-9]{1,3})?")
    fun parse(value: String): Long? = runCatching {
        val text = value.trim().replace(',', '.')
        require(time.matches(text))
        val parts = text.split(':')
        val seconds = parts.last().toBigDecimal()
        if (parts.size > 1) require(seconds < 60.toBigDecimal())
        val minutes = if (parts.size > 1) parts[parts.lastIndex - 1].toLong() else 0L
        if (parts.size > 2) require(minutes < 60)
        val hours = if (parts.size == 3) parts.first().toLong() else 0L
        seconds.multiply(1000.toBigDecimal()).add(minutes.toBigDecimal().multiply(60000.toBigDecimal()))
            .add(hours.toBigDecimal().multiply(3600000.toBigDecimal())).longValueExact()
    }.getOrNull()

    fun format(ms: Long): String {
        val value = ms.coerceAtLeast(0)
        val base = "${value / 60000}:${((value / 1000) % 60).toString().padStart(2, '0')}"
        val fraction = (value % 1000).toString().padStart(3, '0').trimEnd('0')
        return if (fraction.isEmpty()) base else "$base.$fraction"
    }

    fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
}
