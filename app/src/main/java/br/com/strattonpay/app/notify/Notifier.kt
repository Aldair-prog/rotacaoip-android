package br.com.strattonpay.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import br.com.strattonpay.app.MainActivity
import br.com.strattonpay.app.R
import br.com.strattonpay.app.data.Transaction
import br.com.strattonpay.app.data.Withdraw
import br.com.strattonpay.app.ui.money

object Notifier {

    const val EXTRA_ROUTE = "open_route"

    private const val CH_SALES = "sales"
    private const val CH_WITHDRAWS = "withdraws"
    private const val CH_ALERTS = "alerts"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SALES, "Vendas pagas", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Aviso quando um Pix é pago"
                },
                NotificationChannel(CH_WITHDRAWS, "Saques", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Saques pagos ou recusados"
                },
                NotificationChannel(CH_ALERTS, "Contestações e chargebacks", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Disputas abertas pelo pagador"
                },
            ),
        )
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean =
        hasPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun sale(context: Context, t: Transaction) {
        val who = t.customer?.name?.takeIf { it.isNotBlank() }
        post(
            context, CH_SALES, t.id.hashCode(),
            title = "Pix recebido · ${money(t.amount)}",
            text = if (who != null) "$who pagou ${money(t.amount)}." else "Uma cobrança de ${money(t.amount)} foi paga.",
            route = "tx/${t.id}",
        )
    }

    fun salesSummary(context: Context, count: Int, total: Long) {
        post(
            context, CH_SALES, 1001,
            title = "$count vendas pagas · ${money(total)}",
            text = "Abra o app para ver os detalhes.",
            route = "transactions",
        )
    }

    fun withdraw(context: Context, w: Withdraw) {
        val paid = w.status == "COMPLETED"
        post(
            context, CH_WITHDRAWS, w.id.hashCode(),
            title = if (paid) "Saque pago · ${money(w.netAmount)}" else "Saque recusado · ${money(w.amount)}",
            text = if (paid) {
                "O dinheiro foi enviado" + (w.pixKey?.let { " para $it" } ?: "") + "."
            } else {
                "O valor voltou para o saldo." + (w.adminMessage?.let { " Motivo: $it" } ?: "")
            },
            route = "wd/${w.id}",
        )
    }

    fun dispute(context: Context, t: Transaction) {
        val label = when (t.status) {
            "IN_PROTEST" -> "Contestação aberta"
            "PRE_CHARGEBACK" -> "Pré-chargeback"
            "CHARGEBACK" -> "Chargeback confirmado"
            else -> "Alerta na venda"
        }
        post(
            context, CH_ALERTS, (t.id + t.status).hashCode(),
            title = "$label · ${money(t.amount)}",
            text = (t.customer?.name?.let { "Pagador: $it. " } ?: "") + "Toque para ver a venda.",
            route = "tx/${t.id}",
        )
    }

    fun test(context: Context) {
        post(
            context, CH_SALES, 999,
            title = "Notificações ativadas",
            text = "É assim que você vai saber quando um Pix cair.",
            route = null,
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, channel: String, id: Int, title: String, text: String, route: String?) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (route != null) putExtra(EXTRA_ROUTE, route)
        }
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7B2FD0.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // permissão retirada entre a checagem e o envio
        }
    }
}
