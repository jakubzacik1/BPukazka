package app.impulseblocker.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.impulseblocker.app
import app.impulseblocker.core.MonoInstant
import app.impulseblocker.core.TargetType
import app.impulseblocker.core.UnlockTarget
import app.impulseblocker.util.AppCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun formatDuration(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

fun targetLabel(context: Context, target: UnlockTarget): String = when (target.type) {
    TargetType.APP -> AppCatalog.label(context, target.id)
    TargetType.DOMAIN -> target.id
    TargetType.SETTINGS -> "changing blocked items / settings"
}

/** Monotonic "now" that updates every second while the composable is shown (UI only). */
@Composable
fun rememberTickingNow(): State<MonoInstant> {
    val clock = LocalContext.current.app.unlocks.clock
    return produceState(clock.now()) {
        while (true) {
            delay(1000)
            value = clock.now()
        }
    }
}

@Composable
fun rememberPhoto(): ImageBitmap? {
    val context = LocalContext.current
    val store = context.app.photos
    val version by store.version.collectAsState()
    val bitmap by produceState<ImageBitmap?>(null, version) {
        value = store.loadBitmap()?.asImageBitmap()
    }
    return bitmap
}

@Composable
fun PersonalPhoto(modifier: Modifier = Modifier, height: Dp = 280.dp) {
    val photo = rememberPhoto()
    if (photo != null) {
        Image(
            bitmap = photo,
            contentDescription = "Your personal photo",
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).height(height)
                .clip(RoundedCornerShape(16.dp)),
        )
    } else {
        Box(
            modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No personal photo yet.\nChoose one in Settings – a reminder of why you block these apps.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) { AppCatalog.icon(context, pkg, px)?.asImageBitmap() }
    }
    val bmp = icon
    if (bmp != null) {
        Image(bmp, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

/** "−  value  +" control. */
@Composable
fun Stepper(label: String, value: Int, suffix: String, step: Int, range: IntRange, enabled: Boolean = true, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = enabled && value > range.first) { Text("−") }
        Text("$value $suffix", modifier = Modifier.width(84.dp).padding(horizontal = 8.dp), fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = enabled && value < range.last) { Text("+") }
    }
}
