package br.com.rotacaoip

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Relay(
    val host: String,
    val cc: String,        // codigo do pais, ex. "se"
    val pais: String,
    val cidade: String,
    val ipv4: String,
    val chavePublica: String,
)

data class IpInfo(
    val ip: String,
    val pais: String,
    val cidade: String,
    val mullvad: Boolean,
    val host: String,
)

/** Comunicacao com a API publica do Mullvad (a mesma usada pela ferramenta oficial mullvad-wg.sh). */
object Mullvad {
    private const val URL_RELAYS = "https://api.mullvad.net/public/relays/wireguard/v1/"
    private const val URL_REGISTRO = "https://api.mullvad.net/wg"
    private const val URL_IP = "https://am.i.mullvad.net/json"

    private fun abrir(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            useCaches = false
            setRequestProperty("User-Agent", "RotacaoIP-Android")
        }

    private fun lerResposta(c: HttpURLConnection): Pair<Int, String> {
        val codigo = c.responseCode
        val stream = if (codigo >= 400) c.errorStream else c.inputStream
        val corpo = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return codigo to corpo
    }

    /** Registra a chave publica WireGuard na conta e devolve o endereco do tunel. */
    fun registrar(conta: String, chavePublica: String): String {
        val c = abrir(URL_REGISTRO)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val corpo = "account=" + URLEncoder.encode(conta, "UTF-8") +
            "&pubkey=" + URLEncoder.encode(chavePublica, "UTF-8")
        c.outputStream.use { it.write(corpo.toByteArray()) }
        val (codigo, resp) = lerResposta(c)
        val texto = resp.trim()
        if (codigo in 200..299 && Regex("^[0-9a-fA-F:/.,]+$").matches(texto)) return texto
        throw Exception(traduzirErro(texto.ifEmpty { "HTTP $codigo" }))
    }

    private fun traduzirErro(msg: String): String {
        val m = msg.lowercase()
        return when {
            "too many" in m || "max" in m && "device" in m ->
                "Sua conta já tem 5 aparelhos. Remova um em mullvad.net (Conta > Dispositivos) e tente de novo."
            "invalid" in m && "account" in m || "not found" in m ->
                "Número de conta inválido."
            "expired" in m || "time" in m && "left" in m ->
                "A conta Mullvad está sem tempo (expirada)."
            else -> "Mullvad recusou: $msg"
        }
    }

    fun baixarRelays(): String {
        val (codigo, corpo) = lerResposta(abrir(URL_RELAYS))
        if (codigo !in 200..299) throw Exception("Lista de servidores: HTTP $codigo")
        if (lerRelays(corpo).isEmpty()) throw Exception("Lista de servidores vazia")
        return corpo
    }

    /** Le a lista de servidores de forma tolerante (aceita mais de um formato). */
    fun lerRelays(json: String): List<Relay> {
        val saida = mutableListOf<Relay>()
        if (json.isBlank()) return saida
        try {
            val t = json.trim()
            if (t.startsWith("[")) {
                lerListaPlana(JSONArray(t), saida)
            } else {
                val raiz = JSONObject(t)
                val paises = raiz.optJSONArray("countries")
                if (paises != null) {
                    for (i in 0 until paises.length()) {
                        val p = paises.optJSONObject(i) ?: continue
                        val nomePais = p.optString("name")
                        val codPais = p.optString("code").lowercase()
                        val cidades = p.optJSONArray("cities") ?: continue
                        for (j in 0 until cidades.length()) {
                            val c = cidades.optJSONObject(j) ?: continue
                            val nomeCidade = c.optString("name")
                            val relays = c.optJSONArray("relays") ?: continue
                            for (k in 0 until relays.length()) {
                                val r = relays.optJSONObject(k) ?: continue
                                adicionar(r, codPais, nomePais, nomeCidade, saida)
                            }
                        }
                    }
                } else {
                    raiz.optJSONArray("relays")?.let { lerListaPlana(it, saida) }
                }
            }
        } catch (_: Exception) {
        }
        return saida
    }

    private fun lerListaPlana(arr: JSONArray, saida: MutableList<Relay>) {
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.has("type") && r.optString("type") != "wireguard") continue
            adicionar(r, r.optString("country_code").lowercase(), r.optString("country_name"), r.optString("city_name"), saida)
        }
    }

    private fun adicionar(r: JSONObject, codPais: String, nomePais: String, nomeCidade: String, saida: MutableList<Relay>) {
        if (!r.optBoolean("active", true)) return
        val host = r.optString("hostname")
        val ip = r.optString("ipv4_addr_in")
        val chave = r.optString("public_key").ifEmpty { r.optString("pubkey") }
        if (host.isEmpty() || ip.isEmpty() || chave.isEmpty()) return
        val cc = codPais.ifEmpty { if (host.length > 2 && host[2] == '-') host.substring(0, 2) else "" }.lowercase()
        if (cc.length != 2) return
        saida.add(Relay(host.removeSuffix("-wireguard"), cc, nomePais.ifEmpty { cc.uppercase() }, nomeCidade, ip, chave))
    }

    fun ipAtual(): IpInfo {
        val (codigo, corpo) = lerResposta(abrir(URL_IP))
        if (codigo !in 200..299) throw Exception("HTTP $codigo")
        val o = JSONObject(corpo)
        return IpInfo(
            ip = o.optString("ip"),
            pais = o.optString("country"),
            cidade = o.optString("city"),
            mullvad = o.optBoolean("mullvad_exit_ip", false),
            host = o.optString("mullvad_exit_ip_hostname"),
        )
    }
}

/** Bandeira em emoji a partir do codigo do pais ("se" -> 🇸🇪). */
fun bandeira(cc: String): String {
    if (cc.length != 2) return ""
    val sb = StringBuilder()
    for (ch in cc.uppercase()) {
        if (ch !in 'A'..'Z') return ""
        sb.appendCodePoint(0x1F1E6 + (ch - 'A'))
    }
    return sb.toString()
}
