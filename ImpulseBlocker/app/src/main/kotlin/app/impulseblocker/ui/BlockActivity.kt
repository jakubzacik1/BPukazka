package app.impulseblocker.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.impulseblocker.app
import app.impulseblocker.core.ChallengeStage
import app.impulseblocker.core.UnlockLogic
import app.impulseblocker.core.UnlockTarget
import app.impulseblocker.util.AppCatalog

/**
 * Covers a blocked app. Runs in its own task, excluded from Recents. Leaving
 * (button or Back) goes to the home screen rather than back into the app.
 */
class BlockActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_PACKAGE = "package"

        fun intent(context: Context, pkg: String): Intent =
            Intent(context, BlockActivity::class.java)
                .putExtra(EXTRA_PACKAGE, pkg)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
    }

    private val blockedPackage = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        blockedPackage.value = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent {
            ImpulseTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    BlockContent()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        blockedPackage.value = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
    }

    @androidx.compose.runtime.Composable
    private fun BlockContent() {
        val pkg by blockedPackage
        val name = remember(pkg) { AppCatalog.label(this, pkg) }
        val runtime by app.unlocks.state.collectAsState()
        val now by rememberTickingNow()
        val ch = runtime.challenge?.takeIf { it.target == UnlockTarget.app(pkg) }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PersonalPhoto(height = 340.dp)
            Text(
                "$name is blocked",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Text(
                "You chose to block this app to avoid impulsive orders.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Button(onClick = ::goHome, modifier = Modifier.fillMaxWidth()) { Text("Leave $name") }
            OutlinedButton(
                onClick = {
                    startActivity(MainActivity.unlockIntent(this@BlockActivity, UnlockTarget.app(pkg)))
                    finish()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        ch == null -> "Request temporary unlock"
                        UnlockLogic.effectiveStage(ch, now) == ChallengeStage.WAITING ->
                            "Unlock request: ${formatDuration(UnlockLogic.waitRemainingMs(ch, now))} left"
                        else -> "Continue unlock request"
                    },
                )
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}
