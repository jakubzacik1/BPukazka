package app.impulseblocker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.impulseblocker.app
import app.impulseblocker.core.Difficulty
import app.impulseblocker.core.UnlockLogic
import app.impulseblocker.core.UnlockSettings
import app.impulseblocker.core.UnlockTarget
import app.impulseblocker.data.BackupManager
import app.impulseblocker.data.ConfigRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(actions: UiActions) {
    val context = LocalContext.current
    val app = context.app
    val config by app.config.config.collectAsState()
    val runtime by app.unlocks.state.collectAsState()
    val now by rememberTickingNow()
    val s = config.settings
    val scope = rememberCoroutineScope()
    val photoVersion by app.photos.version.collectAsState()
    val hasPhoto = remember(photoVersion) { app.photos.hasPhoto() }

    var exportDialog by remember { mutableStateOf(false) }
    var exportWithPhoto by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<BackupManager.ImportPreview?>(null) }

    fun set(transform: (UnlockSettings) -> UnlockSettings) =
        actions.updateConfig { it.copy(settings = transform(it.settings)) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.photos.setFromUri(uri, s.includePhotoInDeviceBackup) }
                .onFailure { actions.toast("Could not use that photo: ${it.message}") }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.backups.export(uri, exportWithPhoto) }
                .onSuccess { actions.toast("Configuration exported" + if (exportWithPhoto) " (including your photo)" else "") }
                .onFailure { actions.toast("Export failed: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { app.backups.readImport(uri) }
                .onSuccess { importPreview = it }
                .onFailure { actions.toast(it.message ?: "Import failed") }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.navigate(Screen.DASHBOARD) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        val editMs = UnlockLogic.remainingUnlockMs(runtime, UnlockTarget.SETTINGS, now)
        if (s.protectChanges && !config.isSetupMode) {
            Text(
                if (editMs > 0) "Editing unlocked for ${formatDuration(editMs)} — all changes allowed."
                else "Making unlocking easier requires completing the challenge first. Making it harder is always allowed.",
                style = MaterialTheme.typography.bodySmall,
                color = if (editMs > 0) Good else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Unlock challenge") {
            ToggleRow("Waiting period", s.waitEnabled) { v -> set { it.copy(waitEnabled = v) } }
            Stepper("Wait", s.waitMinutes, "min", 5, UnlockSettings.WAIT_RANGE, enabled = s.waitEnabled) { v ->
                set { it.copy(waitMinutes = v) }
            }
            ToggleRow("Math questions", s.mathEnabled) { v -> set { it.copy(mathEnabled = v) } }
            Stepper("Questions", s.questionCount, "", 1, UnlockSettings.QUESTION_RANGE, enabled = s.mathEnabled) { v ->
                set { it.copy(questionCount = v) }
            }
            Text("Difficulty")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Difficulty.entries.forEach { d ->
                    FilterChip(
                        selected = s.difficulty == d,
                        onClick = { set { it.copy(difficulty = d) } },
                        label = { Text(d.label) },
                        enabled = s.mathEnabled,
                    )
                }
            }
            Stepper("Unlock lasts", s.unlockMinutes, "min", 5, UnlockSettings.UNLOCK_RANGE) { v ->
                set { it.copy(unlockMinutes = v) }
            }
        }

        SectionCard("Protection") {
            ToggleRow(
                "Protect changes",
                s.protectChanges,
                "Removing apps/websites or weakening these settings requires the challenge.",
            ) { v -> set { it.copy(protectChanges = v) } }
            ToggleRow(
                "Block encrypted-DNS providers",
                s.blockEncryptedDns,
                "Blocks well-known DNS-over-HTTPS hosts (e.g. dns.google, cloudflare-dns.com) so browsers with " +
                    "\"Secure DNS\" cannot skip the website filter. Browsers that use DNS-over-HTTPS by IP address can still bypass it.",
            ) { v -> set { it.copy(blockEncryptedDns = v) } }
        }

        SectionCard("Blocking-screen photo") {
            PersonalPhoto(height = 200.dp)
            Text(
                "Picked with Android's Photo Picker (no storage permission). A private copy is stored inside the app " +
                    "and is never uploaded by this app.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (hasPhoto) "Change photo" else "Choose photo") }
                if (hasPhoto) OutlinedButton(onClick = { scope.launch { app.photos.remove() } }) { Text("Remove") }
            }
            ToggleRow(
                "Include photo in Android backup",
                s.includePhotoInDeviceBackup,
                "Off (default): the photo stays only on this phone and is lost on uninstall. On: Android's own " +
                    "backup (Google account, end-to-end encrypted when a screen lock is set) also stores the photo.",
            ) { v ->
                scope.launch {
                    val r = app.config.update { it.copy(settings = it.settings.copy(includePhotoInDeviceBackup = v)) }
                    if (r != ConfigRepository.UpdateResult.NEEDS_UNLOCK) app.photos.relocate(v)
                }
            }
        }

        SectionCard("Backup & restore") {
            Text(
                "Your blocked apps, websites and settings are included in Android's automatic backup (if enabled in " +
                    "Android settings) and restored when you reinstall the app on a phone with the same Google account. " +
                    "Without such a backup, uninstalling deletes everything. You can also save a file yourself:",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { exportWithPhoto = false; exportDialog = true }) { Text("Export…") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import…") }
            }
        }

        SectionCard("Privacy & permissions") {
            Text(
                "• Accessibility: used only to see which app comes to the front (package name) to show the blocking " +
                    "screen. Screen content is not read.\n" +
                    "• VPN: a local DNS filter. Only DNS lookups pass through it, they are checked on the phone and " +
                    "forwarded to your network's normal DNS server. No browsing data is collected or sent anywhere.\n" +
                    "• Notifications: warnings if protection stops; blocked-website notices.\n" +
                    "• No ads, analytics, accounts or tracking. The app talks to no server of its own.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(" ")
    }

    if (exportDialog) {
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("Export configuration") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The file contains your blocked apps, websites and settings.")
                    if (hasPhoto) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = exportWithPhoto, onCheckedChange = { exportWithPhoto = it })
                            Text("Also include my personal photo")
                        }
                        if (exportWithPhoto) {
                            Text(
                                "Your photo will be stored inside the exported file. Anyone who gets this file can see it. " +
                                    "If you save it to a cloud folder, the photo is uploaded there.",
                                color = Warn, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exportDialog = false
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportLauncher.launch("impulse-blocker-$date.json")
                }) { Text("Choose file") }
            },
            dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("Cancel") } },
        )
    }

    importPreview?.let { preview ->
        var restorePhoto by remember(preview) { mutableStateOf(preview.photoBytes != null) }
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text("Import configuration?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This replaces your current configuration with ${preview.config.blockedApps.size} blocked app(s) " +
                            "and ${preview.config.blockedDomains.size} website(s).",
                    )
                    if (preview.photoBytes != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = restorePhoto, onCheckedChange = { restorePhoto = it })
                            Text("Also restore the photo from the file")
                        }
                    }
                    if (preview.config.webBlockingEnabled) {
                        Text("Website blocking may need the VPN permission again (see dashboard).", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    importPreview = null
                    scope.launch {
                        runCatching { app.backups.applyImport(preview, restorePhoto) }
                            .onSuccess { r ->
                                if (r == ConfigRepository.UpdateResult.NEEDS_UNLOCK) {
                                    actions.toast("This import would weaken protection. Complete the challenge first, then import again.")
                                    actions.requestUnlock(UnlockTarget.SETTINGS)
                                } else actions.toast("Configuration imported")
                            }
                            .onFailure { actions.toast("Import failed: ${it.message}") }
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importPreview = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
