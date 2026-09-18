package com.zsz.studyassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.MainViewModel

/**
 * 📊 学习统计：全部数据来自现有表（questions / review / categories），不改数据库结构。
 * 今日 / 本周已复习、连续天数、待复习、掌握度分布、科目分布、近 7 天新增。
 */
@Composable
fun StatsScreen(vm: MainViewModel) {
    val s = LocalStrings.current
    val st by vm.stats.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(s["stats.title"], style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))

        // ── 四张概览卡 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(s["stats.today"], "${st.todayReviewed}", "📖", Modifier.weight(1f))
            StatCard(s["stats.week"], "${st.weekReviewed}", "🗓", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(s["stats.streak"], "${st.streakDays}", "🔥", Modifier.weight(1f))
            StatCard(s["stats.due"], "${st.dueNow}", "⏰", Modifier.weight(1f))
        }

        Spacer(Modifier.height(18.dp))
        // ── 掌握度分布（堆叠条） ──
        SectionTitle(s["stats.mastery"])
        Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.padding(14.dp)) {
                val total = st.mastery.sumOf { it.second }.coerceAtLeast(1)
                val colors = listOf(
                    MaterialTheme.colorScheme.outlineVariant,
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.tertiary
                )
                Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                    val h = size.height
                    val r = CornerRadius(h / 2f)
                    var x = 0f
                    st.mastery.forEachIndexed { i, (_, v) ->
                        val w = size.width * v / total
                        if (w > 0f) {
                            drawRoundRect(
                                color = colors[i % colors.size],
                                topLeft = Offset(x, 0f),
                                size = Size(w, h),
                                cornerRadius = r
                            )
                            x += w
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                st.mastery.forEachIndexed { i, (name, v) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(colors[i % colors.size])
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("$v", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        // ── 近 7 天 / 近 1 个月新增（标题右侧可切换） ──
        var monthMode by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SectionTitle(if (monthMode) s["stats.last30"] else s["stats.last7"])
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (monthMode) s["stats.month"] else s["stats.week"],
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.size(6.dp))
                Switch(
                    checked = monthMode,
                    onCheckedChange = { monthMode = it },
                    modifier = Modifier.scale(0.8f)
                )
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.padding(14.dp)) {
                val series = if (monthMode) st.last30Days else st.last7Days
                val maxV = (series.maxOrNull() ?: 0).coerceAtLeast(1)
                val barColor = MaterialTheme.colorScheme.primary
                val axisColor = MaterialTheme.colorScheme.outlineVariant
                val labelColor = MaterialTheme.colorScheme.outline
                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                    val gutter = 30.dp.toPx()          // 左侧留给纵坐标刻度
                    val plotW = (size.width - gutter).coerceAtLeast(1f)
                    val n = series.size.coerceAtLeast(1)
                    val gap = plotW * 0.04f / n
                    val bw = (plotW - gap * (n - 1)) / n
                    // 纵坐标：0 / 中值 / 最大值 三条刻度线 + 数字（便于读实际数量）
                    val ticks = listOf(0, (maxV + 1) / 2, maxV).distinct()
                    ticks.forEach { t ->
                        val y = size.height - size.height * t / maxV
                        drawLine(
                            color = axisColor,
                            start = Offset(gutter, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f
                        )
                        val layout = measurer.measure(
                            text = androidx.compose.ui.text.AnnotatedString("$t"),
                            style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = labelColor)
                        )
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(
                                (gutter - 6.dp.toPx() - layout.size.width).coerceAtLeast(0f),
                                (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                            )
                        )
                    }
                    // 柱子
                    series.forEachIndexed { i, v ->
                        if (v <= 0) return@forEachIndexed
                        val h = size.height * v / maxV
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(gutter + i * (bw + gap), size.height - h),
                            size = Size(bw, h.coerceAtLeast(3f)),
                            cornerRadius = CornerRadius(3f)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (monthMode) s["stats.daysAgo30"] else s["stats.daysAgo7"],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        s["stats.today"],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        // ── 科目分布 ──
        SectionTitle(s["stats.subjects"] + "（${s.format("stats.total", "n" to "${st.totalQuestions}")}）")
        Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.padding(14.dp)) {
                if (st.subjects.isEmpty()) {
                    Text(
                        s["stats.empty"],
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    val maxV = (st.subjects.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                    st.subjects.take(8).forEach { (name, v) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(0.34f),
                                maxLines = 1
                            )
                            Box(
                                Modifier
                                    .weight(0.56f)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(v.toFloat() / maxV)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                            Text(
                                "  $v",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(0.10f)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StatCard(label: String, value: String, emoji: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp)) {
            Text("$emoji  $label", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
