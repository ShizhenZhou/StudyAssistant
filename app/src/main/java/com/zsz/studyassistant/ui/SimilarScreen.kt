package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.zsz.studyassistant.MainViewModel

/** 练同类题：AI 出一道同类题，先只显示题目，可互动；顶部「查看答案」再显示答案 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarScreen(nav: NavHostController, vm: MainViewModel) {
    var revealed by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val question = vm.similarQuestion
    val answer = vm.similarAnswer
    val messages = remember(vm.similarQuestion, vm.similarMessages, revealed) {
        buildList {
            if (vm.similarQuestion != null) add(ChatMsg("assistant", vm.similarQuestion!!))
            for (m in vm.similarMessages) add(ChatMsg(if (m.role == "assistant") "assistant" else "user", m.content))
            if (revealed && vm.similarAnswer != null) add(ChatMsg("assistant", "💡 答案\n\n${vm.similarAnswer}"))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("练同类题") },
                navigationIcon = {
                    TextButton(onClick = { nav.popBackStack() }) { Text("←") }
                },
                actions = {
                    if (answer != null) {
                        TextButton(onClick = { revealed = true }) { Text("👁 查看答案") }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().imePadding().padding(padding)) {
            if (vm.similarBusy && question == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("同类题生成中……", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                ConversationWebView(messages, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp))
            }
            // 底部：追问输入 + 发送
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("和 AI 互动，如：我不会这步…", fontSize = 14.sp) },
                    maxLines = 3,
                    shape = RoundedCornerShape(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.sendSimilar(input); input = "" },
                    enabled = input.isNotBlank() && !vm.similarBusy,
                    shape = RoundedCornerShape(22.dp)
                ) { Text("发送") }
            }
        }
    }
}
