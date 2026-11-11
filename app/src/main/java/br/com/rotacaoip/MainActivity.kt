package br.com.rotacaoip

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ cores (iguais ao app do Windows)
private val Fundo = Color(0xFF0D0F15)
private val Card = Color(0xFF161922)
private val Card2 = Color(0xFF1C2030)
private val Borda = Color(0xFF282C3A)
private val Texto = Color(0xFFECEEF5)
private val Muted = Color(0xFF82889B)
private val Verde = Color(0xFF40D680)
private val Vermelho = Color(0xFFFF5260)
private val Laranja = Color(0xFFFFA640)
private val Acento = Color(0xFFFF465A)
private val Azul = Color(0xFF5AC8FF)
private val Roxo = Color(0xFFBE78FF)

class MainActivity : ComponentActivity() {

    private var depoisDaPermissao: (() -> Unit)? = null

    private val pedirVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) depoisDaPermissao?.invoke()
        else Engine.registrar(T_ERRO, "—", "", "Permissão de VPN negada. Toque em LIGAR e aceite.")
        depoisDaPermissao = null
    }

    private val pedirNotificacao = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) pedirNotificacao.launch(Manifest.permission.POST_NOTIFICATIONS)

        val desativar = intent?.action == ACAO_DESATIVAR
        if (desativar) Engine.desativarTudo()

        setContent {
            var logado by remember { mutableStateOf(Prefs.logado) }
            Box(Modifier.fillMaxSize().background(Fundo).safeDrawingPadding()) {
                if (logado) TelaPrincipal(this@MainActivity, onSair = { logado = false })
                else TelaLogin(onEntrou = { logado = true; ligarSeAutomatico() })
            }
        }
        if (!desativar) ligarSeAutomatico()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACAO_DESATIVAR) Engine.desativarTudo()
    }

    companion object {
        const val ACAO_DESATIVAR = "br.com.rotacaoip.DESATIVAR"
    }

    private fun ligarSeAutomatico() {
        if (Prefs.logado && Prefs.ligarAoAbrir && !Engine.rodando) comPermissaoVpn { Engine.iniciar() }
    }

    fun comPermissaoVpn(acao: () -> Unit) {
        val intent = VpnService.prepare(this)
        if (intent == null) acao() else {
            depoisDaPermissao = acao
            pedirVpn.launch(intent)
        }
    }

    // ------------------------------------------------------------ atalhos de protecao
    fun abrirVpnSempreAtiva() = abrir(Intent(Settings.ACTION_VPN_SETTINGS))

    @SuppressLint("BatteryLife")
    fun pedirBateriaSemRestricao() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            abrirDetalhesApp()
        } else {
            abrir(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    fun abrirInicioAutomatico() {
        val i = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        if (!abrir(i)) abrirDetalhesApp()
    }

    private fun abrirDetalhesApp() =
        abrir(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    private fun abrir(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) {
        false
    }
}

// ================================================================== componentes

@Composable
private fun CardBox(modifier: Modifier = Modifier, conteudo: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .border(1.dp, Borda, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) { conteudo() }
}

@Composable
private fun Rotulo(t: String, cor: Color = Muted) =
    Text(t, color = cor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)

@Composable
private fun Cabecalho() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(54.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("ROTAÇÃO DE IP", color = Texto, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("Mullvad VPN • troca automática de servidor", color = Muted, fontSize = 12.sp)
        }
    }
}

// ================================================================== login

@Composable
private fun TelaLogin(onEntrou: () -> Unit) {
    var conta by remember { mutableStateOf("") }
    var carregando by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val escopo = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Cabecalho()
        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Rotulo("NÚMERO DA CONTA MULLVAD")
                CampoTexto(conta, { conta = it.filter(Char::isDigit).take(16) }, "16 números", KeyboardType.Number)
                Text(
                    "O app registra uma chave WireGuard na sua conta (usa 1 das 5 vagas de aparelho) " +
                        "e conecta direto nos servidores do Mullvad. O número fica salvo só neste celular.",
                    color = Muted, fontSize = 12.sp,
                )
                if (msg.isNotEmpty()) Text(msg, color = Vermelho, fontSize = 13.sp)
                BotaoGrande(
                    texto = if (carregando) "Conectando ao Mullvad..." else "ENTRAR",
                    cor = if (carregando) Card2 else Verde,
                ) {
                    if (carregando) return@BotaoGrande
                    if (conta.length < 10) { msg = "Digite o número da conta (16 dígitos)."; return@BotaoGrande }
                    carregando = true; msg = ""
                    escopo.launch {
                        val erro = withContext(Dispatchers.IO) {
                            try {
                                val par = com.wireguard.crypto.KeyPair()
                                val end = Mullvad.registrar(conta, par.publicKey.toBase64())
                                val json = Mullvad.baixarRelays()
                                Prefs.conta = conta
                                Prefs.chavePrivada = par.privateKey.toBase64()
                                Prefs.endereco = end
                                Prefs.relaysJson = json
                                Prefs.relaysEm = System.currentTimeMillis()
                                null
                            } catch (e: Exception) {
                                e.message ?: "Erro de conexão"
                            }
                        }
                        carregando = false
                        if (erro == null) onEntrou() else msg = erro
                    }
                }
            }
        }
    }
}

