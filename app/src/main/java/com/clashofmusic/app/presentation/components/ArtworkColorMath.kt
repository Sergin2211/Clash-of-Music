package com.clashofmusic.app.presentation.components

/** Bounded color quantization; neutral highlights do not drown out the artwork's dominant hue. */
internal object ArtworkColorMath {
    fun dominant(pixels: IntArray): Int? {
        val weights = FloatArray(4096)
        val counts = IntArray(4096)
        val reds = IntArray(4096)
        val greens = IntArray(4096)
        val blues = IntArray(4096)
        for (pixel in pixels) {
            if (pixel ushr 24 < 128) continue
            val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
            val high = maxOf(r, g, b); val low = minOf(r, g, b)
            if (high < 32 || high > 245 && high - low < 16) continue
            val bin = (r shr 4 shl 8) or (g shr 4 shl 4) or (b shr 4)
            weights[bin] += 1f + (high - low).toFloat() / high * 2f
            counts[bin]++; reds[bin] += r; greens[bin] += g; blues[bin] += b
        }
        val best = weights.indices.maxByOrNull { weights[it] } ?: return null
        val count = counts[best]
        if (count == 0) return null
        return (0xff shl 24) or (reds[best] / count shl 16) or (greens[best] / count shl 8) or (blues[best] / count)
    }

    fun backdrop(dominant: Int, base: Int): Int {
        fun mixed(weight: Float): Int {
            fun channel(shift: Int) = (((base shr shift and 255) * (1f - weight)) + ((dominant shr shift and 255) * weight)).toInt()
            return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = (color shr shift and 255) / 255.0
                return if (value <= .04045) value / 12.92 else Math.pow((value + .055) / 1.055, 2.4)
            }
            return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        }
        // Stronger artwork tint, reduced only when needed to retain readable white text.
        val maxLuminance = (luminance(0xfff7f7f8.toInt()) + .05) / 4.5 - .05
        var weight = .58f
        while (weight > 0f && luminance(mixed(weight)) > maxLuminance) weight = (weight - .01f).coerceAtLeast(0f)
        return mixed(weight)
    }
}
