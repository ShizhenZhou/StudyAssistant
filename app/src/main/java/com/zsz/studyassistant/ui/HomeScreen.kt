package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.ApiKeyStore

@Composable
fun HomeScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var keyOk by remember { mutableStateOf(vm.hasApiKey()) }
    var showDialog by remember { mutableStateOf(false) }
    // 首次打开且未配 Key → 自动弹填 Key 对话框
    LaunchedEffect(keyOk) { if (!keyOk) showDialog = true }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Study Assistant", fontSize = 32.sp, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            // 副标题：小一号 + 强制单行（英文 "Photo search · AI solutions · Mistake notebook" 较长）
            Text(
                s["home.subtitle"],
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(48.dp))
            Button(shape = smoothPill(), 
                onClick = { nav.navigate("camera") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(s["home.camera"], fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            // 图文提问：文字/图文直接解答（原在拍照搜题页右上角，现挪到主页）
            Button(shape = smoothPill(), 
                onClick = { vm.startAskSession(); nav.navigate("solve") { popUpTo("home") } },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(s["home.ask"], fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(shape = smoothPill(), 
                onClick = { nav.navigate("notebook") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(s["home.notebook"], fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(shape = smoothPill(), 
                onClick = { nav.navigate("review") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(s["home.review"], fontSize = 20.sp)
            }
        }
    }

    // 填 Key 对话框
    if (showDialog) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(s["home.key.title"]) },
            text = {
                Column {
                    Text(s["home.key.hint"], style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("sk-...") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(shape = smoothPill(), onClick = {
                    if (input.isNotBlank()) {
                        ApiKeyStore.saveKey(context, input.trim())
                        keyOk = true
                        showDialog = false
                    }
                }, enabled = input.isNotBlank()) { Text(s["home.key.save"]) }
            },
            dismissButton = {
                TextButton(shape = smoothPill(), onClick = { showDialog = false }) { Text(s["home.key.later"]) }
            }
        )
    }
}
