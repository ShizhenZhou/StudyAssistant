package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.data.UpdateTransport

@Composable
internal fun UpdateMirrorSettings() {
    val context = LocalContext.current
    val strings = updateTransportStrings(LocalUiLang.current)
    var config by remember { mutableStateOf(UpdateTransport.load(context)) }
    var address by rememberSaveable { mutableStateOf(config.baseUrl) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), shape = smoothShape(14.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(strings["title"], Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(strings[if (expanded) "collapse" else "configure"])
                }
                Switch(checked = config.enabled, onCheckedChange = { enabled ->
                    val next = config.copy(enabled = enabled)
                    if (UpdateTransport.save(context, next)) config = next
                }, modifier = Modifier.semantics { contentDescription = strings["title"] })
            }
            if (expanded) {
                Text(strings["disclosure"], style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = address, onValueChange = { address = it; invalid = false },
                    label = { Text(strings["address"]) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(), isError = invalid,
                    supportingText = { Text(strings[if (invalid) "invalid" else "hint"]) })
                TextButton(onClick = {
                    val base = UpdateTransport.normalizedBaseUrl(address)
                    invalid = base == null
                    if (base != null) {
                        val next = config.copy(baseUrl = base)
                        if (UpdateTransport.save(context, next)) { config = next; address = base }
                    }
                }, enabled = address != config.baseUrl, modifier = Modifier.align(Alignment.End)) {
                    Text(strings["save"])
                }
            }
        }
    }
}
