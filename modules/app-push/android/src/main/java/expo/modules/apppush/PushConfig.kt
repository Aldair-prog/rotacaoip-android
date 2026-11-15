package expo.modules.apppush

import android.content.Context
import android.graphics.Color

/**
 * Dados da marca e liga/desliga das notificações.
 * O app (JS) grava aqui ao abrir; o serviço e o BootReceiver leem mesmo com o app fechado.
 */
class PushConfig(context: Context) {
    private val prefs = context.getSharedPreferences("app_push_config", Context.MODE_PRIVATE)

    var brandName: String
        get() = prefs.getString("brand", null) ?: "Avisos"
        set(v) = prefs.edit().putString("brand", v).apply()

    var colorHex: String
        get() = prefs.getString("color", null) ?: "#F4212E"
        set(v) = prefs.edit().putString("color", v).apply()

    val color: Int
        get() = try {
            Color.parseColor(colorHex)
        } catch (e: Exception) {
            Color.RED
        }

    /** Nome do som em res/raw (sem extensão), ex.: connect_notificacao */
    var sound: String
        get() = prefs.getString("sound", null) ?: ""
        set(v) = prefs.edit().putString("sound", v).apply()

    /** Esquema do app para abrir a central de avisos ao tocar (ex.: connectpay) */
    var scheme: String
        get() = prefs.getString("scheme", null) ?: ""
        set(v) = prefs.edit().putString("scheme", v).apply()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()
}
