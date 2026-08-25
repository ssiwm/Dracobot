package eu.draconest.hermesbots.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Grok-like: grafitowa czerń + jeden chłodny akcent
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF072A47),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1D1D1D),
    onSurfaceVariant = Color(0xFFB5B5B5),
    outline = Color(0xFF333333),
    secondaryContainer = Color(0xFF232323),
    onSecondaryContainer = Color(0xFFEDEDED),
    primaryContainer = Color(0xFF12314F),
    onPrimaryContainer = Color(0xFFCFE3FF),
    error = Color(0xFFFF6B6B)
)

@Composable
fun HermesBotsTheme(content: @Composable () -> Unit) {
    // MVP: ciemny zawsze (jak Grok); light/dynamic color w M3
    MaterialTheme(colorScheme = DarkColors, content = content)
}
