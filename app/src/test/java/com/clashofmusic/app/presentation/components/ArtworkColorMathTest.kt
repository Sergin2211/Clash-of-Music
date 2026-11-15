package com.clashofmusic.app.presentation.components

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class ArtworkColorMathTest {
    @Test fun darkSaturatedArtworkHasAStrongerTintThanThePreviousBlend() {
        val red = ArtworkColorMath.backdrop(0xffed1010.toInt(), 0xff151216.toInt())
        assertTrue((red shr 16 and 255) > 130)
    }
    @Test fun saturatedDominantArtworkSurvivesWhiteAndBlackMargins() {
        val pixels = IntArray(1600) { when { it < 600 -> 0xff245fc0.toInt(); it < 1100 -> -1; else -> 0xff050505.toInt() } }
        val result = ArtworkColorMath.dominant(pixels)!!
        assertTrue((result and 255) > (result shr 16 and 255) * 2)
    }
    @Test fun actualPopulationWinsOverASmallDecorativeAccent() {
        val result = ArtworkColorMath.dominant(IntArray(1600) { if (it < 1500) 0xff347b55.toInt() else 0xffcc2244.toInt() })!!
        assertTrue((result shr 8 and 255) > (result shr 16 and 255))
    }
    @Test fun absentAndTransparentImagesKeepTheExistingDarkFallback() {
        assertNull(ArtworkColorMath.dominant(intArrayOf()))
        assertNull(ArtworkColorMath.dominant(IntArray(1600)))
        assertNull(ArtworkColorMath.dominant(IntArray(1600) { -1 }))
    }
    @Test fun evenBrightArtworkLeavesWhitePlayerTextReadableInBothThemes() {
        fun luminance(argb: Int): Double {
            fun c(shift: Int): Double { val value = (argb shr shift and 255) / 255.0
                return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4) }
            return .2126 * c(16) + .7152 * c(8) + .0722 * c(0)
        }
        for (base in listOf(0xff151216.toInt(), 0xff15121a.toInt())) {
            for (color in listOf(-1, 0xffffff00.toInt(), 0xff00ffff.toInt(), 0xffff00ff.toInt(), 0xff2393ff.toInt())) {
                val background = ArtworkColorMath.backdrop(color, base)
                assertTrue((luminance(0xfff7f7f8.toInt()) + .05) / (luminance(background) + .05) >= 4.5)
            }
        }
    }
}
