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

/** 顶栏「📁 分类」按钮的基准字号（= 同栏其它按钮），emoji 永远用它、**不随分类名缩放** */
internal const val BASE_CATEGORY_SP = 13

/** 分类名不超过这个字数（汉字数）就用基准字号单行显示 */
internal const val CATEGORY_FULL_CHARS = 5

/** 超过 [CATEGORY_FULL_CHARS] 后逐档缩小的候选字号（**不含**基准 13） */
internal val CATEGORY_SHRINK_SP = listOf(12, 11, 10, 9)

/**
 * 「📁 分类」按钮的**排版决策**（用户 2026-10-12 修订）。
 *
 * 背景：这个按钮显示**当前分类名**，而分类名长短差别很大。用户要求：
 *   · 按钮占位固定：宽 = 「📁 + [CATEGORY_FULL_CHARS] 个汉字」，高 = 「⏸ 中止 / 🔄 重新生成」的实测高；
 *   · **emoji 的字号任何时候都不变**（永远 [BASE_CATEGORY_SP]），只有分类名缩放；
 *   · ≤ [CATEGORY_FULL_CHARS] 字用基准字号**单行**；
 *   · 多于 [CATEGORY_FULL_CHARS] 字**不许出现「…」**：先在**单行**里逐档调小字号（用户要的是"字变小"而不是"换行"），
 *     连最小字号单行都放不下才允许**第二行**（占位不变，顶栏高度足够）。
 *
 * 这里只做纯逻辑决策：能否放下由调用方以 [fits] 注入（Compose 侧用 TextMeasurer 实测，单测用假实现），
 * 因此本函数不依赖 Compose 运行时，可直接单测。
 *
 * @param nameLen 分类名的字符数（**不含** 📁 前缀）
 * @param fits (字号 sp, 行数) -> 这个组合能否**完整**放下（不截断）
 * @return (字号 sp, 最大行数)
 */
internal fun categoryLabelStyle(nameLen: Int, fits: (sp: Int, maxLines: Int) -> Boolean): Pair<Int, Int> {
    if (nameLen <= CATEGORY_FULL_CHARS) return BASE_CATEGORY_SP to 1
    for (sp in CATEGORY_SHRINK_SP) if (fits(sp, 1)) return sp to 1
    for (sp in CATEGORY_SHRINK_SP) if (fits(sp, 2)) return sp to 2
    return CATEGORY_SHRINK_SP.last() to 2   // 兜底：最小字号两行（仍可能被裁，但不给「…」）
}

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
