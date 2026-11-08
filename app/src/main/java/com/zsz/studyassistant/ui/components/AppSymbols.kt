package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
internal enum class AppIcon { Camera, Edit, Notebook, Book, History, Chart, Settings, Ai, Bell, Theme, Language, Data, Update, Info }

/** Decorative icon beside an existing localized label; don't duplicate its accessibility text. */
@Composable
internal fun AppSymbol(icon: AppIcon, modifier: Modifier = Modifier, size: Dp = 24.dp,
                       onPrimary: Boolean = false) {
    // Follow the app theme, including a manually selected theme that differs from the device.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val vector = remember(icon, dark, onPrimary) {
        val palette = iconPalette(dark, onPrimary)
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
    Icon(vector, contentDescription = null, tint = Color.Unspecified, modifier = modifier.size(size))
}

private enum class Tone { Deep, Violet, Blue, Mint, Gold, Paper, Light, Edge }
private class SymbolPath(data: String, val fill: Tone?, val stroke: Tone? = null, val width: Float = 0f) {
    val nodes by lazy { PathParser().parsePathString(data).toNodes() }
}

private fun iconPalette(dark: Boolean, onPrimary: Boolean): Array<Color> = when {
    dark && onPrimary -> arrayOf(Color(0xFF725D96), Color(0xFF8D75B3), Color(0xFF81A9C7), Color(0xFF89B6A7), Color(0xFFCBB078), Color(0xFFEDE4FA), Color(0xFFDFD2F0), Color(0xFF705A92))
    onPrimary -> arrayOf(Color(0xFFE5D9F9), Color(0xFFCBBBE9), Color(0xFFB8D4EB), Color(0xFFABD1C4), Color(0xFFE8CC93), Color(0xFFF8F4FF), Color(0xFFE5D9F9), Color(0xFFECE3FA))
    dark -> arrayOf(Color(0xFFC2AFE3), Color(0xFF9F88C4), Color(0xFF89ADCC), Color(0xFF89B6A7), Color(0xFFCBB078), Color(0xFFF1EBFA), Color(0xFF43364F), Color(0xFFB8A3D7))
    else -> arrayOf(Color(0xFF927CBA), Color(0xFFB9A6DC), Color(0xFFA3C5E1), Color(0xFFABD1C4), Color(0xFFE8CC93), Color(0xFFFBFAFF), Color(0xFFEAE3F5), Color(0xFF8A77AA))
}

