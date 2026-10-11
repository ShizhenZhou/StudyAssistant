package com.zsz.studyassistant.ui

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp

/**
 * 全局「按钮字号」与「界面字号档位」（C14）。
 *
 * 两条独立的东西，别混在一起：
 *  1. **按钮基准字号**（[BTN_LABEL] 等）：用户反馈"按钮上的字偏大" → 统一比 Material3 默认
 *     （labelLarge = 14sp）**小 2sp**。默认样式的按钮/标签（Button / TextButton / FilterChip）
 *     由 [appTypography] 一次性收窄；少数写死了 fontSize 的地方改用这里的常量。
 *  2. **界面字号档位**（[FontScale]）：设置 → 应用主题 里可选 小/标准/大/特大，
 *     在 MainActivity 用 `LocalDensity` 覆写 `fontScale` 实现 —— **只缩放文字，不动 dp 布局**
 *     （Density(density, fontScale) 里 dp 仍按 density 算），对话区 WebView 同步缩放，
 *     这样"屏幕上的排版"和"导出的长图"一致。
 */

/** 顶栏动作按钮（原 13sp → 12sp） */
internal val BTN_LABEL = 12.sp
/** 更紧凑处的小字（原 12sp → 11sp） */
internal val BTN_LABEL_SMALL = 11.sp
/** 页面级大按钮（原 16sp → 14sp） */
internal val BTN_LABEL_BIG = 14.sp

/**
 * **解题页顶栏**动作按钮（⏸ 中止生成 / 🔄 重新生成 / 📚 存错题本 / 📁 分类 …）：
 * 用户 2026-10-07 要求「默认字号再缩小一些」→ 在 [BTN_LABEL_BIG] 基础上再小 1sp。
 * 同一根顶栏里的按钮都用它，避免出现"有的 14、有的 13"这种不一致。
 */
internal val BTN_LABEL_SOLVE = 13.sp

/**
 * 「📁 分类」按钮的字号档位（用户 2026-10-11 要求）。
 *
 * 背景：这个按钮显示**当前分类名**，而分类名长短差别很大。用户要求：
 *   · 按钮的**整体占位**始终与「⏸ 中止生成 / 🔄 重新生成」按钮一致（宽高都不变）；
 *   · 名字不足 4 个字时**不许**把 emoji 居中/挪位，宁可右边留白（emoji 位置固定）；
 *   · 名字超过 4 个字时逐档**缩小字号**；再长就允许折成两行（见 [categoryLabelMaxLines]）。
 *
 * 返回 sp 数值（不是 TextUnit），这样纯逻辑可以直接单测，不需要 Compose 运行时。
 * @param nameLen 分类名的字符数（**不含** 📁 前缀）
 */
internal fun categoryLabelSp(nameLen: Int): Int = when {
    nameLen <= 4 -> 13      // 与顶栏其它按钮同字号；不足 4 字靠右侧留白补齐占位
    nameLen <= 6 -> 12
    nameLen <= 8 -> 11
    nameLen <= 11 -> 10
    else -> 9
}

/** 分类名很长时允许两行（占位不变；顶栏按钮最小高度 40dp ≫ 两行 10sp，放得下） */
internal fun categoryLabelMaxLines(nameLen: Int): Int = if (nameLen >= 9) 2 else 1

/**
 * **内部页面**（学习/统计/设置三个主页之外的页面）左上角标题的字号：
 * Material3 的 titleLarge 默认 22sp，这里统一 **小 1sp**（用户要求），配合把返回箭头按钮的
 * 内边距收紧，标题会离 ← 更近一点。
 */
internal val SUBPAGE_TITLE = 21.sp

/** 解题页（SolveScreen）的标题：用户要求比其它内部页**再小 2sp** */
internal val SOLVE_TITLE = 19.sp

/** 界面字号档位（C14）：[factor] 直接乘在 Compose 的 fontScale 与 WebView 的 CSS 变量上 */
enum class FontScale(val id: String, val factor: Float) {
    SMALL("small", 0.85f),
    NORMAL("normal", 1.0f),
    LARGE("large", 1.15f),
    XLARGE("xlarge", 1.3f);

    companion object {
        val DEFAULT = NORMAL
        /** 设置页里的展示顺序 */
        val menuOrder: List<FontScale> = listOf(SMALL, NORMAL, LARGE, XLARGE)
        fun fromId(id: String?): FontScale = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** 字号档位的持久化（与主题/语言共用 `settings`） */
object FontScaleStore {
    private const val PREFS = "settings"
    private const val KEY = "font_scale"

    fun load(context: Context): FontScale =
        FontScale.fromId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, scale: FontScale) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, scale.id).apply()
    }
}

/**
 * 应用主题的字体：把默认样式的按钮/标签收窄到 [BTN_LABEL] / [BTN_LABEL_SMALL]。
 * 只改 labelLarge / labelMedium（Button、TextButton、FilterChip、NavigationBar 文字都用这两个），
 * 正文（bodyLarge/bodyMedium）与标题（titleLarge）保持 Material3 默认，避免全篇变小。
 */
internal fun appTypography(base: Typography = Typography()): Typography = base.copy(
    labelLarge = base.labelLarge.copy(fontSize = BTN_LABEL),
    labelMedium = base.labelMedium.copy(fontSize = BTN_LABEL_SMALL)
)
