package app.fitcoach.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF14201B), onPrimary = Color.White,
    secondary = Color(0xFFE0A100), onSecondary = Color(0xFF14201B),
    background = Color(0xFFEEF1EC), onBackground = Color(0xFF14201B),
    surface = Color.White, onSurface = Color(0xFF14201B),
    surfaceVariant = Color(0xFFE3E8E4), onSurfaceVariant = Color(0xFF5C6B64),
    outline = Color(0xFFD9DFDA), error = Color(0xFF9B2C4B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2B825), onPrimary = Color(0xFF10231D),
    secondary = Color(0xFFF2B825), onSecondary = Color(0xFF10231D),
    background = Color(0xFF0E1714), onBackground = Color(0xFFE8EFEA),
    surface = Color(0xFF16231F), onSurface = Color(0xFFE8EFEA),
    surfaceVariant = Color(0xFF21332B), onSurfaceVariant = Color(0xFF93A59C),
    outline = Color(0xFF27382F), error = Color(0xFFE27A98),
)

/** Meaning colours that sit outside the Material scheme. */
object Pal {
    val ok @Composable get() = if (isSystemInDarkTheme()) Color(0xFF5DBB92) else Color(0xFF2F7D5B)
    val skip @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE27A98) else Color(0xFF9B2C4B)
    val water @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6BB6DE) else Color(0xFF2D7DA8)
}

@Composable
fun FitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
