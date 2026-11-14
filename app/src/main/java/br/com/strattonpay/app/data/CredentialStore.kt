package br.com.strattonpay.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Tudo que é sensível fica aqui, criptografado com o Android Keystore:
 * - usuário e senha do painel (para preencher o login sozinho);
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

    companion object {
        private const val KEY_SAVE_LOGIN = "save_panel_login"
        private const val KEY_PANEL_USER = "panel_user"
        private const val KEY_PANEL_PASS = "panel_pass"
        private const val KEY_LOCK = "lock_enabled"
    }
}
