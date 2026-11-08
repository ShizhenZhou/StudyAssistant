package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared soft-fill icons: 24-unit grid, rounded details and a fine 0.8-unit outer contour. */
internal enum class AppIcon { Camera, Edit, Notebook, Book, History, Chart, Settings, Ai, Bell, Theme, Language, Data, Update, Info, Refresh, Pause, Folder, Gallery, Key, Brain, Crop, Timer, Trash, Backup, Tools, Bug, Shield, List, Warning, Leaf, Cloud, Flame, Plus, Eye, EyeOff, Check, Person, Tag }

/** Decorative icon beside an existing localized label; don't duplicate its accessibility text. */
@Composable
internal fun AppSymbol(icon: AppIcon, modifier: Modifier = Modifier, size: Dp = 24.dp,
                       onPrimary: Boolean = false, backgroundColor: Color? = null) {
    // Follow the app theme, including a manually selected theme that differs from the device.
    val colors = MaterialTheme.colorScheme
    // A dark app theme can have light filled buttons (and custom colors can do the reverse).
    val background = backgroundColor ?: if (onPrimary) colors.primary else colors.surface
    val darkBackdrop = background.luminance() < 0.179f
    val vector = remember(icon, darkBackdrop) {
        val palette = iconPalette(icon, darkBackdrop)
        ImageVector.Builder(name = icon.name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            iconPaths.getValue(icon).forEach { shape ->
                addPath(pathData = shape.nodes,
                    fill = shape.fill?.let { SolidColor(palette[it.ordinal]) },
                    stroke = shape.stroke?.let { SolidColor(palette[it.ordinal]) },
                    strokeLineWidth = shape.width, strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
    }
    Icon(vector, contentDescription = null, tint = Color.Unspecified, modifier = modifier.size(size).alpha(LocalContentColor.current.alpha))
}

private enum class Tone { Deep, Main, Accent, Mint, Gold, Paper, Light, Edge }
private class SymbolPath(data: String, val fill: Tone?, val stroke: Tone? = null, val width: Float = 0f) {
    val nodes by lazy { PathParser().parsePathString(data).toNodes() }
}

/** Stable semantic hues distinguish icons from theme-tinted controls and containers. */
private fun iconPalette(icon: AppIcon, darkBackdrop: Boolean): Array<Color> {
    val (main, accent) = when (icon) {
        AppIcon.Camera, AppIcon.Ai, AppIcon.Info, AppIcon.Refresh, AppIcon.Gallery, AppIcon.Brain, AppIcon.Plus, AppIcon.Eye, AppIcon.Person ->
            if (darkBackdrop) Color(0xFF80BADA) to Color(0xFF4C9FCD)
            else Color(0xFF5F9FC6) to Color(0xFF347CA6)
        AppIcon.Trash, AppIcon.Bug, AppIcon.Flame ->
            if (darkBackdrop) Color(0xFFE7A18D) to Color(0xFFD17A71)
            else Color(0xFFC97E69) to Color(0xFFB15B57)
        AppIcon.Edit, AppIcon.Theme ->
            if (darkBackdrop) Color(0xFFE1A681) to Color(0xFF71B7CA)
            else Color(0xFFD08F67) to Color(0xFF4B9DAD)
        AppIcon.History, AppIcon.Bell, AppIcon.Folder, AppIcon.Key, AppIcon.Timer, AppIcon.Pause, AppIcon.Warning ->
            if (darkBackdrop) Color(0xFFE8BB65) to Color(0xFF78B6CB)
            else Color(0xFFD5A347) to Color(0xFF5D9EB7)
        AppIcon.Settings, AppIcon.Tools, AppIcon.List, AppIcon.EyeOff ->
            if (darkBackdrop) Color(0xFFAEC6D0) to Color(0xFF62ADC4)
            else Color(0xFF859FAA) to Color(0xFF3F899F)
        AppIcon.Book, AppIcon.Notebook, AppIcon.Update, AppIcon.Backup, AppIcon.Shield, AppIcon.Leaf, AppIcon.Check ->
            if (darkBackdrop) Color(0xFF79C3AB) to Color(0xFFA6CC88)
            else Color(0xFF55A08B) to Color(0xFF92B76C)
        AppIcon.Chart, AppIcon.Language, AppIcon.Data, AppIcon.Crop, AppIcon.Cloud, AppIcon.Tag ->
            if (darkBackdrop) Color(0xFF6BBDA9) to Color(0xFF71ADD7)
            else Color(0xFF469D88) to Color(0xFF5898C4)
    }
    // Neutral contours separate the colored silhouette from both purple and custom themes.
    return if (darkBackdrop) arrayOf(
        Color(0xFFD5E2E9), main, accent, Color(0xFF7BBE9E), Color(0xFFE8B556),
        Color(0xFFFAFCFC), Color(0xFF354C59), Color(0xFFC4D4DB)
    ) else arrayOf(
        Color(0xFF405D6E), main, accent, Color(0xFF6AB08E), Color(0xFFE0AC4F),
        Color(0xFFFAFCFC), Color(0xFFE7EFF1), Color(0xFF526D7B)
    )
}

private val iconPaths = mapOf(
    AppIcon.Camera to listOf(
        SymbolPath("M4,5.8 H7 L8.7,3.4 H15.3 L17,5.8 H20 Q22,5.8 22,7.8 V18.5 Q22,20.5 20,20.5 H4 Q2,20.5 2,18.5 V7.8 Q2,5.8 4,5.8 Z", Tone.Main, null, 0f),
        SymbolPath("M2,9 H22 V18.5 Q22,20.5 20,20.5 H4 Q2,20.5 2,18.5 Z", Tone.Accent, null, 0f),
        SymbolPath("M7.5,12.9 A4.5,4.5 0,1 0,16.5 12.9 A4.5,4.5 0,1 0,7.5 12.9 Z", Tone.Paper, Tone.Edge, 0.8f),
        SymbolPath("M8.9,12.9 A3.1,3.1 0,1 0,15.1 12.9 A3.1,3.1 0,1 0,8.9 12.9 Z", Tone.Deep, null, 0f),
        SymbolPath("M9.95,12.9 A2.05,2.05 0,1 0,14.05 12.9 A2.05,2.05 0,1 0,9.95 12.9 Z", Tone.Accent, null, 0f),
        SymbolPath("M10.75,12.1 A0.65,0.65 0,1 0,12.05 12.1 A0.65,0.65 0,1 0,10.75 12.1 Z", Tone.Paper, null, 0f),
        SymbolPath("M18.45,7 H19.65 Q20.1,7 20.1,7.45 V7.45 Q20.1,7.9 19.65,7.9 H18.45 Q18,7.9 18,7.45 V7.45 Q18,7 18.45,7 Z", Tone.Paper, null, 0f),
        SymbolPath("M4,5.8 H7 L8.7,3.4 H15.3 L17,5.8 H20 Q22,5.8 22,7.8 V18.5 Q22,20.5 20,20.5 H4 Q2,20.5 2,18.5 V7.8 Q2,5.8 4,5.8 Z", null, Tone.Edge, 0.8f),
    ),
    AppIcon.Edit to listOf(
        SymbolPath("M5.5,3.5 H16 Q18.5,3.5 18.5,6 V19 Q18.5,21.5 16,21.5 H5.5 Q3,21.5 3,19 V6 Q3,3.5 5.5,3.5 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M6.2,5.5 H15.3 Q16.5,5.5 16.5,6.7 V18.3 Q16.5,19.5 15.3,19.5 H6.2 Q5,19.5 5,18.3 V6.7 Q5,5.5 6.2,5.5 Z", Tone.Light, null, 0f),
        SymbolPath("M7.4,10 H11 M7.4,14 H10.2", null, Tone.Main, 1.2f),
        SymbolPath("M10,14.5 L11,10.7 L18.9,2.8 Q20,1.7 21.1,2.8 L21.8,3.5 Q22.6,4.3 21.8,5.3 L13.9,13.5 Z", Tone.Gold, Tone.Edge, 0.8f),
        SymbolPath("M10,14.5 L11,10.7 L13.9,13.5 Z", Tone.Deep, null, 0f),
        SymbolPath("M18.1,3.6 L21,6.4", null, Tone.Paper, 1.1f),
    ),
    AppIcon.Notebook to listOf(
        SymbolPath("M6.4,2.5 H17.6 Q20,2.5 20,4.9 V19.1 Q20,21.5 17.6,21.5 H6.4 Q4,21.5 4,19.1 V4.9 Q4,2.5 6.4,2.5 Z", Tone.Main, null, 0f),
        SymbolPath("M8,2.5 H17.6 Q20,2.5 20,4.9 V19.1 Q20,21.5 17.6,21.5 H8 Z", Tone.Light, null, 0f),
        SymbolPath("M11,6 H16.5 Q17.5,6 17.5,7 V11.7 Q17.5,12.7 16.5,12.7 H11 Q10,12.7 10,11.7 V7 Q10,6 11,6 Z", Tone.Accent, null, 0f),
        SymbolPath("M11.5,8.3 H16 M11.5,10.5 H14.7", null, Tone.Paper, 1.1f),
        SymbolPath("M2.8,7 H6 M2.8,12 H6 M2.8,17 H6", null, Tone.Deep, 1.4f),
        SymbolPath("M6.4,2.5 H17.6 Q20,2.5 20,4.9 V19.1 Q20,21.5 17.6,21.5 H6.4 Q4,21.5 4,19.1 V4.9 Q4,2.5 6.4,2.5 Z", null, Tone.Edge, 0.8f),
    ),
    AppIcon.Book to listOf(
        SymbolPath("M12,5.4 Q7.5,2.5 2.5,4 V19 Q7.5,17.5 12,20.5 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M12,5.4 Q16.5,2.5 21.5,4 V19 Q16.5,17.5 12,20.5 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M12,5.8 V19.8", null, Tone.Paper, 0.9f),
        SymbolPath("M5.3,8 Q7.2,7.5 9.5,8.5 M5.3,11.7 Q7.2,11.2 9.5,12.2 M14.5,8.5 Q16.8,7.5 18.7,8 M14.5,12.2 Q16.8,11.2 18.7,11.7", null, Tone.Paper, 1.3f),
    ),
    AppIcon.History to listOf(
        SymbolPath("M3.6,12.3 A8.8,8.8 0,1 0,21.2 12.3 A8.8,8.8 0,1 0,3.6 12.3 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M6.2,12.3 A6.2,6.2 0,1 0,18.6 12.3 A6.2,6.2 0,1 0,6.2 12.3 Z", Tone.Light, null, 0f),
        SymbolPath("M12.4,8 V12.5 L15.8,14.4", null, Tone.Deep, 1.4f),
        SymbolPath("M2.2,4.4 L2.2,11.5 Q2.2,12.2 3,12.2 H9.8 Z", Tone.Accent, Tone.Edge, 0.8f),
    ),
    AppIcon.Chart to listOf(
        SymbolPath("M6.6,11 H8.7 Q9.8,11 9.8,12.1 V18.4 Q9.8,19.5 8.7,19.5 H6.6 Q5.5,19.5 5.5,18.4 V12.1 Q5.5,11 6.6,11 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M13.1,4 H15.2 Q16.3,4 16.3,5.1 V18.4 Q16.3,19.5 15.2,19.5 H13.1 Q12,19.5 12,18.4 V5.1 Q12,4 13.1,4 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M2.7,3 Q3.4,3 3.4,3.7 V20.7 H21 Q21.7,20.7 21.7,21.4 Q21.7,22.1 21,22.1 H2.7 Q2,22.1 2,21.4 V3.7 Q2,3 2.7,3 Z", Tone.Deep, null, 0f),
    ),
    AppIcon.Settings to listOf(
        SymbolPath("M9,2.5 H15 L15.5,5 L17,6 L19.5,5.5 L22,10 L20,11.5 V13 L22,14.5 L19.5,19 L17,18.5 L15.5,19.5 L15,22 H9 L8.5,19.5 L7,18.5 L4.5,19 L2,14.5 L4,13 V11.5 L2,10 L4.5,5.5 L7,6 L8.5,5 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M7.6,12.25 A4.4,4.4 0,1 0,16.4 12.25 A4.4,4.4 0,1 0,7.6 12.25 Z", Tone.Light, Tone.Edge, 0.8f),
        SymbolPath("M9.3,12.25 A2.7,2.7 0,1 0,14.7 12.25 A2.7,2.7 0,1 0,9.3 12.25 Z", Tone.Accent, null, 0f),
    ),
    AppIcon.Ai to listOf(
        SymbolPath("M12,2.5 L15,9 L21.5,12 L15,15 L12,21.5 L9,15 L2.5,12 L9,9 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M12,2.5 L15,9 L21.5,12 H12 Z", Tone.Gold, null, 0f),
        SymbolPath("M20,1.3 L21,3.7 L23.4,4.7 L21,5.7 L20,8.1 L19,5.7 L16.6,4.7 L19,3.7 Z", Tone.Accent, Tone.Edge, 0.8f),
    ),
    AppIcon.Bell to listOf(
        SymbolPath("M9.8,19.7 A2.2,2.2 0,1 0,14.2 19.7 A2.2,2.2 0,1 0,9.8 19.7 Z", Tone.Deep, null, 0f),
        SymbolPath("M12,2 H12 Q13.5,2 13.5,3.5 V3.5 Q13.5,5 12,5 H12 Q10.5,5 10.5,3.5 V3.5 Q10.5,2 12,2 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M5,10 Q5,3.5 12,3.5 Q19,3.5 19,10 V14.8 L21.2,17.4 Q22,18.7 20.5,18.7 H3.5 Q2,18.7 2.8,17.4 L5,14.8 Z", Tone.Gold, null, 0f),
        SymbolPath("M4.8,15.5 H19.2 L21.2,17.4 Q22,18.7 20.5,18.7 H3.5 Q2,18.7 2.8,17.4 Z", Tone.Main, null, 0f),
        SymbolPath("M8,10 Q8,7.5 10,6.8", null, Tone.Paper, 1.3f),
        SymbolPath("M5,10 Q5,3.5 12,3.5 Q19,3.5 19,10 V14.8 L21.2,17.4 Q22,18.7 20.5,18.7 H3.5 Q2,18.7 2.8,17.4 L5,14.8 Z", null, Tone.Edge, 0.8f),
    ),
    AppIcon.Theme to listOf(
        SymbolPath("M12,2.8 C6.8,2.8 2.8,6.8 2.8,12 C2.8,17 6.7,21.2 11.7,21.2 H13 Q15.5,21.2 15.5,19 Q15.5,18 14.5,17.2 Q13.7,16.5 14.5,15.8 Q15,15.3 16,15.3 H17 Q21.2,15.3 21.2,11.7 C21.2,6.8 17.2,2.8 12,2.8 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M5.8,10 A1.7,1.7 0,1 0,9.2 10 A1.7,1.7 0,1 0,5.8 10 Z", Tone.Accent, null, 0f),
        SymbolPath("M9,6.6 A1.7,1.7 0,1 0,12.4 6.6 A1.7,1.7 0,1 0,9 6.6 Z", Tone.Gold, null, 0f),
        SymbolPath("M13.8,7.3 A1.7,1.7 0,1 0,17.2 7.3 A1.7,1.7 0,1 0,13.8 7.3 Z", Tone.Mint, null, 0f),
        SymbolPath("M16.6,11.3 A1.4,1.4 0,1 0,19.4 11.3 A1.4,1.4 0,1 0,16.6 11.3 Z", Tone.Light, null, 0f),
        SymbolPath("M6.5,16.3 A1.5,1.5 0,1 0,9.5 16.3 A1.5,1.5 0,1 0,6.5 16.3 Z", Tone.Paper, null, 0f),
    ),
    AppIcon.Language to listOf(
        SymbolPath("M2.8,12 A9.2,9.2 0,1 0,21.2 12 A9.2,9.2 0,1 0,2.8 12 Z", Tone.Mint, Tone.Edge, 0.8f),
        SymbolPath("M12,2.8 C6.4,8 6.4,16 12,21.2 C17.6,16 17.6,8 12,2.8 Z", Tone.Accent, null, 0f),
        SymbolPath("M12,3.2 C6.6,8 6.6,16 12,20.8 C17.4,16 17.4,8 12,3.2 M3.5,12 H20.5", null, Tone.Paper, 1.1f),
    ),
    AppIcon.Data to listOf(
        SymbolPath("M3,6 H21 V18 Q21,22 12,22 Q3,22 3,18 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M3,6 H21 V11.8 Q21,15 12,15 Q3,15 3,11.8 Z", Tone.Mint, null, 0f),
        SymbolPath("M3,6 C3,1 21,1 21,6 C21,11 3,11 3,6 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M4,5.5 C6,3.3 18,3.3 20,5.5 C17,7.5 7,7.5 4,5.5 Z", Tone.Light, null, 0f),
        SymbolPath("M4.5,17 Q12,20 19.5,17", null, Tone.Light, 1f),
    ),
    AppIcon.Update to listOf(
        SymbolPath("M3,15 H7.5 V17.4 H16.5 V15 H21 V20 Q21,22 19,22 H5 Q3,22 3,20 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M10,2.5 H14 V10 H18.3 L12,16.5 L5.7,10 H10 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M11.3,3.5 H11.3 Q11.8,3.5 11.8,4 V8 Q11.8,8.5 11.3,8.5 H11.3 Q10.8,8.5 10.8,8 V4 Q10.8,3.5 11.3,3.5 Z", Tone.Light, null, 0f),
    ),
    AppIcon.Info to listOf(
        SymbolPath("M2.8,12 A9.2,9.2 0,1 0,21.2 12 A9.2,9.2 0,1 0,2.8 12 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M10.75,7 A1.25,1.25 0,1 0,13.25 7 A1.25,1.25 0,1 0,10.75 7 Z", Tone.Paper, null, 0f),
        SymbolPath("M10.5,10.5 H12.5 V17.2 M10,17.2 H14.5", null, Tone.Paper, 1.7f),
    ),
    AppIcon.Refresh to listOf(
        SymbolPath("M19,7 A8,8 0,1 0,20 15", null, Tone.Main, 3f),
        SymbolPath("M15,3 L21,3 L21,9 Z", Tone.Accent, Tone.Edge, 0.8f),
    ),
    AppIcon.Pause to listOf(
        SymbolPath("M5,4 H10 V20 H5 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M14,4 H19 V20 H14 Z", Tone.Accent, Tone.Edge, 0.8f),
    ),
    AppIcon.Folder to listOf(
        SymbolPath("M2,6 Q2,4 4,4 H9 L12,7 H20 Q22,7 22,9 V19 Q22,21 20,21 H4 Q2,21 2,19 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M2,10 H22 V19 Q22,21 20,21 H4 Q2,21 2,19 Z", Tone.Gold, Tone.Edge, 0.8f),
        SymbolPath("M6,14 H12", null, Tone.Paper, 1.3f),
    ),
    AppIcon.Gallery to listOf(
        SymbolPath("M4,3 H20 Q22,3 22,5 V19 Q22,21 20,21 H4 Q2,21 2,19 V5 Q2,3 4,3 Z", Tone.Light, Tone.Edge, 0.8f),
        SymbolPath("M3,17 L8,11 L13,16 L17,12 L21,17 V19 Q21,20 20,20 H4 Q3,20 3,19 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M13,7 A2,2 0,1 0,17 7 A2,2 0,1 0,13 7 Z", Tone.Gold, Tone.Edge, 0.8f),
    ),
    AppIcon.Key to listOf(
        SymbolPath("M3,8 A5,5 0,1 1,12 11 L21,20 L18,22 L15,19 L13,20 L11,18 L12,16 L9,13 A5,5 0,0 1,3 8 Z", Tone.Gold, Tone.Edge, 0.8f),
        SymbolPath("M5,7 A1.8,1.8 0,1 0,8.6 7 A1.8,1.8 0,1 0,5 7 Z", Tone.Paper, Tone.Edge, 0.8f),
    ),
    AppIcon.Brain to listOf(
        SymbolPath("M12,5 C9,-1 4,3 5,7 C0,8 1,13 4,14 C1,19 6,23 10,20 L12,21 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M12,5 C15,-1 20,3 19,7 C24,8 23,13 20,14 C23,19 18,23 14,20 L12,21 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M12,5 V21 M6,7 Q9,7 9,10 M4,14 Q8,13 8,17 M18,7 Q15,7 15,10 M20,14 Q16,13 16,17", null, Tone.Paper, 1.2f),
    ),
    AppIcon.Crop to listOf(
        SymbolPath("M6,2 V16 Q6,18 8,18 H22", null, Tone.Main, 3f),
        SymbolPath("M2,6 H16 Q18,6 18,8 V22", null, Tone.Accent, 3f),
        SymbolPath("M11,12 L21,2", null, Tone.Gold, 1.8f),
    ),
    AppIcon.Timer to listOf(
        SymbolPath("M4,13 A8,8 0,1 0,20 13 A8,8 0,1 0,4 13 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M6.5,13 A5.5,5.5 0,1 0,17.5 13 A5.5,5.5 0,1 0,6.5 13 Z", Tone.Light, Tone.Edge, 0.8f),
        SymbolPath("M12,8 V13 L15,15", null, Tone.Deep, 1.5f),
        SymbolPath("M9,2 H15 M12,2 V5 M19,5 L21,7", null, Tone.Accent, 2f),
    ),
    AppIcon.Trash to listOf(
        SymbolPath("M5,7 H19 L18,20 Q18,22 16,22 H8 Q6,22 6,20 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M3,4 H21 V7 H3 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M9,4 V2 H15 V4 M10,10 V18 M14,10 V18", null, Tone.Paper, 1.3f),
    ),
    AppIcon.Backup to listOf(
        SymbolPath("M4,3 H17 L21,7 V20 Q21,22 19,22 H4 Q2,22 2,20 V5 Q2,3 4,3 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M6,3 H16 V10 H6 Z", Tone.Light, Tone.Edge, 0.8f),
        SymbolPath("M6,15 H18 V22 H6 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M12,18 V11 M9,14 L12,11 L15,14", null, Tone.Paper, 1.6f),
    ),
    AppIcon.Tools to listOf(
        SymbolPath("M4,3 L8,7 L11,4 L8,1 C15,0 17,7 13,11 L22,20 L19,23 L10,14 C4,17 -1,11 2,6 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M7,17 L3,21", null, Tone.Gold, 3f),
    ),
    AppIcon.Bug to listOf(
        SymbolPath("M8,6 Q8,2 12,2 Q16,2 16,6 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M6,8 Q6,6 12,6 Q18,6 18,8 V15 Q18,22 12,22 Q6,22 6,15 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M12,7 V21 M2,8 L6,10 M2,14 H6 M2,21 L7,18 M22,8 L18,10 M22,14 H18 M22,21 L17,18", null, Tone.Deep, 1.2f),
    ),
    AppIcon.Shield to listOf(
        SymbolPath("M12,2 L21,6 V12 Q21,19 12,23 Q3,19 3,12 V6 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M12,3 L20,6 V12 Q20,18 12,21 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M7,12 L11,16 L17,9", null, Tone.Paper, 2f),
    ),
    AppIcon.List to listOf(
        SymbolPath("M5,3 H19 Q21,3 21,5 V20 Q21,22 19,22 H5 Q3,22 3,20 V5 Q3,3 5,3 Z", Tone.Light, Tone.Edge, 0.8f),
        SymbolPath("M8,1 H16 V5 H8 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M7,9 H8 M7,14 H8 M7,19 H8", null, Tone.Gold, 2f),
        SymbolPath("M11,9 H17 M11,14 H17 M11,19 H15", null, Tone.Accent, 1.5f),
    ),
    AppIcon.Warning to listOf(
        SymbolPath("M10,3 Q12,0 14,3 L23,19 Q24,22 21,22 H3 Q0,22 1,19 Z", Tone.Gold, Tone.Edge, 0.8f),
        SymbolPath("M12,8 V14 M12,18 V18.2", null, Tone.Deep, 2f),
    ),
    AppIcon.Leaf to listOf(
        SymbolPath("M3,19 C-1,8 10,2 21,3 C22,15 14,24 3,19 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M2,22 L16,8 M7,17 V11 M11,13 H17", null, Tone.Paper, 1.4f),
    ),
    AppIcon.Cloud to listOf(
        SymbolPath("M12,7 A5,5 0,1 0,22 7 A5,5 0,1 0,12 7 Z", Tone.Gold, Tone.Edge, 0.8f),
        SymbolPath("M5,21 C-1,21 0,13 5,13 C4,5 16,5 17,12 C24,11 25,21 19,21 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M6,17 H16", null, Tone.Paper, 1.3f),
    ),
    AppIcon.Flame to listOf(
        SymbolPath("M12,1 C15,8 23,10 21,17 C19,25 3,24 3,16 C3,12 6,8 8,7 C7,12 12,12 12,1 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M12,10 C16,15 18,18 15,21 C12,24 7,21 8,18 C8,15 11,14 12,10 Z", Tone.Gold, Tone.Edge, 0.8f),
    ),
    AppIcon.Plus to listOf(
        SymbolPath("M9,3 H15 V9 H21 V15 H15 V21 H9 V15 H3 V9 H9 Z", Tone.Main, Tone.Edge, 0.8f),
    ),
    AppIcon.Eye to listOf(
        SymbolPath("M1,12 Q12,-3 23,12 Q12,27 1,12 Z", Tone.Light, Tone.Edge, 0.8f),
        SymbolPath("M7,12 A5,5 0,1 0,17 12 A5,5 0,1 0,7 12 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M10,12 A2,2 0,1 0,14 12 A2,2 0,1 0,10 12 Z", Tone.Deep, Tone.Edge, 0.8f),
    ),
    AppIcon.EyeOff to listOf(
        SymbolPath("M1,12 Q12,-3 23,12 Q12,27 1,12 Z", Tone.Light, Tone.Edge, 0.8f),
        SymbolPath("M7,12 A5,5 0,1 0,17 12 A5,5 0,1 0,7 12 Z", Tone.Accent, Tone.Edge, 0.8f),
        SymbolPath("M3,3 L21,21", null, Tone.Main, 2.3f),
    ),
    AppIcon.Check to listOf(
        SymbolPath("M2,12 A10,10 0,1 0,22 12 A10,10 0,1 0,2 12 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M6,12 L10,16 L18,8", null, Tone.Paper, 2f),
    ),
    AppIcon.Person to listOf(
        SymbolPath("M8,6 A4,4 0,1 0,16 6 A4,4 0,1 0,8 6 Z", Tone.Gold, Tone.Edge, 0.8f),
        SymbolPath("M3,22 V19 Q3,12 12,12 Q21,12 21,19 V22 Z", Tone.Main, Tone.Edge, 0.8f),
    ),
    AppIcon.Tag to listOf(
        SymbolPath("M2,3 H13 L23,13 L13,23 L2,12 Z", Tone.Main, Tone.Edge, 0.8f),
        SymbolPath("M5,7 A2,2 0,1 0,9 7 A2,2 0,1 0,5 7 Z", Tone.Paper, Tone.Edge, 0.8f),
        SymbolPath("M11,12 L17,18", null, Tone.Accent, 1.5f),
    ),
)
