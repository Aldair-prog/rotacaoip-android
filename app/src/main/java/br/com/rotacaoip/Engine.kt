package br.com.rotacaoip

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class Estado(
    val rodando: Boolean = false,
    val status: String = "parado", // parado, iniciando, trocando, rodando, erro
    val cc: String = "",
    val pais: String = "",
    val cidade: String = "",
    val ip: String = "",
    val protegido: Boolean = false,
    val trocas: Int = 0,
    val proxima: Long = 0L,
    val vpnLigada: Boolean = false,
)

data class Linha(val tipo: Int, val hora: String, val pais: String, val ip: String, val detalhe: String)

data class Vel(
    val fase: String = "", // "", ping, download, upload, pronto, erro, cancelado
    val ping: Double = 0.0,
    val jitter: Double = 0.0,
    val down: Double = 0.0,
    val up: Double = 0.0,
    val progresso: Float = 0f,
    val quando: Long = 0L,
    val local: String = "",
    val erro: String = "",
)

const val T_INFO = 0
const val T_OK = 1
const val T_ERRO = 2
const val T_TROCA = 3

object Engine {
    private lateinit var ctx: Context
    private lateinit var backend: GoBackend

    private val tunel = object : Tunnel {
        override fun getName(): String = "rotacaoip"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN) estado.update { it.copy(vpnLigada = false) }
        }
    }

    val estado = MutableStateFlow(Estado())
    val historico = MutableStateFlow<List<Linha>>(emptyList())
    val vel = MutableStateFlow(Vel())

    private val tratador = CoroutineExceptionHandler { _, e ->
        registrar(T_ERRO, "—", "", "Erro interno recuperado: ${e.message ?: e.javaClass.simpleName}")
    }
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO + tratador)
    private var rotacao: Job? = null
    private var teste: Job? = null
    private var iniciado = false

    val rodando: Boolean get() = rotacao?.isActive == true

    fun init(c: Context) {
        if (iniciado) return
        iniciado = true
        ctx = c.applicationContext
        backend = GoBackend(ctx)
        // "VPN sempre ativa" do Android: quando o sistema liga a VPN (ex.: no boot), inicia a rotacao.
        GoBackend.setAlwaysOnCallback { if (Prefs.logado) iniciar() }
    }

    fun registrar(tipo: Int, pais: String, ip: String, detalhe: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        historico.update { (listOf(Linha(tipo, hora, pais, ip, detalhe)) + it).take(300) }
    }

    private fun info(msg: String) = registrar(T_INFO, "—", "", msg)
    private fun erro(msg: String) = registrar(T_ERRO, "—", "", msg)

    // ---------------------------------------------------------------- controle

    @Synchronized
    fun iniciar() {
        if (rodando || !Prefs.logado) return
        Prefs.rodando = true
        RotationService.iniciar(ctx)
        estado.update { it.copy(rodando = true, status = "iniciando", proxima = 0) }
        info("Rotação ligada: a cada ${Prefs.minutos} min.")
        val job = escopo.launch { protegerLoop() }
        rotacao = job
    }

    @Synchronized
    fun parar() {
        rotacao?.cancel()
        rotacao = null
        Prefs.rodando = false
        estado.update { it.copy(rodando = false, status = "parado", proxima = 0) }
        info("Rotação desligada.")
        RotationService.parar(ctx)
    }

    fun desconectar() {
        parar()
        teste?.cancel()
        escopo.launch {
            try {
                backend.setState(tunel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
            }
            estado.update { Estado(trocas = it.trocas) }
            info("VPN desconectada.")
        }
    }

    /** Desativa por completo (igual ao parar-rotacao do Windows): rotacao, VPN e "Ligar ao abrir". */
    fun desativarTudo() {
        Prefs.ligarAoAbrir = false
        desconectar()
        info("Desativado por completo: rotação, VPN e \"Ligar ao abrir\" desligados.")
        if (Prefs.soComRotacao) {
            erro("\"Internet só com a rotação\" está ativo: sem rotação o celular fica sem internet. " +
                "Para liberar, desmarque a opção no app.")
        }
    }

    /** Vigia da VPN: true se o tunel caiu ou parou de responder (sem handshake WireGuard ha > 200 s). */
    private fun vpnCaiu(): Boolean = try {
        if (backend.getState(tunel) != Tunnel.State.UP) true else {
            val st = backend.getStatistics(tunel)
            var ultimo = 0L
            for (k in st.peers()) {
                val p = st.peer(k)
                if (p != null && p.latestHandshakeEpochMillis() > ultimo) ultimo = p.latestHandshakeEpochMillis()
            }
            ultimo > 0 && System.currentTimeMillis() - ultimo > 200_000L
        }
    } catch (_: Exception) {
        false
    }

    /** Envolve o loop: qualquer erro inesperado reinicia a rotacao em 5 s, nunca derruba o app. */
    private suspend fun protegerLoop() {
        while (true) {
            try {
                loop()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                erro("Erro interno recuperado (${e.message ?: e.javaClass.simpleName}). Reiniciando...")
                delay(5000)
            }
        }
    }

    // ---------------------------------------------------------------- rotacao

    private suspend fun loop() {
        estado.update { it.copy(status = "iniciando") }
        val chave = KeyPair(Key.fromBase64(Prefs.chavePrivada))

        var relays = Mullvad.lerRelays(Prefs.relaysJson)
        while (relays.isEmpty()) {
            try {
                val json = Mullvad.baixarRelays()
                Prefs.relaysJson = json
                Prefs.relaysEm = System.currentTimeMillis()
                relays = Mullvad.lerRelays(json)
            } catch (e: Exception) {
                erro("Sem lista de servidores (${e.message}). Tentando de novo em 30 s.")
                estado.update { it.copy(status = "erro") }
                delay(30_000)
            }
        }

        var porPais = relays.filter { it.cc != "br" }.groupBy { it.cc }
        info("${porPais.size} países carregados (sem o Brasil).")
        var ultimo = ""

        while (true) {
            var conectou = false
            for (tentativa in 1..5) {
                val codigos = porPais.keys.toList()
                var cc = codigos.random()
                if (codigos.size > 1) while (cc == ultimo) cc = codigos.random()
                ultimo = cc
                if (cc == "br") continue // nunca Brasil
                val relay = porPais.getValue(cc).random()
                val nome = "${bandeira(cc)} ${relay.pais}"

                teste?.cancel() // nunca testa velocidade durante a troca
                estado.update { it.copy(status = "trocando", proxima = 0) }
                registrar(T_TROCA, nome, "", "Trocando de servidor...")

                val ok = try {
                    conectar(chave, relay)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message ?: e.javaClass.simpleName
                    if ("VPN_NOT_AUTHORIZED" in msg || "not authorized" in msg.lowercase()) {
                        erro("Permissão de VPN não concedida. Abra o app e toque em LIGAR.")
                        estado.update { it.copy(status = "erro") }
                        parar()
                        return
                    }
                    registrar(T_ERRO, nome, "", "Falha: $msg")
                    false
                }
                if (!ok) {
                    registrar(T_ERRO, nome, "", "Não conectou (tentativa $tentativa/5). Tentando outro país...")
                    continue
                }
                conectou = true
                estado.update { it.copy(vpnLigada = true) }

                var info: IpInfo? = null
                for (t in 1..5) {
                    try {
                        info = Mullvad.ipAtual(); break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        delay(3000)
                    }
                }
                if (info == null) {
                    registrar(T_OK, nome, "?", "${relay.cidade} • conectado (IP não verificado)")
                    estado.update {
                        it.copy(cc = cc, pais = relay.pais, cidade = relay.cidade, ip = "não verificado",
                            protegido = false, trocas = it.trocas + 1)
                    }
                } else {
                    val ccReal = if (info.host.length > 2 && info.host[2] == '-') info.host.substring(0, 2) else cc
                    if (info.mullvad) {
                        registrar(T_OK, "${bandeira(ccReal)} ${info.pais}", info.ip, "${info.cidade} • protegido")
                    } else {
                        registrar(T_ERRO, nome, info.ip, "ATENÇÃO: não é IP do Mullvad!")
                    }
                    estado.update {
                        it.copy(cc = ccReal, pais = info.pais, cidade = info.cidade, ip = info.ip,
                            protegido = info.mullvad, trocas = it.trocas + 1)
                    }
                }
                break
            }

            if (!conectou) {
                erro("Nenhum país conectou em 5 tentativas. Tentando de novo em 30 s.")
                estado.update { it.copy(status = "erro", proxima = 0) }
                delay(30_000)
                continue
            }

            // Atualiza a lista de servidores a cada 6 h (pela propria VPN).
            if (System.currentTimeMillis() - Prefs.relaysEm > 6 * 3600_000L) {
                try {
                    val json = Mullvad.baixarRelays()
                    Prefs.relaysJson = json
                    Prefs.relaysEm = System.currentTimeMillis()
                    val novos = Mullvad.lerRelays(json).filter { it.cc != "br" }.groupBy { it.cc }
                    if (novos.isNotEmpty()) porPais = novos
                } catch (_: Exception) {
                }
            }

            val min = Prefs.minutos.coerceIn(1, 1440)
            estado.update { it.copy(status = "rodando", proxima = System.currentTimeMillis() + min * 60_000L) }
            iniciarTeste()
            // Espera ate a proxima troca vigiando a VPN a cada 15 s: se cair, reconecta na hora.
            val fim = System.currentTimeMillis() + min * 60_000L
            while (System.currentTimeMillis() < fim) {
                delay(minOf(15_000L, maxOf(1L, fim - System.currentTimeMillis())))
                if (vpnCaiu()) {
                    teste?.cancel()
                    estado.update { it.copy(vpnLigada = false, status = "erro", proxima = 0) }
                    erro("A VPN caiu! Reconectando agora.")
                    break
                }
            }
        }
    }

    /** Sobe o tunel no servidor escolhido e espera o handshake WireGuard confirmar a conexao. */
    private suspend fun conectar(chave: KeyPair, relay: Relay): Boolean = withContext(Dispatchers.IO) {
        val texto = """
            [Interface]
            PrivateKey = ${chave.privateKey.toBase64()}
            Address = ${Prefs.endereco}
            DNS = 10.64.0.1

            [Peer]
            PublicKey = ${relay.chavePublica}
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = ${relay.ipv4}:51820
            PersistentKeepalive = 25
        """.trimIndent()
        val config = Config.parse(texto.byteInputStream())
        backend.setState(tunel, Tunnel.State.UP, config)
        repeat(30) { // ate ~15 s
            delay(500)
            val stats = backend.getStatistics(tunel)
            for (k in stats.peers()) {
                val p = stats.peer(k)
                if (p != null && p.latestHandshakeEpochMillis() > 0) return@withContext true
            }
        }
        false
    }

    // ---------------------------------------------------------------- teste de velocidade

    fun iniciarTeste() {
        teste?.cancel()
        val e = estado.value
        val local = if (e.cidade.isNotEmpty()) "${e.cidade}, ${e.pais}" else e.pais
        teste = escopo.launch {
            try {
                rodarTeste(local)
            } catch (c: CancellationException) {
                vel.update { it.copy(fase = "cancelado", progresso = 0f) }
                throw c
            } catch (ex: Exception) {
                vel.update { it.copy(fase = "erro", erro = (ex.message ?: "falhou").take(60), progresso = 0f) }
            }
        }
    }

    val testando: Boolean
        get() = vel.value.fase in listOf("ping", "download", "upload")

    private suspend fun rodarTeste(local: String) {
        delay(3000) // rede estabilizar depois da troca
        vel.value = Vel(fase = "ping", local = local)

        // PING: tempo de abrir uma conexao TCP (1 ida e volta)
        val endereco = withContext(Dispatchers.IO) { InetAddress.getByName("speed.cloudflare.com") }
        val amostras = mutableListOf<Double>()
        for (i in 0..8) {
            val ms = withContext(Dispatchers.IO) {
                val t0 = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress(endereco, 443), 5000) }
                (System.nanoTime() - t0) / 1_000_000.0
            }
            if (i > 0) amostras.add(ms)
            vel.update { it.copy(progresso = i / 8f) }
            delay(150)
        }
        amostras.sort()
        val ping = amostras[amostras.size / 2]
        var jit = 0.0
        for (i in 1 until amostras.size) jit += kotlin.math.abs(amostras[i] - amostras[i - 1])
        jit /= (amostras.size - 1)
        vel.update { it.copy(ping = ping, jitter = jit, fase = "download", progresso = 0f) }

        // DOWNLOAD
        val down = medir({ mbps, p -> vel.update { it.copy(down = mbps, progresso = p) } }) { cont, limite ->
            val c = URL("$BASE/__down?bytes=50000000").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.useCaches = false
            try {
                c.inputStream.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    while (System.currentTimeMillis() < limite) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        cont.addAndGet(n.toLong())
                    }
                }
            } finally {
                c.disconnect()
            }
        }
        vel.update { it.copy(down = down, fase = "upload", progresso = 0f) }

        // UPLOAD
        val bloco = ByteArray(64 * 1024).also { java.util.Random().nextBytes(it) }
        val up = medir({ mbps, p -> vel.update { it.copy(up = mbps, progresso = p) } }) { cont, limite ->
            val total = 25L * 1024 * 1024
            val c = URL("$BASE/__up").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000; c.useCaches = false
            c.requestMethod = "POST"; c.doOutput = true
            c.setFixedLengthStreamingMode(total)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            try {
                var enviado = 0L
                c.outputStream.use { out ->
                    while (enviado < total && System.currentTimeMillis() < limite) {
                        val n = minOf(bloco.size.toLong(), total - enviado).toInt()
                        out.write(bloco, 0, n)
                        enviado += n
                        cont.addAndGet(n.toLong())
                    }
                }
                if (enviado >= total) c.responseCode
            } finally {
                c.disconnect()
            }
        }
        vel.update { it.copy(up = up, fase = "pronto", progresso = 1f, quando = System.currentTimeMillis()) }
        info("Velocidade: ping ${ping.toInt()} ms • ↓ ${fmtMbps(down)} • ↑ ${fmtMbps(up)} Mbps")
    }

    /** Roda 4 conexoes em paralelo por 6 s e devolve a media em Mbps. */
    private suspend fun medir(
        aoVivo: (Double, Float) -> Unit,
        tarefa: (AtomicLong, Long) -> Unit,
    ): Double = coroutineScope {
        val bytes = AtomicLong()
        val inicio = System.currentTimeMillis()
        val limite = inicio + DURACAO
        val trabalhos = (1..4).map {
            launch(Dispatchers.IO) {
                while (isActive && System.currentTimeMillis() < limite) {
                    try {
                        tarefa(bytes, limite)
                    } catch (_: Exception) {
                        if (System.currentTimeMillis() < limite) delay(300)
                    }
                }
            }
        }
        while (trabalhos.any { it.isActive }) {
            val el = (System.currentTimeMillis() - inicio) / 1000.0
            if (el > 0) aoVivo(bytes.get() * 8 / el / 1e6, (el * 1000 / DURACAO).toFloat().coerceAtMost(1f))
            delay(250)
        }
        val el = (System.currentTimeMillis() - inicio) / 1000.0
        if (bytes.get() == 0L) throw Exception("nenhum dado transferido")
        bytes.get() * 8 / el / 1e6
    }

    private const val BASE = "https://speed.cloudflare.com"
    private const val DURACAO = 6000L
}

fun fmtMbps(v: Double): String = if (v < 10) String.format(Locale.US, "%.1f", v) else v.toInt().toString()
