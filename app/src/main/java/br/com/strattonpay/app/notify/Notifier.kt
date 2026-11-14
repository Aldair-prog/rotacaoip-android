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
import br.com.strattonpay.app.ui.BrandArgb

object Notifier {

    /** Endereço do painel para abrir ao tocar na notificação. */
    const val EXTRA_URL = "open_url"

    private const val CH_PANEL = "panel"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_PANEL, "Avisos do painel", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pix pago, saques e demais avisos enviados pelo painel"
            },
        )
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean =
        hasPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    fun panel(context: Context, title: String, body: String, url: String?, id: String?) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val notifId = (id ?: (title + body + System.currentTimeMillis())).hashCode()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!url.isNullOrBlank()) putExtra(EXTRA_URL, url)
        }
        val pending = PendingIntent.getActivity(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CH_PANEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BrandArgb)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            // permissão retirada entre a checagem e o envio
        }
    }
}
