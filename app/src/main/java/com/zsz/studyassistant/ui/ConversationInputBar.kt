package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 会话底部输入栏（解题页与同类题页共用）：
 *  已选附图缩略图（可点 ✕ 删除） + 公式快捷输入条 + 图库按钮 + 输入框 + 发送
 * 参数全部由调用方提供，页面只负责保管状态与发送逻辑。
 */
@Composable
fun ConversationInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    images: List<ByteArray>,
    onRemoveImage: (ByteArray) -> Unit,
    onPickImages: () -> Unit,
    onSend: () -> Unit,
    busy: Boolean,
    hint: String,
    sendLabel: String,
    imageDesc: String,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        // 已选 1~3 张附图缩略图（可删除）
        if (images.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                images.forEach { img ->
                    val bmp = remember(img) {
                        try { BitmapFactory.decodeByteArray(img, 0, img.size) } catch (e: Exception) { null }
                    }
                    if (bmp != null) {
                        Box(Modifier.size(60.dp)) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = imageDesc,
                                modifier = Modifier.size(60.dp),
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(22.dp)
                                    .clickable { onRemoveImage(img) }
                                    .background(Color(0xCC000000), CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Text("✕", color = Color.White, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        // 公式快捷输入条（∫ ∑ √ 等）
        FormulaBar(onInsert = { onValueChange(value + it) })
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图库选图（最多 3 张，附在消息里）
            Surface(
                onClick = onPickImages,
                enabled = !busy,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("🖼", fontSize = 18.sp) }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                placeholder = { Text(hint, fontSize = 14.sp, maxLines = 1, softWrap = false) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                maxLines = 3,
                shape = RoundedCornerShape(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = (value.isNotBlank() || images.isNotEmpty()) && !busy,
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) { Text(sendLabel, fontSize = 14.sp) }
        }
    }
}
