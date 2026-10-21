package com.zsz.studyassistant.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 公式渲染的**排版防回归**（2026-10-21 用户反馈「分式里字母的顶部被削掉」）。
 *
 * 两个渲染页（对话页 `conversation_render.html` / 题干等 `latex_render.html`）都用
 * `.katex-display{overflow-x:auto;overflow-y:hidden;…}` 来实现「宽公式在容器里横向滚动」。
 * ⚠️ `overflow:hidden` 的裁剪边界是 **padding 盒**：KaTeX 会把分式 / 上下标画到内容盒外一点点，
 * 若没有上下 `padding`，这点"溢出"的墨迹就被贴着字形裁掉 —— 表现就是分式顶部/底部被削。
 *
 * 这里把两条硬规则钉住，避免以后有人为了"省一行 CSS"又把 padding 删了：
 *   1. 只要规则里还有 `overflow-y:hidden`，同一规则里就必须有 `padding`；
 *   2. `display` 模式**不能**再自己套一层 `.katex-display`（KaTeX 的 `displayMode` 输出里已经有一层）。
 */
class MathRenderAssetTest {

    private val assets = listOf("conversation_render.html", "latex_render.html")

    /** 从当前工作目录向上找 `app/src/main/assets`（Gradle 单测的 CWD 可能是工程根，也可能是 app/） */
    private fun findAsset(name: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(4) {
            val d = dir ?: return@repeat
            for (candidate in listOf("app/src/main/assets", "src/main/assets")) {
                val f = File(d, "$candidate/$name")
                if (f.isFile) return f
            }
            dir = d.parentFile
        }
        return null
    }

    @Test
    fun `渲染页都存在`() {
        for (name in assets) {
            val f = findAsset(name)
            assertTrue(
                "找不到 assets/$name（当前目录：${System.getProperty("user.dir")}）",
                f != null
            )
        }
    }

    @Test
    fun `overflow-y hidden 必须配上下 padding（否则分式上下沿会被裁掉）`() {
        for (name in assets) {
            val text = findAsset(name)?.readText() ?: continue
            // 取 .katex-display{...} 规则体（可能被注释包裹，直接截取到第一个 }）
            val start = text.indexOf(".katex-display{")
            assertTrue("$name 里没有 .katex-display 规则", start >= 0)
            val end = text.indexOf('}', start)
            val rule = text.substring(start, end + 1)
            if (rule.contains("overflow-y:hidden")) {
                assertTrue(
                    "$name 的 .katex-display 有 overflow-y:hidden 却没有 padding —— 会把分式上下沿裁掉：\n$rule",
                    rule.contains("padding")
                )
            }
        }
    }

    @Test
    fun `display 公式不能再套一层 katex-display`() {
        for (name in assets) {
            val text = findAsset(name)?.readText() ?: continue
            val bad = text.lines().filter {
                it.contains("katex.renderToString(") && it.contains("class=\"katex-display\"")
            }
            assertTrue(
                "$name 里把 KaTeX 输出又包了一层 .katex-display（KaTeX displayMode 已经自带）：\n" +
                    bad.joinToString("\n"),
                bad.isEmpty()
            )
        }
    }

    @Test
    fun `两个渲染页都还在用 KaTeX 的 displayMode`() {
        for (name in assets) {
            val text = findAsset(name)?.readText() ?: continue
            assertTrue("$name 里没有 displayMode 渲染调用", text.contains("displayMode:display"))
        }
    }
}
