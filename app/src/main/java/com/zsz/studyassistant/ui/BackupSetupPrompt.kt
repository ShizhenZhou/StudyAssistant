package com.zsz.studyassistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.MainViewModel
import com.zsz.studyassistant.data.AutoBackup
import com.zsz.studyassistant.data.BackupFolderAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs after the Key dialog, once per activity ViewModel, including configuration recreation. */
@Composable
internal fun BackupSetupPrompt(vm: MainViewModel, ready: Boolean) {
    val context = LocalContext.current
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var show by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var usable by rememberSaveable { mutableStateOf(false) }
    var enabled by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (!ready || vm.backupSetupChecked) return@LaunchedEffect
        val tree = AutoBackup.treeUri(context)
        val valid = withContext(Dispatchers.IO) { BackupFolderAccess.isUsable(context, tree) }
        if (AutoBackup.treeUri(context) != tree) return@LaunchedEffect
        if (!valid) {
            withContext(Dispatchers.IO) { AutoBackup.setEnabled(context, false) }
            usable = false; enabled = false; show = true
        }
        vm.backupSetupChecked = true
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            busy = true; failed = false
            try {
                usable = withContext(Dispatchers.IO) { BackupFolderAccess.select(context, uri) }
                failed = !usable
                enabled = usable && AutoBackup.isEnabled(context)
                val preferences = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                if (enabled && com.zsz.studyassistant.data.BackupNotifications.needsPermission(context) &&
                    !preferences.getBoolean("backup_failure_permission_asked", false)) {
                    preferences.edit().putBoolean("backup_failure_permission_asked", true).apply()
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            } finally { busy = false }
        }
    }
    var resumeGeneration by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeGeneration++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(show, busy, resumeGeneration) {
        if (!show || busy) return@LaunchedEffect
        val tree = AutoBackup.treeUri(context)
        val valid = withContext(Dispatchers.IO) { BackupFolderAccess.isUsable(context, tree) }
        if (AutoBackup.treeUri(context) == tree) {
            usable = valid
            if (!valid) withContext(Dispatchers.IO) { AutoBackup.setEnabled(context, false) }
            enabled = valid && AutoBackup.isEnabled(context)
        }
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = { if (!busy) show = false },
        title = { Text(s["data.autoBackup.setupTitle"]) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(s["data.autoBackup.setupDesc"], style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            if (usable) {
                val tree = AutoBackup.treeUri(context)
                val id = tree?.let { runCatching { android.provider.DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
                val path = backupFolderLabel(id, null, s["data.autoBackup.internalStorage"])
                Text(s["data.autoBackup.pathLabel"] + path.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { picker.launch(AutoBackup.treeUri(context)) }, enabled = !busy) {
                Text(s[if (usable) "data.autoBackup.change" else "data.autoBackup.pick"])
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s["data.autoBackup.daily"], modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = usable && enabled, enabled = usable && !busy, onCheckedChange = { on ->
                    enabled = on
                    scope.launch { withContext(Dispatchers.IO) { AutoBackup.setEnabled(context, on) } }
                })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) Text(s["data.autoBackup.openFolderFailed"], color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = usable && !busy, onClick = { show = false }) {
            Text(s["data.autoBackup.setupDone"])
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = { show = false }) {
            Text(s["data.autoBackup.setupSkip"])
        } }
    )
}
