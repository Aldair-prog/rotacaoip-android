package br.com.strattonpay.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Guarda a chave de API criptografada no aparelho (Android Keystore).
 *
 * - "logado": o app abre direto na conta.
 * - "lembrar": ao sair, o Client ID e o Secret ficam salvos e já aparecem preenchidos no login.
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

    private fun stored(): Creds? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val secret = prefs.getString(KEY_SECRET, null) ?: return null
        val base = prefs.getString(KEY_BASE, null) ?: DEFAULT_BASE_URL
        return Creds(base, id, secret)
    }

    /** Credenciais da sessão ativa, ou null se a pessoa saiu. */
    fun load(): Creds? = if (prefs.getBoolean(KEY_LOGGED_IN, true)) stored() else null

    /** Credenciais salvas para preencher o login, se "lembrar" estiver ligado. */
    fun remembered(): Creds? = if (remember) stored() else null

    var remember: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_REMEMBER, value).apply()
        }

    fun save(creds: Creds, remember: Boolean) {
        prefs.edit()
            .putString(KEY_BASE, creds.baseUrl)
            .putString(KEY_ID, creds.clientId)
            .putString(KEY_SECRET, creds.clientSecret)
            .putBoolean(KEY_LOGGED_IN, true)
            .putBoolean(KEY_REMEMBER, remember)
            .apply()
    }

    /** Sai da conta. Mantém os dados para o próximo login só se "lembrar" estiver ligado. */
    fun logout() {
        if (remember) {
            prefs.edit().putBoolean(KEY_LOGGED_IN, false).apply()
        } else {
            forget()
        }
    }

    /** Apaga tudo: sessão e dados salvos. */
    fun forget() {
        prefs.edit().clear().putBoolean(KEY_LOGGED_IN, false).apply()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://app.strattonpay.com.br/api/v1"
        private const val KEY_BASE = "base_url"
        private const val KEY_ID = "client_id"
        private const val KEY_SECRET = "client_secret"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_REMEMBER = "remember"
    }
}
