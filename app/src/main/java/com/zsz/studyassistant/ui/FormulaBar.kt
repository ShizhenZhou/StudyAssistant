package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 可插入的符号（点一下追加到输入框末尾） */
private val FORMULA_SYMBOLS = listOf(
    "∫", "∑", "√", "π", "θ", "α", "β", "μ", "Ω", "±",
    "×", "÷", "≤", "≥", "≠", "≈", "∞", "∂", "Δ", "∇",
    "²", "³", "⁻¹", "₁", "₂", "→"
)

/** LaTeX 模板：显示名 → 插入内容（理工科输入几乎离不开这几个） */
private val FORMULA_TEMPLATES = listOf(
    "x/y" to "\\frac{a}{b}",
    "xⁿ" to "^{2}",
    "xₙ" to "_{n}",
    "√" to "\\sqrt{x}",
    "∫" to "\\int_{a}^{b}",
    "∑" to "\\sum_{n=1}^{\\infty}",
    "lim" to "\\lim_{x \\to 0}",
    "()" to "\\left( \\right)"
)

/**
 * 公式快捷输入条：一行符号 + 一行 LaTeX 模板，点击后把文本追加到输入框。
 * 直接提问与追问共用，避免手打 ∫ ∑ √ 与上下标。
 */
@Composable
fun FormulaBar(onInsert: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s["formula.title"],
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(FORMULA_SYMBOLS) { sym ->
                FormulaChip(label = sym) { onInsert(sym) }
            }
        }
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(FORMULA_TEMPLATES) { (label, latex) ->
                FormulaChip(label = label) { onInsert(latex) }
            }
        }
    }
}

@Composable
private fun FormulaChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
