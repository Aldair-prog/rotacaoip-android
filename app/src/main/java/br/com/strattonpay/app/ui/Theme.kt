package br.com.strattonpay.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta tirada da logo do leão (gradiente #4A0E78 → #7B2FD0)
val Brand = Color(0xFF7B2FD0)
val BrandLight = Color(0xFFA56BFF)
val BrandDark = Color(0xFF4A0E78)
val Bg = Color(0xFF0D0718)
val Surface1 = Color(0xFF171026)
val Surface2 = Color(0xFF221838)
val Outline = Color(0xFF33264D)
val TextMain = Color(0xFFF1ECFA)
val Muted = Color(0xFF9A8FB3)
val Success = Color(0xFF2ECC71)
val Warning = Color(0xFFF5B83D)
val Danger = Color(0xFFE5484D)

private val StrattonColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandDark,
    onPrimaryContainer = Color.White,
    secondary = BrandLight,
    onSecondary = Color.White,
    secondaryContainer = Surface2,
    onSecondaryContainer = BrandLight,
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
