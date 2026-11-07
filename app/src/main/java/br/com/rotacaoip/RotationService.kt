package br.com.rotacaoip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Servico em primeiro plano: mantem a rotacao viva com o app fechado e mostra a notificacao. */
class RotationService : Service() {
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observador: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        iniciarPrimeiroPlano(montar(Engine.estado.value))
        if (observador == null) {
            observador = escopo.launch {
                Engine.estado.collectLatest { e ->
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(ID, montar(e))
                }
            }
        }
        // Se o sistema matou o app e recriou o servico, retoma a rotacao.
        if (Prefs.rodando && Prefs.logado && !Engine.rodando && VpnService.prepare(this) == null) {
            Engine.iniciar()
        }
        return START_STICKY
    }

    private fun iniciarPrimeiroPlano(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
    }

    private fun montar(e: Estado): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val titulo = when (e.status) {
            "trocando" -> "Trocando de servidor..."
            "iniciando" -> "Iniciando VPN..."
            "erro" -> "Tentando reconectar..."
            "rodando" -> "Protegido • troca a cada ${Prefs.minutos} min"
            else -> "VPN conectada"
        }
        val local = if (e.pais.isNotEmpty()) {
            "${bandeira(e.cc)} ${if (e.cidade.isNotEmpty()) e.cidade + ", " else ""}${e.pais}  •  ${e.ip}"
        } else "Rotação de IP"
        return Notification.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titulo)
            .setContentText(local)
            .setContentIntent(abrir)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    override fun onDestroy() {
        escopo.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        const val CANAL = "rotacao"
        const val ID = 7

        fun criarCanal(c: Context) {
            val ch = NotificationChannel(CANAL, "Rotação de IP", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        fun iniciar(c: Context) {
            try {
                c.startForegroundService(Intent(c, RotationService::class.java))
            } catch (_: Exception) {
                // Algumas versoes bloqueiam iniciar em segundo plano; a rotacao segue mesmo assim.
            }
        }

        fun parar(c: Context) {
            try {
                c.stopService(Intent(c, RotationService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}

/** Ao ligar o celular: retoma a rotacao se estava ligada (ou se "Ligar ao abrir" estiver ativo). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.logado) return
        if (!(Prefs.rodando || Prefs.ligarAoAbrir)) return
        if (VpnService.prepare(context) != null) return // sem permissao de VPN ainda
        Engine.iniciar()
    }
}