private val iconPaths = mapOf(
    AppIcon.Camera to listOf(
        SymbolPath("M4,5.8 H7 L8.7,3.4 H15.3 L17,5.8 H20 Q22,5.8 22,7.8 V18.5 Q22,20.5 20,20.5 H4 Q2,20.5 2,18.5 V7.8 Q2,5.8 4,5.8 Z", Tone.Violet, null, 0f),
        SymbolPath("M2,9 H22 V18.5 Q22,20.5 20,20.5 H4 Q2,20.5 2,18.5 Z", Tone.Blue, null, 0f),
        SymbolPath("M7.5,12.9 A4.5,4.5 0,1 0,16.5 12.9 A4.5,4.5 0,1 0,7.5 12.9 Z", Tone.Paper, Tone.Edge, 0.8f),
        SymbolPath("M8.9,12.9 A3.1,3.1 0,1 0,15.1 12.9 A3.1,3.1 0,1 0,8.9 12.9 Z", Tone.Deep, null, 0f),
        SymbolPath("M9.95,12.9 A2.05,2.05 0,1 0,14.05 12.9 A2.05,2.05 0,1 0,9.95 12.9 Z", Tone.Blue, null, 0f),
        SymbolPath("M10.75,12.1 A0.65,0.65 0,1 0,12.05 12.1 A0.65,0.65 0,1 0,10.75 12.1 Z", Tone.Paper, null, 0f),
        SymbolPath("M18.45,7 H19.65 Q20.1,7 20.1,7.45 V7.45 Q20.1,7.9 19.65,7.9 H18.45 Q18,7.9 18,7.45 V7.45 Q18,7 18.45,7 Z", Tone.Paper, null, 0f),
        SymbolPath("M4,5.8 H7 L8.7,3.4 H15.3 L17,5.8 H20 Q22,5.8 22,7.8 V18.5 Q22,20.5 20,20.5 H4 Q2,20.5 2,18.5 V7.8 Q2,5.8 4,5.8 Z", null, Tone.Edge, 0.8f),
    ),
    AppIcon.Edit to listOf(
        SymbolPath("M5.5,3.5 H16 Q18.5,3.5 18.5,6 V19 Q18.5,21.5 16,21.5 H5.5 Q3,21.5 3,19 V6 Q3,3.5 5.5,3.5 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M6.2,5.5 H15.3 Q16.5,5.5 16.5,6.7 V18.3 Q16.5,19.5 15.3,19.5 H6.2 Q5,19.5 5,18.3 V6.7 Q5,5.5 6.2,5.5 Z", Tone.Light, null, 0f),
        SymbolPath("M7.4,10 H11 M7.4,14 H10.2", null, Tone.Violet, 1.2f),
        SymbolPath("M10,14.5 L11,10.7 L18.9,2.8 Q20,1.7 21.1,2.8 L21.8,3.5 Q22.6,4.3 21.8,5.3 L13.9,13.5 Z", Tone.Gold, Tone.Edge, 0.8f),
        SymbolPath("M10,14.5 L11,10.7 L13.9,13.5 Z", Tone.Deep, null, 0f),
        SymbolPath("M18.1,3.6 L21,6.4", null, Tone.Paper, 1.1f),
    ),
    AppIcon.Notebook to listOf(
        SymbolPath("M6.4,2.5 H17.6 Q20,2.5 20,4.9 V19.1 Q20,21.5 17.6,21.5 H6.4 Q4,21.5 4,19.1 V4.9 Q4,2.5 6.4,2.5 Z", Tone.Violet, null, 0f),
        SymbolPath("M8,2.5 H17.6 Q20,2.5 20,4.9 V19.1 Q20,21.5 17.6,21.5 H8 Z", Tone.Light, null, 0f),
        SymbolPath("M11,6 H16.5 Q17.5,6 17.5,7 V11.7 Q17.5,12.7 16.5,12.7 H11 Q10,12.7 10,11.7 V7 Q10,6 11,6 Z", Tone.Blue, null, 0f),
        SymbolPath("M11.5,8.3 H16 M11.5,10.5 H14.7", null, Tone.Paper, 1.1f),
        SymbolPath("M2.8,7 H6 M2.8,12 H6 M2.8,17 H6", null, Tone.Deep, 1.4f),
        SymbolPath("M6.4,2.5 H17.6 Q20,2.5 20,4.9 V19.1 Q20,21.5 17.6,21.5 H6.4 Q4,21.5 4,19.1 V4.9 Q4,2.5 6.4,2.5 Z", null, Tone.Edge, 0.8f),
    ),
    AppIcon.Book to listOf(
        SymbolPath("M12,5.4 Q7.5,2.5 2.5,4 V19 Q7.5,17.5 12,20.5 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M12,5.4 Q16.5,2.5 21.5,4 V19 Q16.5,17.5 12,20.5 Z", Tone.Blue, Tone.Edge, 0.8f),
        SymbolPath("M12,5.8 V19.8", null, Tone.Paper, 0.9f),
        SymbolPath("M5.3,8 Q7.2,7.5 9.5,8.5 M5.3,11.7 Q7.2,11.2 9.5,12.2 M14.5,8.5 Q16.8,7.5 18.7,8 M14.5,12.2 Q16.8,11.2 18.7,11.7", null, Tone.Paper, 1.3f),
    ),
    AppIcon.History to listOf(
        SymbolPath("M3.6,12.3 A8.8,8.8 0,1 0,21.2 12.3 A8.8,8.8 0,1 0,3.6 12.3 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M6.2,12.3 A6.2,6.2 0,1 0,18.6 12.3 A6.2,6.2 0,1 0,6.2 12.3 Z", Tone.Light, null, 0f),
        SymbolPath("M12.4,8 V12.5 L15.8,14.4", null, Tone.Deep, 1.4f),
        SymbolPath("M2.2,4.4 L2.2,11.5 Q2.2,12.2 3,12.2 H9.8 Z", Tone.Blue, Tone.Edge, 0.8f),
    ),
    AppIcon.Chart to listOf(
        SymbolPath("M6.6,11 H8.7 Q9.8,11 9.8,12.1 V18.4 Q9.8,19.5 8.7,19.5 H6.6 Q5.5,19.5 5.5,18.4 V12.1 Q5.5,11 6.6,11 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M13.1,4 H15.2 Q16.3,4 16.3,5.1 V18.4 Q16.3,19.5 15.2,19.5 H13.1 Q12,19.5 12,18.4 V5.1 Q12,4 13.1,4 Z", Tone.Blue, Tone.Edge, 0.8f),
        SymbolPath("M2.7,3 Q3.4,3 3.4,3.7 V20.7 H21 Q21.7,20.7 21.7,21.4 Q21.7,22.1 21,22.1 H2.7 Q2,22.1 2,21.4 V3.7 Q2,3 2.7,3 Z", Tone.Deep, null, 0f),
    ),
    AppIcon.Settings to listOf(
        SymbolPath("M9,2.5 H15 L15.5,5 L17,6 L19.5,5.5 L22,10 L20,11.5 V13 L22,14.5 L19.5,19 L17,18.5 L15.5,19.5 L15,22 H9 L8.5,19.5 L7,18.5 L4.5,19 L2,14.5 L4,13 V11.5 L2,10 L4.5,5.5 L7,6 L8.5,5 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M7.6,12.25 A4.4,4.4 0,1 0,16.4 12.25 A4.4,4.4 0,1 0,7.6 12.25 Z", Tone.Light, Tone.Edge, 0.8f),
        SymbolPath("M9.3,12.25 A2.7,2.7 0,1 0,14.7 12.25 A2.7,2.7 0,1 0,9.3 12.25 Z", Tone.Blue, null, 0f),
    ),
    AppIcon.Ai to listOf(
        SymbolPath("M12,2.5 L15,9 L21.5,12 L15,15 L12,21.5 L9,15 L2.5,12 L9,9 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M12,2.5 L15,9 L21.5,12 H12 Z", Tone.Gold, null, 0f),
        SymbolPath("M20,1.3 L21,3.7 L23.4,4.7 L21,5.7 L20,8.1 L19,5.7 L16.6,4.7 L19,3.7 Z", Tone.Blue, Tone.Edge, 0.8f),
    ),
    AppIcon.Bell to listOf(
        SymbolPath("M9.8,19.7 A2.2,2.2 0,1 0,14.2 19.7 A2.2,2.2 0,1 0,9.8 19.7 Z", Tone.Deep, null, 0f),
        SymbolPath("M12,2 H12 Q13.5,2 13.5,3.5 V3.5 Q13.5,5 12,5 H12 Q10.5,5 10.5,3.5 V3.5 Q10.5,2 12,2 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M5,10 Q5,3.5 12,3.5 Q19,3.5 19,10 V14.8 L21.2,17.4 Q22,18.7 20.5,18.7 H3.5 Q2,18.7 2.8,17.4 L5,14.8 Z", Tone.Gold, null, 0f),
        SymbolPath("M4.8,15.5 H19.2 L21.2,17.4 Q22,18.7 20.5,18.7 H3.5 Q2,18.7 2.8,17.4 Z", Tone.Violet, null, 0f),
        SymbolPath("M8,10 Q8,7.5 10,6.8", null, Tone.Paper, 1.3f),
        SymbolPath("M5,10 Q5,3.5 12,3.5 Q19,3.5 19,10 V14.8 L21.2,17.4 Q22,18.7 20.5,18.7 H3.5 Q2,18.7 2.8,17.4 L5,14.8 Z", null, Tone.Edge, 0.8f),
    ),
    AppIcon.Theme to listOf(
        SymbolPath("M12,2.8 C6.8,2.8 2.8,6.8 2.8,12 C2.8,17 6.7,21.2 11.7,21.2 H13 Q15.5,21.2 15.5,19 Q15.5,18 14.5,17.2 Q13.7,16.5 14.5,15.8 Q15,15.3 16,15.3 H17 Q21.2,15.3 21.2,11.7 C21.2,6.8 17.2,2.8 12,2.8 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M5.8,10 A1.7,1.7 0,1 0,9.2 10 A1.7,1.7 0,1 0,5.8 10 Z", Tone.Blue, null, 0f),
        SymbolPath("M9,6.6 A1.7,1.7 0,1 0,12.4 6.6 A1.7,1.7 0,1 0,9 6.6 Z", Tone.Gold, null, 0f),
        SymbolPath("M13.8,7.3 A1.7,1.7 0,1 0,17.2 7.3 A1.7,1.7 0,1 0,13.8 7.3 Z", Tone.Mint, null, 0f),
        SymbolPath("M16.6,11.3 A1.4,1.4 0,1 0,19.4 11.3 A1.4,1.4 0,1 0,16.6 11.3 Z", Tone.Light, null, 0f),
        SymbolPath("M6.5,16.3 A1.5,1.5 0,1 0,9.5 16.3 A1.5,1.5 0,1 0,6.5 16.3 Z", Tone.Paper, null, 0f),
    ),
    AppIcon.Language to listOf(
        SymbolPath("M2.8,12 A9.2,9.2 0,1 0,21.2 12 A9.2,9.2 0,1 0,2.8 12 Z", Tone.Mint, Tone.Edge, 0.8f),
        SymbolPath("M12,2.8 C6.4,8 6.4,16 12,21.2 C17.6,16 17.6,8 12,2.8 Z", Tone.Blue, null, 0f),
        SymbolPath("M12,3.2 C6.6,8 6.6,16 12,20.8 C17.4,16 17.4,8 12,3.2 M3.5,12 H20.5", null, Tone.Paper, 1.1f),
    ),
    AppIcon.Data to listOf(
        SymbolPath("M3,6 H21 V18 Q21,22 12,22 Q3,22 3,18 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M3,6 H21 V11.8 Q21,15 12,15 Q3,15 3,11.8 Z", Tone.Mint, null, 0f),
        SymbolPath("M3,6 C3,1 21,1 21,6 C21,11 3,11 3,6 Z", Tone.Blue, Tone.Edge, 0.8f),
        SymbolPath("M4,5.5 C6,3.3 18,3.3 20,5.5 C17,7.5 7,7.5 4,5.5 Z", Tone.Light, null, 0f),
        SymbolPath("M4.5,17 Q12,20 19.5,17", null, Tone.Light, 1f),
    ),
    AppIcon.Update to listOf(
        SymbolPath("M3,15 H7.5 V17.4 H16.5 V15 H21 V20 Q21,22 19,22 H5 Q3,22 3,20 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M10,2.5 H14 V10 H18.3 L12,16.5 L5.7,10 H10 Z", Tone.Blue, Tone.Edge, 0.8f),
        SymbolPath("M11.3,3.5 H11.3 Q11.8,3.5 11.8,4 V8 Q11.8,8.5 11.3,8.5 H11.3 Q10.8,8.5 10.8,8 V4 Q10.8,3.5 11.3,3.5 Z", Tone.Light, null, 0f),
    ),
    AppIcon.Info to listOf(
        SymbolPath("M2.8,12 A9.2,9.2 0,1 0,21.2 12 A9.2,9.2 0,1 0,2.8 12 Z", Tone.Violet, Tone.Edge, 0.8f),
        SymbolPath("M10.75,7 A1.25,1.25 0,1 0,13.25 7 A1.25,1.25 0,1 0,10.75 7 Z", Tone.Paper, null, 0f),
        SymbolPath("M10.5,10.5 H12.5 V17.2 M10,17.2 H14.5", null, Tone.Paper, 1.7f),
    ),
)
