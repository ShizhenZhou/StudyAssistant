package com.zsz.studyassistant.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

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
 * 只覆盖与主色相关的角色，其余（surface/outline 等中性色）沿用 Material3 默认值，
 * 保证对比度与层级仍然安全。
 */
fun appColorScheme(dark: Boolean, themeColor: AppThemeColor, customArgb: Int = 0xFF6750A4.toInt()): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val custom = Color(customArgb)
    val p = when {
        themeColor != AppThemeColor.CUSTOM -> Color(if (dark) themeColor.darkPrimary else themeColor.lightPrimary)
        // 自定义色：浅色直接用用户选的色；深色把它提亮一点，保证在深背景上够醒目
        else -> if (dark) lerp(custom, Color.White, 0.35f) ?: custom else custom
    }
    val pc = when {
        themeColor != AppThemeColor.CUSTOM -> Color(if (dark) themeColor.darkContainer else themeColor.lightContainer)
        // 容器色：浅色向白大量插值（浅底深字），深色向黑大量插值（深底浅字）
        else -> if (dark) lerp(custom, Color.Black, 0.55f) ?: custom else lerp(custom, Color.White, 0.78f) ?: custom
    }
    val onP = if (dark) Color(0xFF1C1B1F) else Color.White
    val onPC = if (dark) Color.White else lerp(custom, Color.Black, 0.6f) ?: custom
    // 次要/第三色由主色向黑或白插值推导，保持同一色系的层次
    val toward = if (dark) Color.White else Color.Black
    return base.copy(
        primary = p,
        onPrimary = onP,
        primaryContainer = pc,
        onPrimaryContainer = onPC,
        secondary = lerp(p, toward, 0.25f) ?: p,
        onSecondary = onP,
        secondaryContainer = lerp(pc, base.surface, 0.45f) ?: pc,
        tertiary = lerp(p, toward, 0.45f) ?: p,
        onTertiary = onP
    )
}
