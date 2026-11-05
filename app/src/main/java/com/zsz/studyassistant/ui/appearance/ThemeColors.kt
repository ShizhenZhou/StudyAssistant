package com.zsz.studyassistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver

/**
 * 主题模式（设置 → 应用主题）→ 当前是否深色。
 *
 * 与 MainActivity 里构造 MaterialTheme 的判定**必须是同一套规则**，
 * 否则「原生界面深色、WebView 正文浅色」会不一致。
 * 供需要把配色传给 WebView / 原生绘制的地方复用。
 */
@Composable
internal fun rememberDarkTheme(mode: String): Boolean = when (mode) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

/**
 * 应用主题配色预设（预设色板：一键切换主色）。
 *
 * 设计取舍：不引入 material-color-utilities（不想多一个依赖），
 * 而是为每套配色手写「主色 / 主色容器」在浅色与深色下的取值，
 * 其余次要色（secondary/tertiary/容器）由主色按比例插值推导，
 * 这样视觉上仍是统一的 Material3 观感，且新增一套配色只需加一行。
 */
enum class AppThemeColor(
    val id: String,
    /** 浅色主题主色 */
    val lightPrimary: Long,
    /** 浅色主题主色容器 */
    val lightContainer: Long,
    /** 深色主题主色 */
    val darkPrimary: Long,
    /** 深色主题主色容器 */
    val darkContainer: Long
) {
    PURPLE("purple", 0xFF6750A4, 0xFFEADDFF, 0xFFD0BCFF, 0xFF4F378B),
    BLUE("blue",     0xFF1565C0, 0xFFD6E3FF, 0xFFA8C7FA, 0xFF004A77),
    TEAL("teal",     0xFF00696D, 0xFFCCE8E9, 0xFF4FD8DC, 0xFF004F52),
    GREEN("green",   0xFF2E6C3A, 0xFFB9F0B8, 0xFF9ED69C, 0xFF14521F),
    ORANGE("orange", 0xFF8B5000, 0xFFFFDDB3, 0xFFFFB951, 0xFF6F3A00),
    RED("red",       0xFF9C4146, 0xFFFFDADA, 0xFFFFB3B4, 0xFF7F2A2F),
    PINK("pink",     0xFF8E4A5F, 0xFFFFD9E2, 0xFFFFB1C8, 0xFF723349),
    INDIGO("indigo", 0xFF4355B9, 0xFFDDE1FF, 0xFFBAC3FF, 0xFF2A3A8F),
    /** 自定义色（具体取值存在 customArgb 里，由用户在取色盘上自选） */
    CUSTOM("custom", 0xFF6750A4, 0xFFEADDFF, 0xFFD0BCFF, 0xFF4F378B);

    /** 色板圆点用的代表色（浅色主色即可，深色下也能看清） */
    val swatch: Color get() = Color(lightPrimary)

    companion object {
        val DEFAULT = PURPLE
        /** 设置页色板里展示的预设（不含 CUSTOM） */
        val presets: List<AppThemeColor> get() = entries.filter { it != CUSTOM }
        fun fromId(id: String?): AppThemeColor =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * 由「深色与否 + 配色预设」生成 Material3 配色方案。
 * 背景轻微随主色着色；自定义亮色也需保证按钮和文本链接的可读性。
 */
fun appColorScheme(dark: Boolean, themeColor: AppThemeColor, customArgb: Int = 0xFF6750A4.toInt(),
    wallpaperShade: WallpaperShade = WallpaperShade.STANDARD): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val custom = Color(customArgb).copy(alpha = 1f)
    val rawPrimary = when {
        themeColor != AppThemeColor.CUSTOM -> Color(if (dark) themeColor.darkPrimary else themeColor.lightPrimary)
        // 自定义色：浅色直接用用户选的色；深色把它提亮一点，保证在深背景上够醒目
        else -> if (dark) lerp(custom, Color.White, 0.35f) ?: custom else custom
    }
    val p = readableAccent(rawPrimary, base.surface, dark, wallpaperShade)
    val pc = when {
        themeColor != AppThemeColor.CUSTOM -> Color(if (dark) themeColor.darkContainer else themeColor.lightContainer)
        // 容器色：浅色向白大量插值（浅底深字），深色向黑大量插值（深底浅字）
        else -> if (dark) lerp(custom, Color.Black, 0.55f) ?: custom else lerp(custom, Color.White, 0.78f) ?: custom
    }
    // 次要/第三色由主色向黑或白插值推导，保持同一色系的层次
    val toward = if (dark) Color.White else Color.Black
    val secondary = lerp(p, toward, 0.25f)
    val tertiary = lerp(p, toward, 0.45f)
    val surface = lerp(base.surface, p, if (dark) .025f else .015f)
    val secondaryContainer = lerp(pc, surface, .45f)
    return base.copy(
        primary = p,
        onPrimary = contrastForeground(p),
        primaryContainer = pc,
        onPrimaryContainer = contrastForeground(pc),
        secondary = secondary,
        onSecondary = contrastForeground(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = contrastForeground(secondaryContainer),
        tertiary = tertiary,
        onTertiary = contrastForeground(tertiary),
        surface = surface,
        background = surface,
        onSurfaceVariant = readableAccent(base.onSurfaceVariant, base.surface, dark, wallpaperShade),
        outline = readableAccent(base.outline, base.surface, dark, wallpaperShade),
        surfaceVariant = lerp(base.surfaceVariant, pc, .12f),
        surfaceContainerLowest = lerp(base.surfaceContainerLowest, p, .012f),
        surfaceContainerLow = lerp(base.surfaceContainerLow, pc, .08f),
        surfaceContainer = lerp(base.surfaceContainer, pc, .10f),
        surfaceContainerHigh = lerp(base.surfaceContainerHigh, pc, .12f),
        surfaceContainerHighest = lerp(base.surfaceContainerHighest, pc, .14f)
    )
}

/** The better of black and white always reaches at least 4.5:1 on an opaque color. */
internal fun contrastForeground(background: Color): Color =
    if (background.luminance() > .179f) Color.Black else Color.White

enum class WallpaperShade(val id: String, val lightAlpha: Float, val darkAlpha: Float) {
    LIGHT("light", .78f, .76f), STANDARD("standard", .88f, .90f), STRONG("strong", .96f, .96f);
    companion object {
        fun fromId(id: String?): WallpaperShade = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

internal fun wallpaperSurface(surface: Color, shade: WallpaperShade = WallpaperShade.STANDARD): Color =
    surface.copy(alpha = if (surface.luminance() < .5f) shade.darkAlpha else shade.lightAlpha)

private fun readableAccent(color: Color, surface: Color, dark: Boolean, shade: WallpaperShade): Color {
    val target = if (dark) Color.White else Color.Black
    // Both ends bound any wallpaper color; leave margin for the subsequent surface tint.
    val backgrounds = listOf(surface, wallpaperSurface(surface, shade).compositeOver(Color.Black),
        wallpaperSurface(surface, shade).compositeOver(Color.White))
    fun contrast(c: Color): Float = backgrounds.minOf { background ->
        (maxOf(c.luminance(), background.luminance()) + .05f) /
            (minOf(c.luminance(), background.luminance()) + .05f)
    }
    if (contrast(color) >= 4.8f) return color
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2
        if (contrast(lerp(color, target, mid)) >= 4.8f) high = mid else low = mid
    }
    return lerp(color, target, high)
}

/** The three root tabs share the same translucent theme tint, including wallpaper mode. */
@Composable
internal fun Modifier.rootPageBackground(): Modifier = background(Brush.verticalGradient(listOf(
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = .20f),
    Color.Transparent,
    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .10f)
)))
