package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.ChatItem
import com.zsz.studyassistant.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolveScreen(nav: NavHostController, vm: MainViewModel) {
    var followUp by remember { mutableStateOf("") }
    val hasKey = vm.hasApiKey()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("题目与解答") },
                navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text("←") } },
                actions = {
                    TextButton(onClick = { vm.regenerate() }, enabled = vm.chatItems.isNotEmpty() && !vm.busy) {
                        Text("🔄 重新生成")
                    }
                    TextButton(onClick = { vm.saveToNotebook() }, enabled = vm.chatItems.isNotEmpty() && !vm.busy) {
                        Text("📚 存错题本")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!hasKey) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("⚠️ 尚未配置 DeepSeek API Key", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.padding(4.dp))
                        TextButton(onClick = { nav.navigate("settings") }) { Text("去设置") }
                    }
                }
            }

            vm.error?.let { err ->
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(err, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.clearError() }) { Text("知道了") }
                    }
                }
            }

            if (vm.chatItems.isEmpty() && !vm.busy) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "正在等待题目…\n（拍照后会自动出现题目与解答）",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(vm.chatItems, key = { it.id }) { item ->
                        ChatBubble(item)
                    }
                    if (vm.busy) {
                        item(key = "typing") {
                            Text(
                                "正在思考…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }

            // 底部追问输入
            if (vm.chatItems.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // 拍下一题（方形相机按钮）
                    Button(
                        onClick = {
                            vm.startNewQuestion()
                            nav.navigate("camera")
                        },
                        modifier = Modifier.size(56.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        enabled = !vm.busy
                    ) { Text("📷", fontSize = 22.sp) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = followUp,
                        onValueChange = { followUp = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("继续追问…") },
                        maxLines = 3
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            vm.sendFollowUp(followUp.trim())
                            followUp = ""
                        },
                        enabled = followUp.isNotBlank() && !vm.busy
                    ) { Text("发送") }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(item: ChatItem) {
    val isMine = item.role == "question" || item.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Column(Modifier.padding(10.dp)) {
                if (item.role == "assistant") {
                    LatexText(item.content)
                } else {
                    Text(item.content, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
