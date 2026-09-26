package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * B7 导出对话框：**先选格式（PNG / PDF），再从底部选保存位置**（用户指定的交互）。
 *
 * 保存动作交给 SAF（`CreateDocument`）→ 调用方拿到 Uri 后做真正的渲染与写盘，
 * 所以这里只负责"选格式 + 触发保存 + 显示进度/结果"。
 */
@Composable
internal fun ExportDialog(
    format: QuestionExporter.Format,
    onFormatChange: (QuestionExporter.Format) -> Unit,
    busy: Boolean,
    message: String?,
    failed: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(s["export.title"]) },
        text = {
            Column {
                Text(s["export.format"], style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = format == QuestionExporter.Format.PNG,
                        onClick = { onFormatChange(QuestionExporter.Format.PNG) },
                        enabled = !busy,
                        label = { Text(s["export.png"]) }
                    )
                    FilterChip(
                        selected = format == QuestionExporter.Format.PDF,
                        onClick = { onFormatChange(QuestionExporter.Format.PDF) },
                        enabled = !busy,
                        label = { Text(s["export.pdf"]) }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(s["export.hint"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Text(s["export.exporting"], style = MaterialTheme.typography.bodySmall)
                }
                message?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            // 底部 = 选保存位置（点了才拉起系统"保存到文件"）
            Button(shape = smoothPill(), enabled = !busy, onClick = onSave) { Text(s["export.save"]) }
        },
        dismissButton = {
            TextButton(shape = smoothPill(), enabled = !busy, onClick = onDismiss) { Text(s["common.cancel"]) }
        }
    )
}
