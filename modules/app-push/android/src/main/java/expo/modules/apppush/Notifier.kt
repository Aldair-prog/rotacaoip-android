package expo.modules.apppush

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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Mostra os avisos do painel: valor do Pix grande, na cor da marca, com a logo e o som do app. */
object Notifier {

    // O som de um canal não muda depois de criado: canal com o nome do som
    private fun channelId(cfg: PushConfig) = "avisos_" + (cfg.sound.ifBlank { "padrao" })
    private const val GROUP = "panel_group"
    private const val GROUP_SUMMARY_ID = 7001
    private val VIBRATION = longArrayOf(0, 60, 70, 140)

    /** "R$ 1.250,00" ou "R$ 10" dentro do texto. */
    private val MONEY = Regex("""R\$\s?-?\d{1,3}(?:\.\d{3})*(?:,\d{2})?|R\$\s?-?\d+(?:,\d{2})?""")

    @Volatile
    private var largeIcon: Bitmap? = null

    fun smallIcon(context: Context): Int {
        val id = context.resources.getIdentifier("notification_icon", "drawable", context.packageName)
        return if (id != 0) id else context.applicationInfo.icon
    }

    fun openIntent(context: Context, requestCode: Int): PendingIntent {
        val cfg = PushConfig(context)
        val intent = if (cfg.scheme.isNotBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse("${cfg.scheme}://avisos")).setPackage(context.packageName)
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context, cfg: PushConfig): String {
        val id = channelId(cfg)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return id
        if (nm.getNotificationChannel(id) != null) return id
        val ch = NotificationChannel(id, "Avisos do painel", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Pix recebido, saques e demais avisos da sua conta"
            val raw = if (cfg.sound.isBlank()) 0 else context.resources.getIdentifier(cfg.sound, "raw", context.packageName)
            if (raw != 0) {
                setSound(
                    Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$raw"),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            enableVibration(true)
            vibrationPattern = VIBRATION
            enableLights(true)
            lightColor = cfg.color
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
        return id
    }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean =
        hasPermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Ícone do app num círculo escuro com brilho na cor da marca. */
    private fun logo(context: Context, color: Int): Bitmap? {
        largeIcon?.let { return it }
        return try {
            val size = 192
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    size / 2f, size / 2f, size / 2f,
                    intArrayOf((color and 0x00FFFFFF) or 0x66000000, Color.BLACK),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            c.drawCircle(size / 2f, size / 2f, size / 2f, glow)
            val inset = (size * 0.12f).toInt()
            icon.setBounds(inset, inset, size - inset, size - inset)
            icon.draw(c)
            out.also { largeIcon = it }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, title: String, body: String, id: String?) {
        if (!canNotify(context)) {
            android.util.Log.w("AppPush", "notificações bloqueadas no Android")
            return
        }
        val cfg = PushConfig(context)
        val channel = ensureChannel(context, cfg)
        val notifId = (id ?: (title + body + System.currentTimeMillis())).hashCode()

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(smallIcon(context))
            .setColor(cfg.color)
            .setLargeIcon(logo(context, cfg.color))
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVibrate(VIBRATION)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setGroup(GROUP)
            .setContentIntent(openIntent(context, notifId))

        // Tem valor em dinheiro? Mostra o valor grande, na cor da marca.
        val amount = MONEY.find(title)?.value ?: MONEY.find(body)?.value
        if (amount != null) {
            val label = title.replace(amount, "").trim(' ', '-', '·', ':', '|').ifBlank { "Pix recebido" }
            val rest = body.replace(amount, "").trim(' ', '-', '·', ':', '|')
                .replace(Regex("^(de|do|da)\\s+", RegexOption.IGNORE_CASE), "De ")
            val view = RemoteViews(context.packageName, R.layout.notification_pix).apply {
                setTextViewText(R.id.n_label, label)
                setTextViewText(R.id.n_amount, "R$ " + amount.substringAfter("R$").trim())
                setTextColor(R.id.n_amount, cfg.color)
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
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(smallIcon(context))
                    .setColor(cfg.color)
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                    .setAutoCancel(true)
                    .build(),
            )
            android.util.Log.i("AppPush", "notificação mostrada")
        } catch (e: SecurityException) {
            android.util.Log.w("AppPush", "sem permissão para notificar", e)
        }
    }
}
