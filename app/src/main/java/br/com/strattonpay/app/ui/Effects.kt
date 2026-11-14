@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package br.com.strattonpay.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Cromo: o mesmo prateado da logo. */
val ChromeBrush = Brush.linearGradient(
    listOf(Color(0xFFFFFFFF), Color(0xFFB9BCC4), Color(0xFFF4F5F7), Color(0xFF8D919B), Color(0xFFE9EAEE)),
)

/** Borda metálica dos cartões: cor da marca → prata → apagado. */
val MetalBorder = Brush.linearGradient(
    listOf(Brand.copy(alpha = 0.85f), Color(0x66C9CCD3), Outline, Outline, Brand.copy(alpha = 0.35f)),
)

/** Aura da marca que "respira" atrás da logo. */
@Composable
fun BrandAura(size: Dp, modifier: Modifier = Modifier, intensity: Float = 1f) {
    val t = rememberInfiniteTransition(label = "aura")
    val pulse by t.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f * pulse
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Brand.copy(alpha = 0.55f * intensity), BrandDark.copy(alpha = 0.22f * intensity), Color.Transparent),
                center = center,
                radius = r,
            ),
            radius = r,
        )
    }
}

/** Reflexo de luz passando pelo cromo da logo (só em cima da imagem, não do fundo). */
fun Modifier.shineSweep(progress: () -> Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val p = progress()
        if (p <= 0f || p >= 1f) return@drawWithContent
        val w = size.width
        val x = -w * 0.6f + (w * 2.2f) * p
        drawRect(
            brush = Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent),
                start = Offset(x - w * 0.25f, 0f),
                end = Offset(x + w * 0.25f, size.height),
            ),
            blendMode = BlendMode.SrcAtop,
        )
    }

/** Reflexo que repete de tempos em tempos. */
@Composable
fun rememberShine(periodMs: Int = 2600, delayMs: Int = 300): () -> Float {
    val t = rememberInfiniteTransition(label = "shine")
    val v by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(periodMs, delayMillis = delayMs, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "shineProgress",
    )
    // O brilho atravessa na primeira metade e descansa na segunda.
    return { (v * 1.8f).coerceAtMost(1f) }
}

/** Linha de energia: um feixe de luz correndo de ponta a ponta. */
@Composable
fun EnergyLine(modifier: Modifier = Modifier, height: Dp = 2.dp, speedMs: Int = 1100, active: Boolean = true) {
    val t = rememberInfiniteTransition(label = "energy")
    val x by t.animateFloat(
        initialValue = -0.3f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(tween(speedMs, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "beam",
    )
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        // trilho apagado
        drawRect(
            Brush.horizontalGradient(listOf(Color.Transparent, Brand.copy(alpha = 0.25f), Color.Transparent)),
        )
        if (active) {
            val cx = w * x
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Brand, Color.White, Brand, Color.Transparent),
                    startX = cx - w * 0.22f,
                    endX = cx + w * 0.22f,
                ),
            )
        }
    }
}

/** Nome da marca aparecendo letra por letra, com cursor piscando no fim. */
@Composable
fun TypewriterWordmark(text: String, startDelayMs: Long = 350, fontSize: TextUnit = 22.sp, letterSpacing: TextUnit = 7.sp) {
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(text) {
        delay(startDelayMs)
        while (shown < text.length) {
            delay(55)
            shown++
        }
    }
    val t = rememberInfiniteTransition(label = "cursor")
    val blink by t.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "blink",
    )
    Box(contentAlignment = Alignment.Center) {
        // texto inteiro invisível reserva o espaço (não "pula" enquanto digita)
        androidx.compose.material3.Text(
            text,
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = letterSpacing, color = Color.Transparent),
        )
        androidx.compose.material3.Text(
            text.take(shown) + if (shown < text.length) "▍" else "",
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = letterSpacing, brush = ChromeBrush),
            modifier = Modifier.graphicsLayer { alpha = if (shown < text.length) 1f else 0.6f + 0.4f * blink },
        )
    }
}

/** Anel de scanner girando em volta do ícone de digital. */
@Composable
fun ScannerRing(size: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "scanner")
    val angle by t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(modifier.size(size)) {
        val stroke = 3.dp.toPx()
        drawCircle(Brand.copy(alpha = 0.18f), style = Stroke(stroke))
        rotate(angle) {
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, Brand.copy(alpha = 0.4f), BrandLight, Color.White)),
                startAngle = 0f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** Borda neon com a luz dando a volta (o formato fica parado, só o brilho gira). */
fun Modifier.neonBorder(angle: () -> Float, radius: Dp, width: Dp = 2.dp): Modifier = drawWithContent {
    drawContent()
    val r = radius.toPx()
    val sw = width.toPx()
    val a = Math.toRadians(angle().toDouble())
    val d = maxOf(size.width, size.height)
    val dx = (kotlin.math.cos(a) * d).toFloat()
    val dy = (kotlin.math.sin(a) * d).toFloat()
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(Brand.copy(alpha = 0.25f), BrandLight, Color.White, BrandLight, Brand.copy(alpha = 0.25f)),
            start = Offset(center.x - dx, center.y - dy),
            end = Offset(center.x + dx, center.y + dy),
        ),
        topLeft = Offset(sw / 2, sw / 2),
        size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        style = Stroke(sw),
    )
}