// ================================================================== principal

@Composable
private fun TelaPrincipal(act: MainActivity, onSair: () -> Unit) {
    val e by Engine.estado.collectAsState()
    val v by Engine.vel.collectAsState()
    val hist by Engine.historico.collectAsState()
    val agora by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    var minutosTxt by remember { mutableStateOf(Prefs.minutos.toString()) }
    val auto by Prefs.autoFlow.collectAsState()
    val soRotacao by Prefs.soRotacaoFlow.collectAsState()
    var dialogoSoRotacao by remember { mutableStateOf<Boolean?>(null) } // true = ligando, false = desligando

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cabecalho()

        // ---------------- status
        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row {
                    Column(Modifier.weight(1.3f)) {
                        Rotulo("STATUS")
                        val (st, cor) = when (e.status) {
                            "iniciando" -> "● INICIANDO..." to Laranja
                            "trocando" -> "● TROCANDO SERVIDOR..." to Laranja
                            "rodando" -> "● PROTEGIDO E RODANDO" to Verde
                            "erro" -> "● TENTANDO RECONECTAR" to Vermelho
                            else -> (if (e.vpnLigada) "● VPN CONECTADA" else "● PARADO") to (if (e.vpnLigada) Verde else Muted)
                        }
                        Text(st, color = cor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Rotulo("PRÓXIMA TROCA EM")
                        val prox = when {
                            e.status == "rodando" && e.proxima > 0 -> fmtDur(e.proxima - agora)
                            e.status == "trocando" -> "agora"
                            else -> "—"
                        }
                        Text(prox, color = Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row {
                    Column(Modifier.weight(1.3f)) {
                        Rotulo("LOCAL ATUAL")
                        val local = if (e.pais.isEmpty()) "—" else
                            "${bandeira(e.cc)}  ${if (e.cidade.isNotEmpty()) e.cidade + ", " else ""}${e.pais}"
                        Text(local, color = Texto, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.weight(1f)) {
                        Rotulo("IP ATUAL")
                        Text(e.ip.ifEmpty { "—" }, color = Acento, fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
                Row {
                    val (p, pc) = when {
                        e.ip.isEmpty() -> "Aguardando a primeira troca" to Muted
                        e.protegido -> "✔ IP confirmado como Mullvad" to Verde
                        else -> "⚠ IP não confirmado como Mullvad" to Laranja
                    }
                    Text(p, color = pc, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.3f))
                    Text("TROCAS: ${e.trocas}", color = Muted, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
            }
        }

        // ---------------- botao principal
        BotaoGrande(
            texto = if (e.rodando) "■   DESLIGAR ROTAÇÃO" else "▶   LIGAR ROTAÇÃO",
            cor = if (e.rodando) Color(0xFFDC3246) else Color(0xFF28B464),
            altura = 64,
        ) {
            if (e.rodando) Engine.desconectar() else {
                val m = minutosTxt.toIntOrNull()
                if (m == null || m !in 1..1440) {
                    Engine.registrar(T_ERRO, "—", "", "Digite um intervalo entre 1 e 1440 minutos.")
                } else {
                    Prefs.minutos = m
                    act.comPermissaoVpn { Engine.iniciar() }
                }
            }
        }

        // ---------------- configuracoes
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trocar a cada", color = Texto, fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(70.dp)) {
                CampoTexto(minutosTxt, {
                    minutosTxt = it.filter(Char::isDigit).take(4)
                    minutosTxt.toIntOrNull()?.let { m -> if (m in 1..1440 && !e.rodando) Prefs.minutos = m }
                }, "10", KeyboardType.Number, habilitado = !e.rodando, centro = true)
            }
            Spacer(Modifier.width(8.dp))
            Text("min", color = Texto, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("Ligar ao abrir", color = Texto, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = auto,
                onCheckedChange = { Prefs.ligarAoAbrir = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = Acento,
                    uncheckedThumbColor = Muted, uncheckedTrackColor = Card2, uncheckedBorderColor = Borda,
                ),
            )
        }

        // ---------------- internet so com a rotacao ligada (bloqueio = VPN sempre ativa do Android)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Internet só com a rotação ligada", color = Texto, fontSize = 14.sp)
                Text("sem rotação = sem internet (Wi-Fi e dados)", color = Muted, fontSize = 11.sp)
            }
            Switch(
                checked = soRotacao,
                onCheckedChange = { Prefs.soComRotacao = it; dialogoSoRotacao = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = Acento,
                    uncheckedThumbColor = Muted, uncheckedTrackColor = Card2, uncheckedBorderColor = Borda,
                ),
            )
        }
        dialogoSoRotacao?.let { ligando ->
            AlertDialog(
                onDismissRequest = { dialogoSoRotacao = null },
                containerColor = Card,
                title = {
                    Text(if (ligando) "Ligar o bloqueio" else "Desligar o bloqueio", color = Texto,
                        fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        if (ligando) {
                            "O Android só deixa ligar isso pelas configurações.\n\n" +
                                "Na próxima tela, toque na engrenagem ⚙ ao lado de \"Rotação de IP\" e LIGUE:\n" +
                                "• VPN sempre ativa\n• Bloquear conexões sem VPN\n\n" +
                                "Depois disso, sem a rotação ligada o celular fica sem internet."
                        } else {
                            "Para a internet voltar a funcionar sem a rotação:\n\n" +
                                "Na próxima tela, toque na engrenagem ⚙ ao lado de \"Rotação de IP\" e DESLIGUE:\n" +
                                "• Bloquear conexões sem VPN\n• VPN sempre ativa"
                        },
                        color = Muted, fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { dialogoSoRotacao = null; act.abrirVpnSempreAtiva() }) {
                        Text("Abrir configurações", color = Acento, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialogoSoRotacao = null }) { Text("Depois", color = Muted) }
                },
            )
        }

        // ---------------- velocidade
        CardVelocidade(v)

        // ---------------- protecao
        CardBox {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Rotulo("🛡  DEIXAR O APP SEMPRE VIVO (faça uma vez)")
                Text(
                    "Desativar tudo: segure o ícone do app → \"Desativar tudo\", ou adicione o bloco " +
                        "\"Rotação de IP\" nos atalhos rápidos (puxe a barra de cima → lápis ✎).",
                    color = Muted, fontSize = 12.sp,
                )
                Text("HyperOS: bateria \"Sem restrições\" e \"Início automático\" ligado.", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { BotaoPequeno("Bateria") { act.pedirBateriaSemRestricao() } }
                    Box(Modifier.weight(1f)) { BotaoPequeno("Início automático") { act.abrirInicioAutomatico() } }
                }
            }
        }

        // ---------------- historico
        Rotulo("HISTÓRICO DE TROCAS  (mais recente no topo)")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card)
                .border(1.dp, Borda, RoundedCornerShape(14.dp)),
        ) {
            Row(Modifier.fillMaxWidth().background(Card2).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text("HORA", color = Texto, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(62.dp))
                Text("PAÍS / IP", color = Texto, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("DETALHES", color = Texto, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            if (hist.isEmpty()) {
                Text("Nenhum evento ainda.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
            hist.take(120).forEachIndexed { i, l ->
                val cor = when (l.tipo) { T_OK -> Verde; T_ERRO -> Vermelho; T_TROCA -> Laranja; else -> Muted }
                Row(
                    Modifier.fillMaxWidth().background(if (i % 2 == 0) Card else Color(0xFF1B1F2A))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(l.hora, color = cor, fontSize = 11.sp, modifier = Modifier.width(62.dp))
                    Column(Modifier.weight(1f)) {
                        Text(l.pais, color = cor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (l.ip.isNotEmpty()) Text(l.ip, color = cor, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text(l.detalhe, color = cor, fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
            }
        }

        // ---------------- rodape
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { BotaoPequeno("Desconectar VPN") { Engine.desconectar() } }
            Box(Modifier.weight(1f)) {
                BotaoPequeno("Sair da conta") {
                    Engine.desconectar()
                    Prefs.sair()
                    onSair()
                }
            }
        }
        Text(
            "Conta: •••• ${Prefs.conta.takeLast(4)}   •   versão ${versao(act)}",
            color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
    }
}

private fun versao(act: MainActivity): String = try {
    act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: ""
} catch (_: Exception) { "" }

@Composable
private fun CardVelocidade(v: Vel) {
    CardBox {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Rotulo("⚡  TESTE DE VELOCIDADE")
                Spacer(Modifier.weight(1f))
                val ocupado = Engine.testando
                BotaoPequeno(if (ocupado) "Testando..." else "▶  Testar agora", largura = 130) {
                    if (ocupado) return@BotaoPequeno
                    val st = Engine.estado.value.status
                    if (st == "trocando" || st == "iniciando") {
                        Engine.registrar(T_INFO, "—", "", "Aguarde a troca de servidor terminar para testar.")
                    } else if (!Engine.estado.value.vpnLigada) {
                        Engine.registrar(T_INFO, "—", "", "Ligue a rotação primeiro para testar a velocidade da VPN.")
                    } else Engine.iniciarTeste()
                }
            }
            val (st, stc) = when (v.fase) {
                "ping" -> "Medindo ping..." to Laranja
                "download" -> "Medindo download..." to Laranja
                "upload" -> "Medindo upload..." to Laranja
                "pronto" -> ("Último teste às " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(v.quando)) +
                    (if (v.local.isNotEmpty()) "  •  ${v.local}" else "")) to Muted
                "erro" -> "Falhou: ${v.erro}" to Vermelho
                "cancelado" -> "Interrompido pela troca de servidor" to Muted
                else -> "Testa sozinho após cada troca de IP" to Muted
            }
            Text(st, color = stc, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Bloco(Modifier.weight(1f), "◉ PING", if (v.ping > 0) v.ping.toInt().toString() else "—", "ms",
                    Azul, v.fase == "ping", v.progresso,
                    if (v.jitter > 0 && v.fase != "ping") "jitter ${v.jitter.toInt()} ms" else "")
                Bloco(Modifier.weight(1f), "▼ DOWNLOAD", if (v.down > 0) fmtMbps(v.down) else "—", "Mbps",
                    Verde, v.fase == "download", v.progresso, "")
                Bloco(Modifier.weight(1f), "▲ UPLOAD", if (v.up > 0) fmtMbps(v.up) else "—", "Mbps",
                    Roxo, v.fase == "upload", v.progresso, "")
            }
        }
    }
}

@Composable
private fun Bloco(
    modifier: Modifier, titulo: String, valor: String, unidade: String,
    cor: Color, ativo: Boolean, progresso: Float, extra: String,
) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Card2)
            .border(1.dp, if (ativo) cor else Borda, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Text(titulo, color = cor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(valor, color = Texto, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            if (valor != "—") {
                Spacer(Modifier.width(4.dp))
                Text(unidade, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        if (extra.isNotEmpty()) Text(extra, color = Muted, fontSize = 9.sp)
        if (ativo) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Borda)) {
                Box(Modifier.fillMaxWidth(progresso.coerceIn(0f, 1f)).height(3.dp).background(cor))
            }
        }
    }
}

@Composable
private fun BotaoGrande(texto: String, cor: Color, altura: Int = 54, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(altura.dp).clip(RoundedCornerShape(16.dp)).background(cor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun BotaoPequeno(texto: String, largura: Int = 0, onClick: () -> Unit) {
    val m = if (largura > 0) Modifier.width(largura.dp) else Modifier.fillMaxWidth()
    Box(
        m.height(38.dp).clip(RoundedCornerShape(10.dp)).background(Card2)
            .border(1.dp, Color(0xFF464E64), RoundedCornerShape(10.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, color = Texto, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CampoTexto(
    valor: String, mudar: (String) -> Unit, dica: String, tipo: KeyboardType,
    habilitado: Boolean = true, centro: Boolean = false,
) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E222E))
            .border(1.dp, Borda, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (valor.isEmpty()) {
            Text(dica, color = Muted, fontSize = 16.sp, modifier = Modifier.fillMaxWidth(),
                textAlign = if (centro) TextAlign.Center else TextAlign.Start)
        }
        BasicTextField(
            value = valor,
            onValueChange = mudar,
            enabled = habilitado,
            singleLine = true,
            textStyle = TextStyle(
                color = if (habilitado) Texto else Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                textAlign = if (centro) TextAlign.Center else TextAlign.Start,
            ),
            cursorBrush = SolidColor(Acento),
            keyboardOptions = KeyboardOptions(keyboardType = tipo),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun fmtDur(ms: Long): String {
    val t = (if (ms < 0) 0L else ms) / 1000
    return if (t >= 3600) String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60)
    else String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
}
