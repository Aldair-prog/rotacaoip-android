package br.com.strattonpay.app.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import br.com.strattonpay.app.BuildConfig
import br.com.strattonpay.app.MainActivity
import br.com.strattonpay.app.R
import br.com.strattonpay.app.ui.BrandArgb

object Notifier {

    /** Endereço do painel para abrir ao tocar na notificação. */
    const val EXTRA_URL = "open_url"

    // Canal novo (v2): canal com som próprio não pode ser alterado depois de criado.
    private const val CH_PANEL = "panel_v2"
    private const val GROUP = "panel_group"
    private val VIBRATION = longArrayOf(0, 60, 70, 140)

    /** "R$ 1.250,00" ou "R$ 10" dentro do texto. */
    private val MONEY = Regex("""R\$\s?-?\d{1,3}(?:\.\d{3})*(?:,\d{2})?|R\$\s?-?\d+(?:,\d{2})?""")

    @Volatile
    private var largeIcon: Bitmap? = null

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel("panel") // canal antigo, sem som próprio
        if (nm.getNotificationChannel(CH_PANEL) != null) return
        val sound = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.pix_chime}")
        nm.createNotificationChannel(
            NotificationChannel(CH_PANEL, "Avisos do painel", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Pix pago, saques e demais avisos enviados pelo painel"
                setSound(
                    sound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = VIBRATION
                enableLights(true)
                lightColor = BrandArgb
                setShowBadge(true)
            },
        )
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean =
        hasPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Logo da marca num círculo escuro com brilho (aparece grande na notificação). */
    private fun logo(context: Context): Bitmap? {
        largeIcon?.let { return it }
        return try {
            val size = 192
            val src = BitmapFactory.decodeResource(context.resources, R.drawable.logo_emblem) ?: return null
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    size / 2f, size / 2f, size / 2f,
                    intArrayOf((BrandArgb and 0x00FFFFFF) or 0x66000000, Color.BLACK),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            c.drawCircle(size / 2f, size / 2f, size / 2f, glow)
            val w = size * 0.66f
            val h = w * src.height / src.width
            val left = (size - w) / 2f
            val top = (size - h) / 2f
            c.drawBitmap(src, null, android.graphics.RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            out.also { largeIcon = it }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun panel(context: Context, title: String, body: String, url: String?, id: String?) {
        if (!canNotify(context)) {
            android.util.Log.w("AppPush", "notificações bloqueadas no Android")
            return
        }
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

        val builder = NotificationCompat.Builder(context, CH_PANEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BrandArgb)
            .setLargeIcon(logo(context))
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(BuildConfig.BRAND_NAME)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVibrate(VIBRATION)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setGroup(GROUP)
            .setContentIntent(pending)

        // Tem valor em dinheiro? Mostra o valor grande, na cor da marca.
        val amount = MONEY.find(title)?.value ?: MONEY.find(body)?.value
        if (amount != null) {
            val label = title.replace(amount, "").trim(' ', '-', '·', ':', '|').ifBlank { "Pix recebido" }
            val rest = body.replace(amount, "").trim(' ', '-', '·', ':', '|').replace(Regex("^(de|do|da)\\s+", RegexOption.IGNORE_CASE), "De ")
            val view = RemoteViews(context.packageName, R.layout.notification_pix).apply {
                setTextViewText(R.id.n_label, label)
                setTextViewText(R.id.n_amount, "R$ " + amount.substringAfter("R$").trim())
                setTextViewText(R.id.n_body, rest)
            }
            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(view)
                .setCustomBigContentView(view)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        try {
            val nm = NotificationManagerCompat.from(context)
            nm.notify(notifId, builder.build())
            // Resumo do grupo: várias vendas ficam juntas numa pilha só.
            nm.notify(
                GROUP_SUMMARY_ID,
                NotificationCompat.Builder(context, CH_PANEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(BrandArgb)
                    .setSubText(BuildConfig.BRAND_NAME)
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: SecurityException) {
            android.util.Log.w("AppPush", "sem permissão para notificar", e)
        }
    }

    private const val GROUP_SUMMARY_ID = 7001
}
