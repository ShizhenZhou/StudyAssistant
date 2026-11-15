package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import com.zsz.studyassistant.MainViewModel

/** Returns whether the next startup prompt can proceed. A failed acknowledgement remains visible. */
@Composable
internal fun PromptMigrationNotice(vm: MainViewModel, nav: NavHostController, ready: Boolean): Boolean {
    val editor = vm.customPromptEditor
    val s = LocalStrings.current
    LaunchedEffect(editor.openRequested) {
        if (editor.openRequested) AppNav.switchTab(nav, AppNav.SETTINGS)
    }
    if (ready && editor.legacyReviewPending) AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(s["promptRules.migration.title"]) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s["promptRules.migration.message"])
                editor.errorKey?.let { Text(s[it], color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { editor.acknowledgeLegacyReview(openSettings = true) }, enabled = !editor.busy) {
            Text(s["promptRules.migration.edit"])
        } },
        dismissButton = { TextButton(onClick = { editor.acknowledgeLegacyReview(openSettings = false) }, enabled = !editor.busy) {
            Text(s["promptRules.migration.later"])
        } }
    )
    return editor.legacyReviewChecked && !editor.legacyReviewPending && !editor.openRequested
}
