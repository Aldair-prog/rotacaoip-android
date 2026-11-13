package br.com.strattonpay.app.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.com.strattonpay.app.data.CredentialStore
import br.com.strattonpay.app.data.StrattonApi
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Compara o estado atual da StrattonPay com o que já foi avisado e dispara as notificações:
 * vendas pagas, saques pagos/recusados e contestações.
 * Usado tanto pelo monitor em tempo real (5 s) quanto pela checagem por minutos.
 */
object NotifyChecker {

    fun api(ctx: Context): StrattonApi? {
        val creds = try {
            CredentialStore(ctx).load()
        } catch (e: Exception) {
            null
        } ?: return null
        return StrattonApi(creds.baseUrl, creds.clientId, creds.clientSecret)
    }

    suspend fun check(ctx: Context, api: StrattonApi, settings: AppSettings, includeDisputes: Boolean = true) {
        val firstRun = !settings.initialized

        if (settings.notifySales) runSafely {
            val page = api.transactions(cursor = null, status = "APPROVED", perPage = 30)
            val last = parse(settings.lastPaidAt)
            val fresh = page.items
                .filter { t -> parse(t.paidAt)?.let { p -> last == null || p.isAfter(last) } == true }
                .sortedBy { it.paidAt }
            if (!firstRun && last != null && fresh.isNotEmpty()) {
                if (fresh.size <= 4) {
                    fresh.forEach { Notifier.sale(ctx, it) }
                } else {
                    Notifier.salesSummary(ctx, fresh.size, fresh.sumOf { it.amount })
                }
            }
            val newest = page.items.mapNotNull { parse(it.paidAt) }.maxOrNull()
            if (newest != null && (last == null || newest.isAfter(last))) settings.lastPaidAt = newest.toString()
        }

        if (settings.notifyWithdraws) runSafely {
            val page = api.withdraws(cursor = null, perPage = 30)
            val waiting = settings.pendingWithdraws
            if (!firstRun) {
                page.data
                    .filter { it.id in waiting && (it.status == "COMPLETED" || it.status == "REFUSED") }
                    .forEach { Notifier.withdraw(ctx, it) }
            }
            settings.pendingWithdraws = page.data
                .filter { it.status == "PENDING_ANALYSIS" || it.status == "APPROVED" }
                .map { it.id }
                .toSet()
        }

        if (settings.notifyDisputes && includeDisputes) runSafely {
            val seen = settings.seenDisputes
            val found = listOf("IN_PROTEST", "PRE_CHARGEBACK", "CHARGEBACK").flatMap { status ->
                api.transactions(cursor = null, status = status, perPage = 10).items
            }
            if (!firstRun) {
                found.filter { "${it.id}:${it.status}" !in seen }.take(5).forEach { Notifier.dispute(ctx, it) }
            }
            settings.seenDisputes = (seen + found.map { "${it.id}:${it.status}" }).toList().takeLast(300).toSet()
        }

        settings.initialized = true
    }

    /** Uma parte falhar (ex.: chave sem o escopo) não impede as outras. */
    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ignora esta parte nesta rodada
        }
    }

    private fun parse(iso: String?): Instant? = try {
        if (iso.isNullOrBlank()) null else Instant.parse(iso)
    } catch (e: Exception) {
        null
    }
}

/** Checagem econômica (a cada X minutos), feita pelo WorkManager. */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = AppSettings(ctx)
        if (!settings.notifyEnabled) return Result.success()
        val api = NotifyChecker.api(ctx) ?: return Result.success() // sem chave: para de checar

        try {
            NotifyChecker.check(ctx, api, settings)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // sem internet ou API fora: tenta de novo na próxima rodada
        } finally {
            NotifyScheduler.next(ctx, if (settings.isRealtime) 1 else settings.intervalSeconds / 60)
        }
        return Result.success()
    }
}

/** Liga, desliga e escolhe o modo das notificações. */
object NotifyScheduler {
    private const val WORK_NAME = "stratton_notify"

    private fun request(delayMinutes: Int): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<NotifyWorker>()
            .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

    /** Liga (ou reinicia) no modo escolhido. A primeira rodada só marca o ponto de partida. */
    fun restart(context: Context) {
        val settings = AppSettings(context)
        if (settings.isRealtime) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            RealtimeService.start(context)
        } else {
            RealtimeService.stop(context)
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request(0))
        }
    }

    /** Garante que está rodando (ao abrir o app ou ligar o celular), sem reiniciar à toa. */
    fun ensure(context: Context) {
        val settings = AppSettings(context)
        if (settings.isRealtime) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            RealtimeService.start(context)
        } else {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request(0))
        }
    }

    /** Plano B quando o monitor de 5 s não pode iniciar em segundo plano. */
    fun fallbackToMinutes(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request(1))
    }

    fun next(context: Context, minutes: Int) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request(minutes.coerceAtLeast(1)))
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        RealtimeService.stop(context)
    }
}
