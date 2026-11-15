package com.clashofmusic.app.presentation.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clashofmusic.app.domain.model.ThemeMode

/**
 * Cohesive color palette specification for Clash of Music.
 */
data class ClashColors(
    val primary: Color,
    val primaryDeep: Color,
    val primaryDark: Color,
    val primaryLight: Color,
    val background: Color,
    val backgroundSecondary: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val cardBorder: Color,
    val textPrimary: Color = Color(0xFFF7F7F8),
    val textSecondary: Color,
    val textMuted: Color,
    val mint: Color = Color(0xFFF7F7F8),
    val red: Color = Color(0xFFE86878)
)

/**
 * Elixir keeps its optional violet identity on the shared charcoal surface ladder.
 */
val ElixirClashColors = ClashColors(
    primary = Color(0xFF914BB5),
    primaryDeep = Color(0xFF7B3F9D),
    primaryDark = Color(0xFF5A326E),
    primaryLight = Color(0xFF914BB5),
    background = Color(0xFF100E13),
    backgroundSecondary = Color(0xFF15121A),
    surface = Color(0xFF1C1921),
    surfaceVariant = Color(0xFF27222E),
    cardBorder = Color(0xFF443B4B),
    textPrimary = Color(0xFFF7F7F8),
    textSecondary = Color(0xFFC4C4CA),
    textMuted = Color(0xFF9F9FA8)
)

/**
 * Cherry: the owner's rose brand, neutral charcoal surfaces and readable supporting text.
 * The solid brand is reserved for selection; content uses neutral white and grey.
 */
val CherryClashColors = ClashColors(
    primary = Color(0xFFA64268),
    primaryDeep = Color(0xFF843452),
    primaryDark = Color(0xFF60283D),
    primaryLight = Color(0xFFA64268),
    background = Color(0xFF100E11),
    backgroundSecondary = Color(0xFF151216),
    surface = Color(0xFF1C191E),
    surfaceVariant = Color(0xFF272229),
    cardBorder = Color(0xFF443B45),
    textPrimary = Color(0xFFF7F7F8),
    textSecondary = Color(0xFFC4C4CA),
    textMuted = Color(0xFF9F9FA8)
)

/**
 * Ultra-performance singleton holder for active theme tokens.
 * Pre-allocates BorderStroke and Color instances so that at 120 FPS scrolling
 * ZERO memory allocations take place on every frame.
 */
object ClashThemeTokens {
    private var appliedMode: ThemeMode? = null
    var primary: Color = ElixirClashColors.primary
        private set
    var primaryDeep: Color = ElixirClashColors.primaryDeep
        private set
    var primaryDark: Color = ElixirClashColors.primaryDark
        private set
    var primaryLight: Color = ElixirClashColors.primaryLight
        private set
    var background: Color = ElixirClashColors.background
        private set
    var backgroundSecondary: Color = ElixirClashColors.backgroundSecondary
        private set
    var surface: Color = ElixirClashColors.surface
        private set
    var surfaceVariant: Color = ElixirClashColors.surfaceVariant
        private set
    var cardBorder: Color = ElixirClashColors.cardBorder
        private set
    var textPrimary: Color = ElixirClashColors.textPrimary
        private set
    var textSecondary: Color = ElixirClashColors.textSecondary
        private set
    var textMuted: Color = ElixirClashColors.textMuted
        private set

    // Pre-allocated static borders and surfaces (Zero allocation per frame!)
    var currentSongCardColor: Color = ElixirClashColors.surfaceVariant
        private set
    var currentSongBorder: BorderStroke = BorderStroke(1.dp, ElixirClashColors.primary.copy(alpha = 0.5f))
        private set
    var subtleCardBorder: BorderStroke = BorderStroke(1.dp, ElixirClashColors.cardBorder)
        private set
    var glassmorphicSurfaceColor: Color = ElixirClashColors.surface.copy(alpha = 0.85f)
        private set
    var glassmorphicVariantColor: Color = ElixirClashColors.surfaceVariant.copy(alpha = 0.85f)
        private set

    fun applyTheme(mode: ThemeMode) {
        if (appliedMode == mode) return
        val colors = if (mode == ThemeMode.CHERRY) CherryClashColors else ElixirClashColors
        primary = colors.primary
        primaryDeep = colors.primaryDeep
        primaryDark = colors.primaryDark
        primaryLight = colors.primaryLight
        background = colors.background
        backgroundSecondary = colors.backgroundSecondary
        surface = colors.surface
        surfaceVariant = colors.surfaceVariant
        cardBorder = colors.cardBorder
        textPrimary = colors.textPrimary
        textSecondary = colors.textSecondary
        textMuted = colors.textMuted

        currentSongCardColor = colors.surfaceVariant
        currentSongBorder = BorderStroke(1.dp, colors.primary.copy(alpha = 0.5f))
        subtleCardBorder = BorderStroke(1.dp, colors.cardBorder)
        glassmorphicSurfaceColor = colors.surface.copy(alpha = 0.85f)
        glassmorphicVariantColor = colors.surfaceVariant.copy(alpha = 0.85f)
        appliedMode = mode
    }
}

// Fast inline accessors returning pre-computed values with 0 Composable overhead & 0 allocation
inline val AccentBerry: Color get() = ClashThemeTokens.primary
inline val AccentBerryDeep: Color get() = ClashThemeTokens.primaryDeep
inline val AccentBerryDark: Color get() = ClashThemeTokens.primaryDark
inline val AccentBerryLight: Color get() = ClashThemeTokens.primaryLight

inline val DarkBackground: Color get() = ClashThemeTokens.background
inline val DarkBackgroundSecondary: Color get() = ClashThemeTokens.backgroundSecondary
inline val DarkSurface: Color get() = ClashThemeTokens.surface
inline val DarkSurfaceVariant: Color get() = ClashThemeTokens.surfaceVariant
inline val DarkCardBorder: Color get() = ClashThemeTokens.cardBorder

inline val TextPrimaryDark: Color get() = ClashThemeTokens.textPrimary
inline val TextSecondaryDark: Color get() = ClashThemeTokens.textSecondary
inline val TextMutedDark: Color get() = ClashThemeTokens.textMuted

val AccentMint: Color = Color(0xFFF7F7F8)
val AccentRed: Color = Color(0xFFE86878)

inline val CurrentSongCardColor: Color get() = ClashThemeTokens.currentSongCardColor
inline val CurrentSongBorder: BorderStroke get() = ClashThemeTokens.currentSongBorder
inline val SubtleCardBorder: BorderStroke get() = ClashThemeTokens.subtleCardBorder
inline val GlassmorphicSurfaceColor: Color get() = ClashThemeTokens.glassmorphicSurfaceColor
inline val GlassmorphicVariantColor: Color get() = ClashThemeTokens.glassmorphicVariantColor

// Static Shapes
val CardShape16 = RoundedCornerShape(16.dp)
val CardShape14 = RoundedCornerShape(14.dp)
val CardShape12 = RoundedCornerShape(12.dp)
val ThumbShape8 = RoundedCornerShape(8.dp)
val PlayerSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

// Light Theme Fallback
val LightBackground = Color(0xFFF7F7FA)
val LightSurface = Color(0xFFF7F7F8)
val LightSurfaceVariant = Color(0xFFECEEF2)
val TextPrimaryLight = Color(0xFF1A1A24)
val TextSecondaryLight = Color(0xFF5F6368)
