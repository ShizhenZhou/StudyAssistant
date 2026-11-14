package com.zsz.studyassistant.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The compact disclosure keeps all optional solve actions beside the editor. */
@Composable
internal fun MoreFunctionsBar(
    webEnabled: Boolean,
    onToggleWeb: () -> Unit,
    onPickDocument: () -> Unit,
    onExport: () -> Unit,
    onInsert: (String) -> Unit,
    canToggleWeb: Boolean,
    canPickDocument: Boolean,
    canExport: Boolean,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    var formulaOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = !expanded },
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (expanded) 0.9f else 0.55f),
            modifier = Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 12.dp)
        ) {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "▾" else "▸", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(4.dp))
                Text(s["tools.more"], fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
        if (expanded) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(selected = webEnabled, onClick = onToggleWeb, enabled = canToggleWeb,
                    label = { Text("🌐 " + s["tools.web"], fontSize = 12.sp) })
                FilterChip(selected = false, onClick = onPickDocument, enabled = canPickDocument,
                    label = { Text("📎 " + s["tools.file"], fontSize = 12.sp) })
                FilterChip(selected = false, onClick = onExport, enabled = canExport,
                    label = { Text("📤 " + s["tools.export"], fontSize = 12.sp) })
                FilterChip(selected = formulaOpen, onClick = { formulaOpen = !formulaOpen },
                    label = { Text("⌨️ " + s["tools.formula"], fontSize = 12.sp) })
            }
            if (formulaOpen) FormulaKeyboard(onInsert)
        }
    }
}
