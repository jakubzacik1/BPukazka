package app.impulseblocker.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.impulseblocker.app
import app.impulseblocker.core.ChallengeStage
import app.impulseblocker.core.ChallengeState
import app.impulseblocker.core.MathGenerator
import app.impulseblocker.core.MonoInstant
import app.impulseblocker.core.TargetType
import app.impulseblocker.core.UnlockLogic
import app.impulseblocker.core.UnlockTarget
import app.impulseblocker.util.AppCatalog
import kotlinx.coroutines.launch

/**
 * The difficult unlock flow: photo → waiting period → math → confirmation.
 * All progress that matters lives in persisted RuntimeState; this screen only
 * renders it. Math progress is deliberately not persisted: leaving during the
 * math step means starting the questions again (the waiting period is kept).
 */
@Composable
fun ChallengeScreen(actions: UiActions) {
    val context = LocalContext.current
    val app = context.app
    val runtime by app.unlocks.state.collectAsState()
    val now by rememberTickingNow()
    var lastTarget by remember { mutableStateOf<UnlockTarget?>(null) }
    val challenge = runtime.challenge
    if (challenge != null) lastTarget = challenge.target

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.navigate(Screen.DASHBOARD) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Temporary unlock", style = MaterialTheme.typography.titleLarge)
        }
        PersonalPhoto(height = 300.dp)

        when {
            challenge != null -> ChallengeSteps(challenge, now, actions)
            lastTarget != null && UnlockLogic.isUnlocked(runtime, lastTarget!!, now) ->
                UnlockedView(lastTarget!!, UnlockLogic.remainingUnlockMs(runtime, lastTarget!!, now), actions)
            else -> {
                Text("No unlock request is running.")
                Button(onClick = { actions.navigate(Screen.DASHBOARD) }) { Text("Back to dashboard") }
            }
        }
    }
}

@Composable
private fun ChallengeSteps(c: ChallengeState, now: MonoInstant, actions: UiActions) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val name = remember(c.target) { targetLabel(context, c.target) }
    val stage = UnlockLogic.effectiveStage(c, now)

    Text(
        when (c.target.type) {
            TargetType.SETTINGS -> "Unlock editing of blocked items and settings"
            else -> "Unlock \"$name\""
        },
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
    )
    val steps = buildList {
        if (c.waitDurationMs > 0) add(ChallengeStage.WAITING to "Wait")
        if (c.mathRequired) add(ChallengeStage.MATH to "Math")
        add(ChallengeStage.CONFIRM to "Confirm")
    }
    Text(
        steps.joinToString("  →  ") { (s, label) -> if (s == stage) "[$label]" else label },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    when (stage) {
        ChallengeStage.WAITING -> {
            val remaining = UnlockLogic.waitRemainingMs(c, now)
            Text(
                formatDuration(remaining), fontSize = 56.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            if (c.waitDurationMs > 0) {
                LinearProgressIndicator(
                    progress = { 1f - remaining.toFloat() / c.waitDurationMs },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                "Take a breath. The countdown keeps running if you leave the app or lock the screen. " +
                    "Changing the phone's clock does not shorten it. Do you really want this right now?",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChallengeStage.MATH -> MathStep(c) { scope.launch { app.unlocks.completeMath() } }
        ChallengeStage.CONFIRM -> {
            val minutes = c.unlockDurationMs / 60_000
            Text(
                if (c.target.type == TargetType.SETTINGS)
                    "You can then remove blocked items and relax settings for $minutes minutes. Still want that?"
                else
                    "Do you still want to open \"$name\" for $minutes minutes? Only \"$name\" is unlocked; " +
                        "everything else stays blocked. Blocking returns automatically.",
            )
            Button(onClick = { scope.launch { app.unlocks.confirm() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Yes, unlock for $minutes minutes")
            }
        }
    }

    OutlinedButton(
        onClick = {
            scope.launch { app.unlocks.cancelChallenge() }
            actions.navigate(Screen.DASHBOARD)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Never mind — keep it blocked") }
}

@Composable
private fun MathStep(c: ChallengeState, onPassed: () -> Unit) {
    val generator = remember { MathGenerator() }
    var questions by remember(c) { mutableStateOf(generator.generate(c.questionCount, c.difficulty)) }
    var index by remember(c) { mutableIntStateOf(0) }
    var input by remember(c) { mutableStateOf("") }
    var message by remember(c) { mutableStateOf<String?>(null) }

    fun submit() {
        val q = questions[index]
        if (q.isCorrect(input)) {
            input = ""
            message = null
            if (index + 1 >= questions.size) onPassed() else index++
        } else {
            // Any mistake restarts the whole math step with new questions.
            questions = generator.generate(c.questionCount, c.difficulty)
            index = 0
            input = ""
            message = "Wrong answer. The math challenge restarts with new questions."
        }
    }

    Text("Question ${index + 1} of ${questions.size}  ·  ${c.difficulty.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        "${questions[index].text} = ?", fontSize = 40.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
    )
    OutlinedTextField(
        value = input,
        onValueChange = { v -> input = v.filter { it.isDigit() || it == '-' }.take(9) },
        label = { Text("Answer") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) submit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    message?.let { Text(it, color = Bad) }
    Button(onClick = { submit() }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Submit") }
}

@Composable
private fun UnlockedView(target: UnlockTarget, remainingMs: Long, actions: UiActions) {
    val context = LocalContext.current
    val name = remember(target) { targetLabel(context, target) }
    Text(
        if (target.type == TargetType.SETTINGS) "Editing unlocked for ${formatDuration(remainingMs)}"
        else "\"$name\" is unlocked for ${formatDuration(remainingMs)}",
        style = MaterialTheme.typography.titleMedium, color = Good,
    )
    when (target.type) {
        TargetType.APP -> Button(onClick = {
            AppCatalog.launchIntent(context, target.id)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } ?: actions.toast("This app is not installed")
        }, modifier = Modifier.fillMaxWidth()) { Text("Open $name") }
        TargetType.DOMAIN -> {
            Text(
                "Reload the page in your browser. If it still fails, wait up to a minute: the browser may have " +
                    "remembered the blocked answer.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://${target.id}/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Open ${target.id}") }
        }
        TargetType.SETTINGS -> Button(onClick = { actions.navigate(Screen.DASHBOARD) }, modifier = Modifier.fillMaxWidth()) {
            Text("Go to dashboard")
        }
    }
    OutlinedButton(onClick = { actions.navigate(Screen.DASHBOARD) }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}
