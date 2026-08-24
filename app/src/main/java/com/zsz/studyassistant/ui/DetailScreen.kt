package com.zsz.studyassistant.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(nav: NavHostController, vm: MainViewModel) {
    val q = vm.selectedQuestion
    if (q == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("未选择错题")
        }
        return
    }
    val bitmap = q.imageBytes?.let {
        try { BitmapFactory.decodeByteArray(it, 0, it.size) } catch (e: Exception) { null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("错题详情") },
                navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text("←") } },
                actions = {
                    TextButton(onClick = {
                        vm.deleteFromNotebook(q)
                        nav.popBackStack()
                    }) { Text("删除") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 题目：原图或文字
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "错题原图",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(horizontal = 12.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(q.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("💡 解答", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            // 解答走引用气泡，内部可滚动（复用渲染逻辑）
            ConversationWebView(
                messages = listOf(ChatMsg("assistant", q.answer)),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)
            )
        }
    }
}
