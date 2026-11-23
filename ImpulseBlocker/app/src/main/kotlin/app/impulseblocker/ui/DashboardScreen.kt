package app.impulseblocker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.impulseblocker.app
import app.impulseblocker.core.ChallengeStage
import app.impulseblocker.core.DomainRules
import app.impulseblocker.core.TargetType
import app.impulseblocker.core.UnlockLogic
import app.impulseblocker.core.UnlockTarget
import app.impulseblocker.service.DnsVpnService
import app.impulseblocker.util.AppCatalog
import app.impulseblocker.util.ProtectionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(actions: UiActions) {
    val context = LocalContext.current
    val app = context.app
    val config by app.config.config.collectAsState()
    val runtime by app.unlocks.state.collectAsState()
    val now by rememberTickingNow()

    var status by remember { mutableStateOf(ProtectionStatus.read(context)) }
    LaunchedEffect(Unit) {
        // Refresh while the dashboard is visible (UI only; services never poll).
        while (true) {
            status = ProtectionStatus.read(context)
            delay(1500)
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            actions.updateConfig { it.copy(webBlockingEnabled = true) }
            DnsVpnService.start(context)
        } else {
            actions.toast("VPN permission was not granted. Website blocking stays off.")
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun enableWebBlocking() {
        val prepare = VpnService.prepare(context)
        if (prepare != null) vpnPermission.launch(prepare)
        else {
            actions.updateConfig { it.copy(webBlockingEnabled = true) }
            DnsVpnService.start(context)
        }
    }

    // Keep the VPN state in line with the setting (e.g. after it was turned off and back on).
    val loaded by app.config.loaded.collectAsState()
    LaunchedEffect(config.webBlockingEnabled, loaded) {
        if (loaded && !config.webBlockingEnabled && DnsVpnService.status.value.running) DnsVpnService.stop(context)
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Impulse Blocker", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { actions.navigate(Screen.SETTINGS) }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        // ---------- Protection status ----------
        item {
            SectionCard("Protection status") {
                StatusLine(
                    label = "App blocking",
                    ok = config.appBlockingEnabled && status.accessibilityRunning,
                    text = when {
                        !config.appBlockingEnabled -> "Turned off"
                        !status.accessibilityEnabled -> "Accessibility permission is OFF"
                        !status.accessibilityRunning -> "Enabled, but Android has stopped the service"
                        else -> "Active"
                    },
                    switchChecked = config.appBlockingEnabled,
                    onSwitch = { on -> actions.updateConfig { it.copy(appBlockingEnabled = on) } },
                )
                if (config.appBlockingEnabled && !status.accessibilityRunning) {
                    Text(
                        if (status.accessibilityEnabled)
                            "Turn \"Impulse Blocker – app blocking\" off and on again in Accessibility settings."
                        else
                            "Open Accessibility → Downloaded apps → Impulse Blocker and turn it on. " +
                                "If the switch is greyed out (\"Restricted setting\"), open App info → ⋮ → " +
                                "\"Allow restricted settings\" first.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { openAccessibilitySettings(context) }) { Text("Accessibility settings") }
                        OutlinedButton(onClick = { openAppInfo(context) }) { Text("App info") }
                    }
                }
                HorizontalDivider()
                StatusLine(
                    label = "Website blocking",
                    ok = config.webBlockingEnabled && status.vpnRunning,
                    text = when {
                        !config.webBlockingEnabled -> "Turned off"
                        !status.vpnPermission -> "VPN permission missing"
                        !status.vpnRunning -> "Stopped" + (status.vpnStopReason?.let { ": $it" } ?: "")
                        status.privateDnsStrict -> "Running, but Private DNS bypasses it"
                        else -> "Active" + if (status.vpnAlwaysOn) " (always-on)" else ""
                    },
                    switchChecked = config.webBlockingEnabled,
                    onSwitch = { on ->
                        if (on) enableWebBlocking()
                        else actions.updateConfig { it.copy(webBlockingEnabled = false) }
                    },
                )
                if (config.webBlockingEnabled && !status.vpnRunning) {
                    if (status.otherVpnActive) {
                        Text(
                            "Another VPN app seems to be active. Android allows only one VPN at a time.",
                            style = MaterialTheme.typography.bodySmall, color = Warn,
                        )
                    }
                    FilledTonalButton(onClick = { enableWebBlocking() }) { Text("Restart website blocking") }
                }
                if (config.webBlockingEnabled && status.privateDnsStrict) {
                    Text(
                        "Private DNS is set to a specific provider. Android then sends lookups directly to that " +
                            "provider and blocked websites are NOT filtered. Set Private DNS to \"Automatic\" or \"Off\".",
                        style = MaterialTheme.typography.bodySmall, color = Warn,
                    )
                    OutlinedButton(onClick = { openSettings(context, "android.settings.NETWORK_AND_INTERNET_SETTINGS", Settings.ACTION_WIRELESS_SETTINGS) }) {
                        Text("Network settings")
                    }
                }
                if (config.webBlockingEnabled && status.vpnRunning && !status.vpnAlwaysOn) {
                    Text(
                        "Recommended: Settings → Network → VPN → ⚙ Impulse Blocker → \"Always-on VPN\" ON, " +
                            "so Android restarts website blocking by itself. Keep \"Block connections without VPN\" OFF " +
                            "(this filter only handles DNS).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { openSettings(context, Settings.ACTION_VPN_SETTINGS) }) { Text("VPN settings") }
                }
                if (status.vpnLockdown) {
                    Text(
                        "\"Block connections without VPN\" is ON. This app only filters DNS, so other traffic may be blocked. Turn it off.",
                        style = MaterialTheme.typography.bodySmall, color = Warn,
                    )
                }
                HorizontalDivider()
                if (!status.batteryUnrestricted) {
                    Text(
                        "Battery: optimized. Allowing unrestricted battery use helps Android keep protection running.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { requestBatteryExemption(context) }) { Text("Allow unrestricted battery") }
                } else {
                    Text("Battery: unrestricted ✓", style = MaterialTheme.typography.bodySmall, color = Good)
                }
                if (!status.notificationsAllowed && Build.VERSION.SDK_INT >= 33) {
                    Text(
                        "Notifications are off: you will not be warned if protection stops, and blocked websites can't offer an unlock.",
                        style = MaterialTheme.typography.bodySmall, color = Warn,
                    )
                    OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                        Text("Allow notifications")
                    }
                }
            }
        }

        // ---------- Running challenge / active unlocks ----------
        runtime.challenge?.let { ch ->
            item {
                SectionCard("Unlock request in progress") {
                    val stage = UnlockLogic.effectiveStage(ch, now)
                    Text("${targetLabel(context, ch.target)} — " + when (stage) {
                        ChallengeStage.WAITING -> "waiting ${formatDuration(UnlockLogic.waitRemainingMs(ch, now))}"
                        ChallengeStage.MATH -> "math questions ready"
                        ChallengeStage.CONFIRM -> "waiting for your confirmation"
                    })
                    Button(onClick = { actions.navigate(Screen.CHALLENGE) }) { Text("Continue") }
                }
            }
        }
        val activeUnlocks = runtime.unlocks.filter { (it.deadline.remainingMs(now) ?: 0) > 0 }
        if (activeUnlocks.isNotEmpty()) {
            item {
                SectionCard("Temporarily unlocked") {
                    activeUnlocks.forEach { u ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${targetLabel(context, u.target)}: ${formatDuration(u.deadline.remainingMs(now) ?: 0)}",
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { actions.toast("Blocked again"); revoke(context, u.target) }) { Text("Block now") }
                        }
                    }
                }
            }
        }

        // ---------- Blocked apps ----------
        item {
            SectionCard(
                "Blocked apps (${config.blockedApps.size})",
                action = {
                    IconButton(onClick = { actions.navigate(Screen.APP_PICKER) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add apps")
                    }
                },
            ) {
                if (config.blockedApps.isEmpty()) {
                    Text("No apps blocked yet. Tap + to choose apps.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                config.blockedApps.forEach { pkg ->
                    BlockedAppRow(pkg, actions, onRemove = {
                        actions.updateConfig { c -> c.copy(blockedApps = c.blockedApps - pkg) }
                    })
                }
            }
        }

        // ---------- Blocked websites ----------
        item {
            DomainsCard(config.blockedDomains, actions)
        }
        item { Spacer(Modifier.padding(12.dp)) }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, text: String, switchChecked: Boolean, onSwitch: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(text, color = if (ok) Good else if (switchChecked) Bad else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = switchChecked, onCheckedChange = onSwitch)
    }
}

@Composable
private fun BlockedAppRow(pkg: String, actions: UiActions, onRemove: () -> Unit) {
    val context = LocalContext.current
    val installed = remember(pkg) { AppCatalog.isInstalled(context, pkg) }
    val label = remember(pkg) { AppCatalog.label(context, pkg) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(pkg)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(
                if (installed) pkg else "$pkg · not installed (stays blocked if installed later)",
                style = MaterialTheme.typography.bodySmall,
                color = if (installed) MaterialTheme.colorScheme.onSurfaceVariant else Warn,
            )
        }
        TextButton(onClick = { actions.requestUnlock(UnlockTarget.app(pkg)) }) { Text("Unlock") }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove $label") }
    }
}

@Composable
private fun DomainsCard(domains: List<String>, actions: UiActions) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }

    fun add() {
        when (val r = DomainRules.normalize(input)) {
            is DomainRules.Result.Error -> error = r.message
            is DomainRules.Result.Ok -> {
                error = null
                input = ""
                actions.updateConfig { c ->
                    if (r.domain in c.blockedDomains) c else c.copy(blockedDomains = c.blockedDomains + r.domain)
                }
            }
        }
    }

    SectionCard("Blocked websites (${domains.size})") {
        Text(
            "Each entry blocks that domain and all its subdomains (wolt.com also blocks www.wolt.com). " +
                "Other countries' domains (wolt.fi, foodora.sk …) and other delivery services must be added separately.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val err = error
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; error = null },
                label = { Text("Domain or URL") },
                singleLine = true,
                isError = err != null,
                supportingText = if (err != null) ({ Text(err) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { add() }, enabled = input.isNotBlank()) { Text("Add") }
        }
        domains.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d, modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.requestUnlock(UnlockTarget(TargetType.DOMAIN, d)) }) { Text("Unlock") }
                IconButton(onClick = { editing = d }) { Icon(Icons.Default.Edit, contentDescription = "Edit $d") }
                IconButton(onClick = { actions.updateConfig { c -> c.copy(blockedDomains = c.blockedDomains - d) } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove $d")
                }
            }
        }
    }

    editing?.let { old ->
        var text by remember(old) { mutableStateOf(old) }
        var editError by remember(old) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit domain") },
            text = {
                val err = editError
                OutlinedTextField(
                    value = text, onValueChange = { text = it; editError = null }, singleLine = true,
                    isError = err != null, supportingText = if (err != null) ({ Text(err) }) else null,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (val r = DomainRules.normalize(text)) {
                        is DomainRules.Result.Error -> editError = r.message
                        is DomainRules.Result.Ok -> {
                            editing = null
                            // Replacing a rule removes the old one, so it counts as a protected change.
                            actions.updateConfig { c ->
                                c.copy(blockedDomains = c.blockedDomains.map { if (it == old) r.domain else it }.distinct())
                            }
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

private fun revoke(context: Context, target: UnlockTarget) {
    val app = context.app
    app.appScope.launch { app.unlocks.revoke(target) }
}

fun openAccessibilitySettings(context: Context) =
    openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)

fun openAppInfo(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

fun openSettings(context: Context, vararg actions: String) {
    for (action in actions) {
        val ok = runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) return
    }
    runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@SuppressLint("BatteryLife")
fun requestBatteryExemption(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(direct) }.isFailure) {
        openSettings(context, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}
