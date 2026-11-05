package com.zsz.studyassistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorsTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
    }

    @Test fun textRemainsReadableForPresetsAndExtremeCustomColors() {
        val customColors = listOf(0xFFFFFF00, 0xFFFFFFFF, 0xFF000000, 0xFF00FF00,
            0xFF00FFFF, 0xFFFF00FF, 0xFF808080, 0xFF6750A4)
        for (dark in listOf(false, true)) for (preset in AppThemeColor.entries) {
            for (custom in customColors) {
                val c = appColorScheme(dark, preset, custom.toInt())
                val pairs = listOf(c.onPrimary to c.primary, c.primary to c.surface,
                    c.onPrimaryContainer to c.primaryContainer, c.onSecondary to c.secondary,
                    c.onSecondaryContainer to c.secondaryContainer, c.onTertiary to c.tertiary,
                    c.onSurface to c.surface, c.onSurfaceVariant to c.surfaceVariant)
                pairs.forEachIndexed { index, (text, background) ->
                    assertTrue("dark=$dark preset=$preset custom=$custom role=$index contrast=${contrast(text, background)}",
                        contrast(text, background) >= 4.49f)
                }
            }
        }
    }

    @Test fun translucentBackgroundRemainsReadableOverBlackAndWhiteWallpapers() {
        for (dark in listOf(false, true)) for (preset in AppThemeColor.entries) {
            for (custom in listOf(0xFFFFFF00, 0xFFFFFFFF, 0xFF000000, 0xFF808080)) {
                val c = appColorScheme(dark, preset, custom.toInt())
                for (wallpaper in listOf(Color.Black, Color.White)) {
                    val background = c.surface.copy(alpha = if (dark) .90f else .88f).compositeOver(wallpaper)
                    for (text in listOf(c.primary, c.onSurface, c.onSurfaceVariant, c.outline)) {
                        assertTrue("dark=$dark preset=$preset custom=$custom text=$text wallpaper=$wallpaper contrast=${contrast(text, background)}",
                            contrast(text, background) >= 4.49f)
                    }
                }
            }
        }
    }
}
