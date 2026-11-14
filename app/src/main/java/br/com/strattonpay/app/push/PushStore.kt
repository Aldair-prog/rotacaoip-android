package br.com.strattonpay.app.push

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Assinatura de push do app (o mesmo que um navegador entrega para o site):
 * endereço de entrega + chaves de criptografia (p256dh/auth). Tudo criptografado no aparelho.
 *
 * O endereço é um tópico secreto do ntfy (serviço público de push). As notificações chegam
 * criptografadas pelo próprio painel (Web Push, aes128gcm): o ntfy não consegue ler o conteúdo.
 */
class PushStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "app_push_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** O painel já registrou este celular para receber notificações. */
    var subscribed: Boolean
        get() = prefs.getBoolean("subscribed", false)
        set(v) = prefs.edit().putBoolean("subscribed", v).apply()

    var vapid: String?
        get() = prefs.getString("vapid", null)
        set(v) = prefs.edit().putString("vapid", v).apply()

    /** Última mensagem recebida (para buscar o que chegou enquanto o celular estava offline). */
    var lastMessageId: String?
        get() = prefs.getString("last_id", null)
        set(v) = prefs.edit().putString("last_id", v).apply()

    var testToken: String?
        get() = prefs.getString("test_token", null)
        set(v) = prefs.edit().putString("test_token", v).apply()

    /**
     * Tópico secreto. "up" + 12 caracteres é o formato UnifiedPush do ntfy: assim o limite
     * de envio conta pelo celular que recebe, e não pelo servidor do painel.
     */
    val topic: String
        get() = prefs.getString("topic", null) ?: newTopic().also { prefs.edit().putString("topic", it).apply() }

    val endpoint: String get() = "$NTFY/$topic?up=1"
    val streamUrl: String get() = "$NTFY/$topic/json"
    val topicUrl: String get() = "$NTFY/$topic"

    fun ensureKeys() {
        if (prefs.getString("priv", null) != null) return
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = gen.generateKeyPair()
        val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("priv", b64(pair.private.encoded))
            .putString("pub", b64(pair.public.encoded))
            .putString("auth", b64(auth))
            .apply()
    }

    fun privateKey(): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(prefs.getString("priv", null)!!))) as ECPrivateKey

    fun publicKey(): ECPublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(prefs.getString("pub", null)!!))) as ECPublicKey

    fun authSecret(): ByteArray = unb64(prefs.getString("auth", null)!!)

    /** Formato que a página recebe ao chamar pushManager.subscribe(). */
    fun subscriptionJson(): String {
        ensureKeys()
        return JSONObject()
            .put("endpoint", endpoint)
            .put("p256dh", b64url(rawPublicKey(publicKey())))
            .put("auth", b64url(authSecret()))
            .put("vapid", vapid ?: "")
            .toString()
    }

    /** Apaga a assinatura (novo tópico e novas chaves na próxima vez). */
    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val NTFY = "https://ntfy.sh"

        private fun newTopic(): String {
            val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val rnd = SecureRandom()
            return "up" + (1..12).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }

        fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
        fun b64url(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

        /** Chave pública no formato "cru" do Web Push: 0x04 + X (32 bytes) + Y (32 bytes). */
        fun rawPublicKey(key: ECPublicKey): ByteArray =
            byteArrayOf(4) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

        private fun fixed32(v: BigInteger): ByteArray {
            val b = v.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }
    }
}
