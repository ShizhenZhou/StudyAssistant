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
    var health by rememberSaveable { mutableStateOf(BackupFolderAccess.State.INVALID) }
    val usable = health == BackupFolderAccess.State.AVAILABLE
    var enabled by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var resumeGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(ready, resumeGeneration) {
        if (!ready || vm.backupSetupChecked) return@LaunchedEffect
        val observed = withContext(Dispatchers.IO) { BackupFolderAccess.refresh(context) }
        if (observed == null || !BackupFolderAccess.isCurrent(context, observed)) {
            resumeGeneration++
            return@LaunchedEffect
        }
        health = observed.health.state
        enabled = AutoBackup.isEnabled(context)
        if (health != BackupFolderAccess.State.AVAILABLE) show = true
        vm.backupSetupChecked = true
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            busy = true; failed = false
            try {
                val selected = withContext(Dispatchers.IO) { BackupFolderAccess.select(context, uri) }
                failed = !selected
                // Failed selection leaves the previous directory and its enabled intent intact.
                if (selected) health = BackupFolderAccess.State.AVAILABLE
                enabled = AutoBackup.isEnabled(context)
                val preferences = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
                if (enabled && com.zsz.studyassistant.data.BackupNotifications.needsPermission(context) &&
                    !preferences.getBoolean("backup_failure_permission_asked", false)) {
                    preferences.edit().putBoolean("backup_failure_permission_asked", true).apply()
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            } finally { busy = false }
        }
    }
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
        val observed = withContext(Dispatchers.IO) { BackupFolderAccess.refresh(context, scheduleIfAvailable = true) }
        if (observed != null && BackupFolderAccess.isCurrent(context, observed)) {
            health = observed.health.state
            enabled = AutoBackup.isEnabled(context)
        }
    }
    if (!show) return
    AlertDialog(
        onDismissRequest = { if (!busy) show = false },
        title = { Text(s["data.autoBackup.setupTitle"]) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(s["data.autoBackup.setupDesc"], style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            if (health != BackupFolderAccess.State.AVAILABLE) {
                Text(s[if (health == BackupFolderAccess.State.TEMPORARILY_UNAVAILABLE)
                    "data.autoBackup.temporarilyUnavailable" else "data.autoBackup.invalidFolder"],
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (AutoBackup.treeUri(context) != null) {
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
                Switch(checked = enabled && health != BackupFolderAccess.State.INVALID, enabled = usable && !busy, onCheckedChange = { on ->
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
