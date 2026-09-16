package com.zsz.studyassistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.StudyAssistant
import java.io.File

/** 直接提问：输入文字 + 可选 1~3 张图，直接解答 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var text by remember { mutableStateOf("") }
    var images by remember { mutableStateOf<List<ByteArray>>(emptyList()) }

    // 系统相册（开启**有序选择**）：追加到已有图片，最多 3 张（返回顺序 = 勾选顺序）
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(3)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newOnes = uris.take(3).mapNotNull { uriToCompressedBytes(context, it) }
            images = (images + newOnes).take(3)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s["ask.title"], maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text("←") } },
                actions = {
                    TextButton(onClick = { picker.launch(imagePickRequest(maxItems = 3)) }) { Text(s["ask.addImage"]) }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().imePadding().padding(padding).padding(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                placeholder = { Text(s["ask.placeholder"]) },
                shape = RoundedCornerShape(12.dp)
            )
            // 已选图预览
            if (images.isNotEmpty()) {
                LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images, key = { it.hashCode() }) { img ->
                        val bmp = remember(img) { try { android.graphics.BitmapFactory.decodeByteArray(img, 0, img.size) } catch (e: Exception) { null } }
                        if (bmp != null) {
                            Box(Modifier.size(64.dp)) {
                                Image(bitmap = bmp.asImageBitmap(), contentDescription = s["ask.imageDesc"], modifier = Modifier.size(64.dp), contentScale = ContentScale.Crop)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // 公式快捷输入（∫ ∑ √ 与 LaTeX 模板）
            FormulaBar(onInsert = { text = text + it })
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (text.isNotBlank() || images.isNotEmpty()) {
                        vm.solveDirect(text.trim(), images)
                        nav.navigate("solve") { popUpTo("ask") { inclusive = true } }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = text.isNotBlank() || images.isNotEmpty()
            ) { Text(s["ask.send"]) }
        }
    }
}
