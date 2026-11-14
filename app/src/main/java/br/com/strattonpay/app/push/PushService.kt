package br.com.strattonpay.app.push

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
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.com.strattonpay.app.BuildConfig
import br.com.strattonpay.app.MainActivity
import br.com.strattonpay.app.R
import br.com.strattonpay.app.notify.AppSettings
import br.com.strattonpay.app.notify.Notifier
import br.com.strattonpay.app.ui.BrandArgb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Recebe, abre e mostra as notificações que o painel manda. */
object PushInbox {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // o ntfy manda "keepalive" a cada ~45 s
        .retryOnConnectionFailure(true)
        .build()

    private val seen = LinkedHashSet<String>()

    /** Conexão contínua: entrega em segundos. Retorna quando a conexão cai. */
    fun stream(ctx: Context, store: PushStore, isActive: () -> Boolean) {
        val since = store.lastMessageId?.let { "?since=$it" } ?: "?since=10m"
        val request = Request.Builder().url(store.streamUrl + since).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("ntfy ${response.code}")
            val source = response.body?.source() ?: return
            while (isActive() && !source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                handleLine(ctx, store, line)
            }
        }
    }

    /** Busca o que chegou enquanto o app estava fechado (plano B a cada 15 min). */
    fun catchUp(ctx: Context, store: PushStore) {
        val since = store.lastMessageId ?: "1h"
        val request = Request.Builder().url("${store.streamUrl}?poll=1&since=$since").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            response.body?.string().orEmpty().lineSequence().forEach { handleLine(ctx, store, it) }
        }
    }

    /** Teste do canal: manda uma mensagem para o próprio tópico. */
    fun sendTest(store: PushStore) {
        val token = "teste-" + System.currentTimeMillis()
        store.testToken = token
        val request = Request.Builder()
            .url(store.topicUrl)
            .post(token.toRequestBody("text/plain".toMediaType()))
            .build()
        client.newCall(request).execute().use { }
    }

    @Synchronized
    private fun handleLine(ctx: Context, store: PushStore, line: String) {
        if (line.isBlank()) return
        val event = try {
            JSONObject(line)
        } catch (e: Exception) {
            return
        }
        if (event.optString("event") != "message") return
        val id = event.optString("id")
        if (id.isNotBlank()) {
            if (!seen.add(id)) return
            if (seen.size > 200) seen.remove(seen.first())
            store.lastMessageId = id
        }
        val message = event.optString("message")

        if (event.optString("encoding") == "base64") {
            try {
                val body = Base64.decode(message, Base64.DEFAULT)
                val plain = WebPushCrypto.decrypt(body, store.privateKey(), store.publicKey(), store.authSecret())
                show(ctx, plain.toString(Charsets.UTF_8), id)
            } catch (e: Exception) {
                // mensagem que não é do painel (ou chave antiga): ignora
            }
        } else if (message.isNotBlank() && message == store.testToken) {
            Notifier.panel(
                ctx,
                title = "Notificações funcionando",
                body = "O ${BuildConfig.BRAND_NAME} recebe os avisos do painel na hora.",
                url = null,
                id = id,
            )
        }
    }

    /** Lê o conteúdo que o painel mandou (JSON de notificação ou texto). */
    private fun show(ctx: Context, payload: String, id: String) {
        var title = BuildConfig.BRAND_NAME
        var body = payload
        var url: String? = null
        try {
            val o = JSONObject(payload)
            val n = o.optJSONObject("notification") ?: o
            val data = n.optJSONObject("data") ?: o.optJSONObject("data")
            title = n.optString("title").ifBlank { o.optString("title") }.ifBlank { BuildConfig.BRAND_NAME }
            body = listOf("body", "message", "text", "description")
                .map { n.optString(it).ifBlank { o.optString(it) } }
                .firstOrNull { it.isNotBlank() } ?: ""
            url = listOf("url", "link", "href", "click_action")
                .map { n.optString(it).ifBlank { o.optString(it) }.ifBlank { data?.optString(it).orEmpty() } }
                .firstOrNull { it.isNotBlank() }
        } catch (e: Exception) {
            // não é JSON: mostra o texto como veio
        }
        Notifier.panel(ctx, title, body, url, id)
    }
}

/**
 * Mantém a conexão de push aberta. O Android exige um aviso fixo para isso;
 * ele fica no canal "Conexão" com prioridade mínima (some da barra de status).
 */
class PushService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = AppSettings(this)
        val store = PushStore(this)
        if (!settings.notifyEnabled || !store.subscribed) {
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
                var backoff = 2_000L
                while (isActive) {
                    val s = AppSettings(this@PushService)
                    if (!s.notifyEnabled || !store.subscribed) break
                    try {
                        PushInbox.stream(this@PushService, store) { isActive }
                        backoff = 2_000L
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(60_000L)
                    }
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
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CH_CONNECTION, "Conexão de notificações", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Aviso fixo exigido pelo Android para receber as notificações na hora"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(this, CH_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BrandArgb)
            .setContentTitle("${BuildConfig.BRAND_NAME} conectado")
            .setContentText("Pronto para receber os avisos do painel.")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val ONGOING_ID = 4242
        private const val CH_CONNECTION = "connection"
        private const val CATCH_UP = "push_catch_up"

        /** Liga a recepção (conexão contínua + busca a cada 15 min como plano B). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PushService::class.java))
            } catch (e: Exception) {
                // o Android não deixou iniciar agora; a busca periódica cobre até o app ser aberto
            }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                CATCH_UP,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CatchUpWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
            WorkManager.getInstance(context).cancelUniqueWork(CATCH_UP)
        }

        /** Liga se o painel já registrou o celular e as notificações estão ativas. */
        fun ensure(context: Context) {
            if (AppSettings(context).notifyEnabled && PushStore(context).subscribed) start(context)
        }

        suspend fun sendTest(context: Context): Boolean = withContext(Dispatchers.IO) {
            try {
                PushInbox.sendTest(PushStore(context))
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}

class CatchUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = PushStore(applicationContext)
        if (!AppSettings(applicationContext).notifyEnabled || !store.subscribed) return Result.success()
        try {
            PushInbox.catchUp(applicationContext, store)
        } catch (e: Exception) {
            return Result.retry()
        }
        return Result.success()
    }
}

/** Religa a recepção depois que o celular reinicia ou o app é atualizado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        PushService.ensure(context)
    }
}
