package com.zsz.studyassistant.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun HistorySearchControls(query: String, onQuery: (String) -> Unit, period: Int, onPeriod: (Int) -> Unit) {
    val s = LocalStrings.current
    OutlinedTextField(value = query, onValueChange = { if (it.length <= 200) onQuery(it) }, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        placeholder = { Text(s["history.search.hint"]) },
        trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text(s["history.search.clear"]) } })
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0 to "history.search.all", 1 to "history.today", 7 to "history.search.week", 30 to "history.search.month").forEach { (days, key) ->
            FilterChip(selected = period == days, onClick = { onPeriod(days) }, label = { Text(s[key]) })
        }
    }
}
