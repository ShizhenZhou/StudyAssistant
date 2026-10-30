package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.ApiKeyStore

/**
 * 首页按钮标签的**居中策略**（用户 2026-10-13 第三版定稿，分长短两种按钮）。
 *
 * 长按钮（拍照搜题 / 图文提问 / 搜题记录）：整串居中后**再整体往左偏半个字**。
 *   （用户三版对比下来定的：v1 整串居中 → emoji 把文字顶右；v2 只让文字居中 → 偏左太多；
 *     v3 = 中间值，整串居中 + 左移 `fontSize / 2`。）
 * 短按钮（错题本 / 复习，半宽格子）：**整串严格居中，不做偏移**（名字短，居中即可）。
 *
 * `Modifier.offset` 只影响绘制、不影响测量，所以按钮内的裁切/换行逻辑不受影响。
 */
@Composable
private fun HomeButtonLabel(label: String, fontSize: TextUnit, shiftHalfCharLeft: Boolean = false) {
    Text(
        label,
        fontSize = fontSize,
        maxLines = 1,
        modifier = if (shiftHalfCharLeft) Modifier.offset(x = (-fontSize.value / 2f).dp) else Modifier
    )
}

@Composable
fun HomeScreen(nav: NavHostController, vm: MainViewModel) {
    val context = LocalContext.current
    val s = LocalStrings.current
    var keyOk by remember { mutableStateOf(vm.hasApiKey()) }
    var showDialog by remember { mutableStateOf(false) }
    // 首次打开且未配 Key → 自动弹填 Key 对话框
    LaunchedEffect(keyOk) { if (!keyOk) showDialog = true }
    BackupSetupPrompt(vm, ready = keyOk && !showDialog)

    Box(Modifier.fillMaxSize().rootPageBackground().statusBarsPadding()) {
        Column(
            modifier = Modifier
                .readablePage(560.dp)
                // ★ 兜底：2×2 后高度已减半，但大字号 + 极小屏（或分屏）下仍可能超高 → 允许滚动，绝不裁掉入口
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Study Assistant", fontSize = 32.sp, style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary)
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
            Spacer(Modifier.height(32.dp))
            // ★ 2026-10-13（用户要求）：五个入口的排布改为
            //   「📷 拍照搜题」独占一行 → 「✏️ 图文提问」独占一行 → 「📚 错题本 / 📖 复习」各半宽一行
            //   → 「🕘 搜题记录」独占一行。
            //   原因：2×2 网格时半宽格子放不下「📷 拍照搜题」，被压成「📷 拍照…」——用户明确不要「…」，
            //   并要求这两个主入口**各占一行**。最外层已有 verticalScroll 兜底，4 行在小屏/大字号下也不会被裁。
            if (vm.draftSummaries.isNotEmpty() || vm.draftIssue) {
                val feedback = LocalAppFeedback.current
                TextButton(onClick = {
                    feedback.draftOrigin=AppNav.Origin.HOME
                    feedback.draftRecoveryRequested=true
                }) { Text(s["draft.title"]) }
            }
            val gap = 12.dp
            Button(shape = smoothPill(),
                onClick = { nav.navigate(AppNav.CAMERA) },
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                HomeButtonLabel(s["home.camera"], 20.sp, shiftHalfCharLeft = true)
            }
            Spacer(Modifier.height(gap))
            // 图文提问：文字/图文直接解答（原在拍照搜题页右上角，现挪到主页）
            Button(shape = smoothPill(),
                onClick = { vm.startAskSession(); AppNav.openSolve(nav, AppNav.Origin.HOME) },
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                HomeButtonLabel(s["home.ask"], 20.sp, shiftHalfCharLeft = true)
            }
            Spacer(Modifier.height(gap))
            // 错题本 / 复习：名字短，半宽放得下；仍留 maxLines=2 兜底 —— **任何情况都不出「…」**
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                AppOutlinedButton(shape = smoothPill(),
                    onClick = { nav.navigate(AppNav.NOTEBOOK) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).height(64.dp)
                ) {
                    HomeButtonLabel(s["home.notebook"], 19.sp)
                }
                AppOutlinedButton(shape = smoothPill(),
                    onClick = { nav.navigate(AppNav.REVIEW) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).height(64.dp)
                ) {
                    HomeButtonLabel(s["home.review"], 19.sp)
                }
            }
            Spacer(Modifier.height(gap))
            // 第 5 个入口：🕘 搜题记录（独占一行、撑满宽度）
            AppOutlinedButton(shape = smoothPill(),
                onClick = { nav.navigate(AppNav.HISTORY) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                HomeButtonLabel(s["home.history"], 20.sp, shiftHalfCharLeft = true)
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
