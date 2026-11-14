package br.com.strattonpay.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Tudo que é sensível fica aqui, criptografado com o Android Keystore:
 * - usuário e senha do painel (para preencher o login sozinho);
 * - chave de API opcional, usada só pelas notificações em segundo plano.
 */
class CredentialStore(context: Context) {

    data class Creds(val baseUrl: String, val clientId: String, val clientSecret: String)

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "stratton_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // ---------- Login do painel ----------

    var savePanelLogin: Boolean
        get() = prefs.getBoolean(KEY_SAVE_LOGIN, true)
        set(v) {
            prefs.edit().putBoolean(KEY_SAVE_LOGIN, v).apply()
            if (!v) clearPanelLogin()
        }

    val panelUser: String? get() = prefs.getString(KEY_PANEL_USER, null)
    val panelPass: String? get() = prefs.getString(KEY_PANEL_PASS, null)

    fun setPanelLogin(user: String, pass: String) {
        prefs.edit().putString(KEY_PANEL_USER, user).putString(KEY_PANEL_PASS, pass).apply()
    }

    fun clearPanelLogin() {
        prefs.edit().remove(KEY_PANEL_USER).remove(KEY_PANEL_PASS).apply()
    }

    // ---------- Bloqueio com digital ----------

    var lockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK, true)
        set(v) = prefs.edit().putBoolean(KEY_LOCK, v).apply()

    // ---------- Chave de API (notificações) ----------

    fun load(): Creds? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null
        val base = prefs.getString(KEY_BASE, null) ?: DEFAULT_BASE_URL
        return Creds(base, id, secret)
    }

    fun saveApi(creds: Creds) {
        prefs.edit()
            .putString(KEY_BASE, creds.baseUrl)
            .putString(KEY_ID, creds.clientId)
            .putString(KEY_SECRET, creds.clientSecret)
            .apply()
    }

    fun clearApi() {
        prefs.edit().remove(KEY_BASE).remove(KEY_ID).remove(KEY_SECRET).apply()
    }

    companion object {
        val DEFAULT_BASE_URL = br.com.strattonpay.app.BuildConfig.API_BASE
        private const val KEY_BASE = "base_url"
        private const val KEY_ID = "client_id"
        private const val KEY_SECRET = "client_secret"
        private const val KEY_SAVE_LOGIN = "save_panel_login"
        private const val KEY_PANEL_USER = "panel_user"
        private const val KEY_PANEL_PASS = "panel_pass"
        private const val KEY_LOCK = "lock_enabled"
    }
}
