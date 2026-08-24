package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel

@Composable
fun HomeScreen(nav: NavHostController, vm: MainViewModel) {
    val keyOk by remember { mutableStateOf(vm.hasApiKey()) }
    Box(Modifier.fillMaxSize()) {
        // 右上角设置入口
        TextButton(
            onClick = { nav.navigate("settings") },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
        ) { Text(if (keyOk) "⚙️" else "⚙️ 填Key") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Study Assistant", fontSize = 32.sp, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("拍照搜题 · AI 解答 · 错题整理", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = { nav.navigate("camera") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text("📷  拍照搜题", fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { nav.navigate("notebook") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text("📚  错题本", fontSize = 20.sp)
            }
        }
    }
}
