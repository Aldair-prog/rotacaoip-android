package br.com.strattonpay.app.push

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/** Exemplo oficial do padrão Web Push (RFC 8291, apêndice A). */
class WebPushCryptoTest {

    private fun b64(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    private val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    @Test
    fun abreMensagemDoExemploOficial() {
        val uaPrivate = KeyFactory.getInstance("EC").generatePrivate(
            ECPrivateKeySpec(BigInteger(1, b64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")), params),
        ) as ECPrivateKey
        val raw = b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
        val uaPublic = KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65))), params),
        ) as ECPublicKey
        val body = b64(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
        )

        assertEquals(raw.toList(), PushStore.rawPublicKey(uaPublic).toList())
        val plain = WebPushCrypto.decrypt(body, uaPrivate, uaPublic, b64("BTBZMqHH6r4Tts7J_aSIgg"))
        assertEquals("When I grow up, I want to be a watermelon", plain.toString(Charsets.UTF_8))
    }
}
