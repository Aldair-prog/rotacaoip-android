package br.com.strattonpay.app.notify

import android.content.Context

/** Preferências simples do app (nada secreto aqui). */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /** Receber as notificações do painel neste celular. */
    var notifyEnabled: Boolean
        get() = prefs.getBoolean("notify_enabled", true)
        set(v) = prefs.edit().putBoolean("notify_enabled", v).apply()
}
