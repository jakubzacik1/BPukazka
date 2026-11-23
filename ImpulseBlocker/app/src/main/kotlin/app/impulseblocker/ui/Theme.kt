package app.impulseblocker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Good = Color(0xFF6FD08C)
val Bad = Color(0xFFFF6B5B)
val Warn = Color(0xFFFFC857)

private val colors = darkColorScheme(
    primary = Color(0xFFFF8A7A),
    onPrimary = Color(0xFF2B0B07),
    secondary = Color(0xFF9CC3FF),
    background = Color(0xFF101214),
    onBackground = Color(0xFFE6E8EA),
    surface = Color(0xFF101214),
    onSurface = Color(0xFFE6E8EA),
    surfaceVariant = Color(0xFF1C2024),
    onSurfaceVariant = Color(0xFFB4BAC0),
    surfaceContainer = Color(0xFF1A1D21),
    surfaceContainerHigh = Color(0xFF22262B),
    error = Bad,
)

@Composable
fun ImpulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
