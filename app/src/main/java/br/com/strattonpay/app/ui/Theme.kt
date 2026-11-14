package br.com.strattonpay.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import br.com.strattonpay.app.BuildConfig
import br.com.strattonpay.app.R

private val isConnect = BuildConfig.FLAVOR == "connect"

// Stratton: roxo do leão (#4A0E78 → #7B2FD0). Connect: vermelho do CP.
val Brand = if (isConnect) Color(0xFFE11D2E) else Color(0xFF7B2FD0)
val BrandLight = if (isConnect) Color(0xFFFF4D5E) else Color(0xFFA56BFF)
val BrandDark = if (isConnect) Color(0xFF8E0A16) else Color(0xFF4A0E78)
val BrandArgb: Int = if (isConnect) 0xFFE11D2E.toInt() else 0xFF7B2FD0.toInt()

// Fundo igual ao do painel de cada marca, para o app e o site ficarem sem emenda.
val Bg = if (isConnect) Color(0xFF0B0C11) else Color(0xFF0B0E12)
val Surface1 = Color(0xFF13161D)
val Surface2 = Color(0xFF1B1F28)
val Outline = Color(0xFF262B36)
val TextMain = Color(0xFFF1F2F5)
val Muted = Color(0xFF8E95A3)
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

/** Fonte própria (Inter) para o app ficar igual em qualquer celular, mesmo com fonte do sistema trocada. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun TextStyle.inter() = copy(fontFamily = Inter)

private val StrattonType = Typography().let { b ->
    Typography(
        displayLarge = b.displayLarge.inter(),
        displayMedium = b.displayMedium.inter(),
        displaySmall = b.displaySmall.inter(),
        headlineLarge = b.headlineLarge.inter(),
        headlineMedium = b.headlineMedium.inter(),
        headlineSmall = b.headlineSmall.inter(),
        titleLarge = b.titleLarge.inter(),
        titleMedium = b.titleMedium.inter(),
        titleSmall = b.titleSmall.inter(),
        bodyLarge = b.bodyLarge.inter(),
        bodyMedium = b.bodyMedium.inter(),
        bodySmall = b.bodySmall.inter(),
        labelLarge = b.labelLarge.inter(),
        labelMedium = b.labelMedium.inter(),
        labelSmall = b.labelSmall.inter(),
    )
}

@Composable
fun StrattonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = StrattonColors, typography = StrattonType, content = content)
}
