package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 公式快捷输入条：默认收成一条**窄条**（只显示小字「公式键盘」），
 * 点窄条展开符号行 + LaTeX 模板行，再点收起——不占输入区空间。
 * 直接提问与追问共用，避免手打 ∫ ∑ √ 与上下标。
 */
@Composable
fun FormulaBar(onInsert: (String) -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        // 窄条：整条可点，展开/收起
        Surface(
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (expanded) 0.9f else 0.55f),
            modifier = Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 12.dp)
        ) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (expanded) "▾" else "▸",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    s["formula.title"],
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
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
