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
        // ── 近 7 天新增 ──
        SectionTitle(s["stats.last7"])
        Card(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.padding(14.dp)) {
                val maxV = (st.last7Days.maxOrNull() ?: 0).coerceAtLeast(1)
                val barColor = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                    val n = st.last7Days.size.coerceAtLeast(1)
                    val gap = size.width * 0.04f
                    val bw = (size.width - gap * (n - 1)) / n
                    st.last7Days.forEachIndexed { i, v ->
                        val h = size.height * v / maxV
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(i * (bw + gap), size.height - h),
                            size = Size(bw, h.coerceAtLeast(if (v > 0) 4f else 0f)),
                            cornerRadius = CornerRadius(4f)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(s["stats.daysAgo7"], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    Text(s["stats.today"], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
