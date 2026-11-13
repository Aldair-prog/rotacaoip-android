package br.com.strattonpay.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFD4AF37)
val GoldDark = Color(0xFFA8872A)
val Bg = Color(0xFF0A0E17)
val Surface1 = Color(0xFF131A27)
val Surface2 = Color(0xFF1B2433)
val Outline = Color(0xFF2A3445)
val TextMain = Color(0xFFEDEFF4)
val Muted = Color(0xFF8A93A6)
val Success = Color(0xFF2ECC71)
val Warning = Color(0xFFF5B83D)
val Danger = Color(0xFFE5484D)

private val StrattonColors = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF111111),
    primaryContainer = GoldDark,
    onPrimaryContainer = Color(0xFF111111),
    secondary = Gold,
    onSecondary = Color(0xFF111111),
    secondaryContainer = Surface2,
    onSecondaryContainer = Gold,
    background = Bg,
    onBackground = TextMain,
    surface = Surface1,
    onSurface = TextMain,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    surfaceContainerLowest = Bg,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    outline = Outline,
    outlineVariant = Outline,
    error = Danger,
    onError = Color.White,
)

@Composable
fun StrattonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StrattonColors, content = content)
}
