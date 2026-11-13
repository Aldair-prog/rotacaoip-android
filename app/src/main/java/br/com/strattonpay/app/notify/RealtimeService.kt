package br.com.strattonpay.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import br.com.strattonpay.app.MainActivity
import br.com.strattonpay.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Monitor em tempo real: consulta a StrattonPay a cada 5 segundos enquanto estiver ligado.
 * O Android exige uma notificação fixa ("Monitorando pagamentos") para o app poder fazer isso.
 */
class RealtimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppSettings(this).intervalSeconds = 60
            NotifyScheduler.restart(this)
            stopSelf()
            return START_NOT_STICKY
        }

        val settings = AppSettings(this)
        if (!settings.notifyEnabled || !settings.isRealtime) {
            stopSelf()
            return START_NOT_STICKY
        }

        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            ongoingNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )

        if (loop?.isActive != true) {
            loop = scope.launch {
                var round = 0
                while (isActive) {
                    val s = AppSettings(this@RealtimeService)
                    if (!s.notifyEnabled || !s.isRealtime) break
                    val api = NotifyChecker.api(this@RealtimeService) ?: break
                    try {
                        // Vendas e saques a cada rodada; contestações (3 consultas) a cada ~1 min.
                        NotifyChecker.check(this@RealtimeService, api, s, includeDisputes = round % 12 == 0)
                    } catch (e: Exception) {
                        // sem internet: tenta de novo na próxima rodada
                    }
                    round++
                    delay(s.intervalSeconds.coerceAtLeast(MIN_SECONDS) * 1000L)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ongoingNotification() = run {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Monitor em tempo real", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Aviso fixo enquanto o app acompanha os pagamentos a cada 5 segundos"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RealtimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7B2FD0.toInt())
            .setContentTitle("Stratton monitorando pagamentos")
            .setContentText("Você é avisado em segundos quando um Pix cair.")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, "Pausar tempo real", stop)
            .build()
    }

    companion object {
        const val MIN_SECONDS = 5
        private const val ONGOING_ID = 4242
        private const val CH_MONITOR = "monitor"
        private const val ACTION_STOP = "br.com.strattonpay.app.STOP_REALTIME"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RealtimeService::class.java))
            } catch (e: Exception) {
                // O Android não deixou iniciar agora (app em segundo plano): checa a cada 1 min
                // até o app ser aberto de novo, quando o tempo real volta sozinho.
                NotifyScheduler.fallbackToMinutes(context)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimeService::class.java))
        }
    }
}

/** Religa as notificações depois que o celular reinicia. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = AppSettings(context)
        if (settings.notifyEnabled && NotifyChecker.api(context) != null) NotifyScheduler.ensure(context)
    }
}
