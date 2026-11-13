package br.com.strattonpay.app.notify

import android.content.Context

/** Preferências de notificação (nada secreto aqui) e o controle do que já foi avisado. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("stratton_settings", Context.MODE_PRIVATE)

    var notifyEnabled: Boolean
        get() = prefs.getBoolean("notify_enabled", false)
        set(v) = prefs.edit().putBoolean("notify_enabled", v).apply()

    var notifySales: Boolean
        get() = prefs.getBoolean("notify_sales", true)
        set(v) = prefs.edit().putBoolean("notify_sales", v).apply()

    var notifyWithdraws: Boolean
        get() = prefs.getBoolean("notify_withdraws", true)
        set(v) = prefs.edit().putBoolean("notify_withdraws", v).apply()

    var notifyDisputes: Boolean
        get() = prefs.getBoolean("notify_disputes", true)
        set(v) = prefs.edit().putBoolean("notify_disputes", v).apply()

    var intervalMinutes: Int
        get() = prefs.getInt("interval_minutes", 5)
        set(v) = prefs.edit().putInt("interval_minutes", v).apply()

    // ---------- Controle interno ----------

    var initialized: Boolean
        get() = prefs.getBoolean("initialized", false)
        set(v) = prefs.edit().putBoolean("initialized", v).apply()

    var lastPaidAt: String?
        get() = prefs.getString("last_paid_at", null)
        set(v) = prefs.edit().putString("last_paid_at", v).apply()

    var pendingWithdraws: Set<String>
        get() = prefs.getStringSet("pending_withdraws", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("pending_withdraws", v).apply()

    var seenDisputes: Set<String>
        get() = prefs.getStringSet("seen_disputes", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("seen_disputes", v).apply()

    /** Zera o histórico: na próxima checagem só marca o ponto de partida, sem avisar coisas antigas. */
    fun resetTracking() {
        prefs.edit()
            .remove("initialized")
            .remove("last_paid_at")
            .remove("pending_withdraws")
            .remove("seen_disputes")
            .apply()
    }
}
