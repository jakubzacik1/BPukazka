package app.impulseblocker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.impulseblocker.app
import app.impulseblocker.util.AppCatalog
import app.impulseblocker.util.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppPickerScreen(actions: UiActions) {
    val context = LocalContext.current
    val config by context.app.config.config.collectAsState()
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { AppCatalog.launchableApps(context) }
    }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.navigate(Screen.DASHBOARD) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Choose apps to block", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search apps") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Phone, emergency, settings, launcher and keyboard apps are never shown and cannot be blocked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        val list = apps
        if (list == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val q = query.trim().lowercase()
            val filtered = list.filter {
                it.packageName !in config.blockedApps &&
                    (q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filtered, key = { it.packageName }) { entry ->
                    val checked = entry.packageName in selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { if (checked) selected -= entry.packageName else selected += entry.packageName }
                            .padding(vertical = 6.dp),
                    ) {
                        AppIcon(entry.packageName)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.label)
                            Text(entry.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = checked, onCheckedChange = {
                            if (it) selected += entry.packageName else selected -= entry.packageName
                        })
                    }
                }
            }
        }
        Button(
            onClick = {
                val toAdd = selected.toList()
                actions.updateConfig { c -> c.copy(blockedApps = (c.blockedApps + toAdd).distinct()) }
                actions.navigate(Screen.DASHBOARD)
            },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) { Text(if (selected.isEmpty()) "Select apps" else "Block ${selected.size} app(s)") }
    }
}
