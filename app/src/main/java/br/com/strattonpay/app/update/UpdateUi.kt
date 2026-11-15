@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package br.com.strattonpay.app.update

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.com.strattonpay.app.BuildConfig
import br.com.strattonpay.app.R
import br.com.strattonpay.app.notify.AppSettings
import br.com.strattonpay.app.ui.Brand
import br.com.strattonpay.app.ui.BrandAura
import br.com.strattonpay.app.ui.BrandLight
import br.com.strattonpay.app.ui.ChromeBrush
import br.com.strattonpay.app.ui.Inter
import br.com.strattonpay.app.ui.MetalBorder
import br.com.strattonpay.app.ui.Muted
import br.com.strattonpay.app.ui.PrimaryButton
import br.com.strattonpay.app.ui.Surface1
import br.com.strattonpay.app.ui.Surface2
import br.com.strattonpay.app.ui.TextMain
import br.com.strattonpay.app.ui.Warning
import br.com.strattonpay.app.ui.rememberShine
import br.com.strattonpay.app.ui.shineSweep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/** Estado da atualização (compartilhado entre o aviso e a tela de Configurações). */
class UpdateState {
    var release by mutableStateOf<Updater.Release?>(null)
    var showDialog by mutableStateOf(false)
    var checking by mutableStateOf(false)
    var progress by mutableStateOf<Float?>(null)
    var message by mutableStateOf<String?>(null)
    var downloaded: File? = null

    /** Procura versão nova. manual = veio do botão (mostra "já está na mais nova"). */
    suspend fun check(context: Context, manual: Boolean) {
        if (checking) return
        checking = true
        message = null
        val found = try {
            Updater.check()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (manual) message = "Sem conexão para procurar atualização."
            null
        }
        checking = false
        val settings = AppSettings(context)
        settings.lastUpdateCheck = System.currentTimeMillis()
        if (found != null) {
            release = found
            if (manual || found.build != settings.postponedBuild) showDialog = true
        } else if (manual && message == null) {
            message = "Você já está na versão mais nova (${BuildConfig.VERSION_NAME})."
        }
    }
}

@Composable
fun rememberUpdateState(): UpdateState = remember { UpdateState() }

/** Aviso de versão nova: baixa com barra de progresso e abre o instalador. */
@Composable
fun UpdateDialog(state: UpdateState) {
    val rel = state.release ?: return
    if (!state.showDialog) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloading = state.progress != null
    val sizeMb = if (rel.sizeBytes > 0) "%.1f MB".format(rel.sizeBytes / 1_048_576f) else ""

    val start: () -> Unit = {
        val activity = context as? Activity
        if (activity != null) {
            scope.launch {
                state.message = null
                try {
                    val file = state.downloaded?.takeIf { it.exists() && it.name.contains("-${rel.build}.") }
                        ?: run {
                            state.progress = 0f
                            Updater.download(context, rel) { p -> state.progress = p }.also { state.downloaded = it }
                        }
                    state.progress = null
                    when (val result = Updater.install(activity, file)) {
                        Updater.InstallResult.Started -> state.showDialog = false
                        Updater.InstallResult.NeedsPermission ->
                            state.message = "Libere \"Permitir desta fonte\" para o ${BuildConfig.BRAND_NAME} e toque em Atualizar de novo."
                        is Updater.InstallResult.Rejected -> state.message = result.reason
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state.progress = null
                    state.message = "Não foi possível baixar agora. Verifique a internet e tente de novo."
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!downloading) state.showDialog = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.verticalGradient(listOf(Surface2, Surface1)))
                .border(BorderStroke(1.dp, MetalBorder), RoundedCornerShape(24.dp))
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                BrandAura(size = 130.dp, intensity = 0.8f)
                Image(
                    painter = painterResource(R.drawable.logo_emblem),
                    contentDescription = null,
                    modifier = Modifier.width(72.dp).shineSweep(rememberShine(periodMs = 2600, delayMs = 300)),
                )
            }
            Text(
                "Nova versão disponível",
                style = TextStyle(brush = ChromeBrush, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 21.sp),
            )
            Text(
                "${BuildConfig.VERSION_NAME}  →  ${rel.version}" + if (sizeMb.isNotEmpty()) "  ·  $sizeMb" else "",
                color = BrandLight,
                style = MaterialTheme.typography.labelLarge,
            )
            if (rel.notes.isNotBlank()) {
                Text(
                    rel.notes,
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .verticalScroll(rememberScrollState())
                        .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }
            val p = state.progress
            if (p != null) {
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = Brand,
                    trackColor = Color.White.copy(alpha = 0.08f),
                )
                Text("Baixando… ${(p * 100).toInt()}%", color = TextMain, style = MaterialTheme.typography.bodySmall)
            }
            state.message?.let {
                Text(it, color = Warning, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            PrimaryButton(
                text = if (state.downloaded != null) "Instalar atualização" else "Atualizar agora",
                onClick = start,
                loading = downloading,
            )
            if (!downloading) {
                TextButton(onClick = {
                    AppSettings(context).postponedBuild = rel.build
                    state.showDialog = false
                }) { Text("Depois", color = Muted) }
            }
            Text(
                "Seus dados e o login salvo continuam. A atualização instala por cima.",
                color = Muted.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
