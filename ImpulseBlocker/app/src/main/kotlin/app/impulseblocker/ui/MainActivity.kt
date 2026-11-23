package app.impulseblocker.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.impulseblocker.app
import app.impulseblocker.core.BlockerConfig
import app.impulseblocker.core.TargetType
import app.impulseblocker.core.UnlockTarget
import app.impulseblocker.data.ConfigRepository
import kotlinx.coroutines.launch

enum class Screen { DASHBOARD, APP_PICKER, SETTINGS, CHALLENGE }

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_UNLOCK_TYPE = "unlock_type"
        const val EXTRA_UNLOCK_ID = "unlock_id"

        fun unlockIntent(context: Context, target: UnlockTarget): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_UNLOCK_TYPE, target.type.name)
                .putExtra(EXTRA_UNLOCK_ID, target.id)
    }

    /** Unlock request delivered by the blocking screen or a notification. */
    private val pendingRequest = mutableStateOf<UnlockTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            ImpulseTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) { AppRoot(pendingRequest) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val type = intent?.getStringExtra(EXTRA_UNLOCK_TYPE) ?: return
        val id = intent.getStringExtra(EXTRA_UNLOCK_ID) ?: return
        val t = runCatching { TargetType.valueOf(type) }.getOrNull() ?: return
        pendingRequest.value = UnlockTarget(t, id)
    }
}

/** Shared UI actions (navigation + protected config changes). */
class UiActions(
    val navigate: (Screen) -> Unit,
    val requestUnlock: (UnlockTarget) -> Unit,
    /** Applies a config change; if it weakens protection, asks to complete the challenge first. */
    val updateConfig: ((BlockerConfig) -> BlockerConfig) -> Unit,
    val toast: (String) -> Unit,
)

@Composable
fun AppRoot(pendingRequest: MutableState<UnlockTarget?>) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.DASHBOARD) }
    var replaceTarget by remember { mutableStateOf<UnlockTarget?>(null) }
    var showProtectedDialog by remember { mutableStateOf(false) }

    fun startOrContinue(target: UnlockTarget) {
        val current = app.unlocks.state.value.challenge
        when {
            current?.target == target -> screen = Screen.CHALLENGE
            current != null -> replaceTarget = target
            else -> scope.launch {
                app.unlocks.startChallenge(target, app.config.config.value.settings)
                screen = Screen.CHALLENGE
            }
        }
    }

    val actions = remember {
        UiActions(
            navigate = { screen = it },
            requestUnlock = { startOrContinue(it) },
            updateConfig = { transform ->
                scope.launch {
                    if (app.config.update(transform) == ConfigRepository.UpdateResult.NEEDS_UNLOCK) {
                        showProtectedDialog = true
                    }
                }
            },
            toast = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
        )
    }

    LaunchedEffect(pendingRequest.value) {
        val target = pendingRequest.value ?: return@LaunchedEffect
        pendingRequest.value = null
        startOrContinue(target)
    }

    BackHandler(enabled = screen != Screen.DASHBOARD) { screen = Screen.DASHBOARD }

    when (screen) {
        Screen.DASHBOARD -> DashboardScreen(actions)
        Screen.APP_PICKER -> AppPickerScreen(actions)
        Screen.SETTINGS -> SettingsScreen(actions)
        Screen.CHALLENGE -> ChallengeScreen(actions)
    }

    replaceTarget?.let { target ->
        val current = app.unlocks.state.value.challenge
        AlertDialog(
            onDismissRequest = { replaceTarget = null },
            title = { Text("Another unlock request is running") },
            text = {
                Text(
                    "A request for \"${current?.let { targetLabel(context, it.target) }}\" is in progress. " +
                        "Starting a request for \"${targetLabel(context, target)}\" cancels it and starts a new waiting period.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    replaceTarget = null
                    scope.launch {
                        app.unlocks.startChallenge(target, app.config.config.value.settings)
                        screen = Screen.CHALLENGE
                    }
                }) { Text("Start new request") }
            },
            dismissButton = {
                TextButton(onClick = { replaceTarget = null; screen = Screen.CHALLENGE }) { Text("Continue current") }
            },
        )
    }

    if (showProtectedDialog) {
        AlertDialog(
            onDismissRequest = { showProtectedDialog = false },
            title = { Text("This change is protected") },
            text = {
                Text(
                    "Removing blocked items or making unlocking easier would bypass the challenge, " +
                        "so it requires completing the same steps (waiting period, math, confirmation). " +
                        "Afterwards you can edit freely for 10 minutes.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showProtectedDialog = false
                    startOrContinue(UnlockTarget.SETTINGS)
                }) { Text("Start challenge") }
            },
            dismissButton = { TextButton(onClick = { showProtectedDialog = false }) { Text("Keep it") } },
        )
    }
}
