package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.MainViewModel

@Composable
internal fun AiWebToggle(vm: MainViewModel, explain: Boolean = false) {
    val s = LocalStrings.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = vm.webEnabled, onClick = { vm.updateWebEnabled(!vm.webEnabled) },
                enabled = !vm.busy && !vm.streamInterrupted, label = { Text(s["ai.web.toggle"]) })
            vm.toolProgress?.let { Text(s[it], style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary) }
        }
        if (explain) Text(s["ai.web.description"], style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
