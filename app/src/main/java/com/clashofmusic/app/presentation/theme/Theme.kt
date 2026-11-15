package com.clashofmusic.app.presentation.theme

import android.app.Activity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.clashofmusic.app.domain.model.ThemeMode

fun getClashColorScheme(colors: ClashColors): ColorScheme {
    return darkColorScheme(
        primary = colors.textPrimary,
        onPrimary = colors.background,
        primaryContainer = colors.primaryDark,
        onPrimaryContainer = colors.textPrimary,
        secondary = colors.textSecondary,
        onSecondary = colors.background,
        secondaryContainer = colors.surfaceVariant,
        onSecondaryContainer = colors.textPrimary,
        background = colors.background,
        onBackground = colors.textPrimary,
        surface = colors.surface,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceVariant,
        surfaceTint = colors.surface,
        onSurfaceVariant = colors.textSecondary,
        outline = colors.cardBorder,
        outlineVariant = colors.cardBorder,
        error = colors.red
    )
}

@Composable
fun ClashOfMusicTheme(
    themeMode: ThemeMode = ThemeMode.CHERRY,
    content: @Composable () -> Unit
) {
    // Apply static theme tokens (Zero allocation, instant fast read during 120 FPS rendering)
    ClashThemeTokens.applyTheme(themeMode)

    val clashColors = if (themeMode == ThemeMode.CHERRY) CherryClashColors else ElixirClashColors

    val colorScheme = remember(themeMode) {
        getClashColorScheme(clashColors)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = clashColors.background.toArgb()
                window.navigationBarColor = clashColors.background.toArgb()
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ClashTypography,
        shapes = ClashShapes,
        content = content
    )
}
