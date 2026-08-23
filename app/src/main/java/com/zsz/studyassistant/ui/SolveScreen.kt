package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolveScreen(nav: NavHostController, vm: MainViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("题目与解答") },
                navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("📝 题目", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = vm.questionText,
                onValueChange = { vm.updateQuestionText(it) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                placeholder = { Text("拍照的题目会自动识别填入，也可以手动输入或修改") }
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.solve() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.busy && vm.questionText.isNotBlank()
            ) { Text(if (vm.busy) "解答中..." else "🤖 AI 解答") }

            vm.error?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text(err, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { vm.clearError() }) { Text("知道了") }
            }

            if (vm.answer.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Text("💡 解答", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        vm.answer,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.saveToNotebook() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📚 加入错题本") }
            }
        }
    }
}
