package br.com.strattonpay.app.push

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Abre as notificações do painel: Web Push com criptografia aes128gcm (RFC 8291 / RFC 8188). */
object WebPushCrypto {

    fun decrypt(body: ByteArray, privateKey: ECPrivateKey, uaPublic: ECPublicKey, authSecret: ByteArray): ByteArray {
        require(body.size > 21) { "mensagem curta demais" }
        val salt = body.copyOfRange(0, 16)
        val rs = ByteBuffer.wrap(body, 16, 4).int
        val idLen = body[20].toInt() and 0xff
        val asPublicRaw = body.copyOfRange(21, 21 + idLen)
        val ciphertext = body.copyOfRange(21 + idLen, body.size)

        val uaPublicRaw = PushStore.rawPublicKey(uaPublic)
        val asPublic = rawToPublicKey(asPublicRaw, uaPublic)
        val ecdh = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(asPublic, true)
            generateSecret()
        }

        // RFC 8291: mistura o segredo ECDH com o auth secret
        val prkKey = hmac(authSecret, ecdh)
        val keyInfo = "WebPush: info\u0000".toByteArray() + uaPublicRaw + asPublicRaw
        val ikm = hmac(prkKey, keyInfo + byteArrayOf(1))

        // RFC 8188: chave de conteúdo e nonce
        val prk = hmac(salt, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm\u0000".toByteArray() + byteArrayOf(1)).copyOf(16)
        val baseNonce = hmac(prk, "Content-Encoding: nonce\u0000".toByteArray() + byteArrayOf(1)).copyOf(12)

        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        var seq = 0L
        val recordSize = if (rs > 16) rs else ciphertext.size
        while (offset < ciphertext.size) {
            val end = minOf(offset + recordSize, ciphertext.size)
            val nonce = baseNonce.copyOf()
            for (i in 0 until 8) {
                nonce[11 - i] = (nonce[11 - i].toInt() xor ((seq shr (8 * i)) and 0xff).toInt()).toByte()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            val plain = cipher.doFinal(ciphertext, offset, end - offset)
            out.write(unpad(plain))
            offset = end
            seq++
        }
        return out.toByteArray()
    }

    /** Remove o preenchimento: zeros no fim + delimitador 0x01/0x02. */
    private fun unpad(plain: ByteArray): ByteArray {
        var i = plain.size - 1
        while (i >= 0 && plain[i].toInt() == 0) i--
        return if (i >= 0 && (plain[i].toInt() == 1 || plain[i].toInt() == 2)) plain.copyOf(i) else plain
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun rawToPublicKey(raw: ByteArray, sameCurve: ECPublicKey): ECPublicKey {
        require(raw.size == 65 && raw[0].toInt() == 4) { "chave do remetente inválida" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), sameCurve.params)) as ECPublicKey
    }
}
