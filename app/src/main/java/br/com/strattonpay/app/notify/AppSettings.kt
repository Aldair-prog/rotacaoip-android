package br.com.strattonpay.app.notify

import android.content.Context

/** Preferências simples do app (nada secreto aqui). */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    /** Tela de boas-vindas (permissões) já foi mostrada. Volta só se limpar os dados ou reinstalar. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(v) = prefs.edit().putBoolean("onboarding_done", v).apply()

    /** Procurar versão nova ao abrir o app. */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(v) = prefs.edit().putBoolean("auto_update", v).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(v) = prefs.edit().putLong("last_update_check", v).apply()

    /** Versão que a pessoa escolheu "lembrar depois" (não pergunta de novo nas próximas horas). */
    var postponedBuild: Int
        get() = prefs.getInt("postponed_build", 0)
        set(v) = prefs.edit().putInt("postponed_build", v).apply()

    /** Receber as notificações do painel neste celular. */
    var notifyEnabled: Boolean
        get() = prefs.getBoolean("notify_enabled", true)
        set(v) = prefs.edit().putBoolean("notify_enabled", v).apply()
}
