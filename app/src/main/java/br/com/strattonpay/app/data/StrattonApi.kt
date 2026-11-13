package br.com.strattonpay.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, override val message: String) : Exception(message)

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
    isLenient = true
}

/**
 * Cliente da API pública da StrattonPay (https://app.strattonpay.com.br/api/v1).
 * Autentica com HTTP Basic (client id + client secret).
 */
class StrattonApi(baseUrl: String, clientId: String, clientSecret: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val auth = Credentials.basic(clientId, clientSecret)
    private val jsonType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ---------- Empresa / saldo / extrato ----------

    suspend fun company(): Company = get("/companies/@me")

    suspend fun balance(): Balance = get("/withdraws/@me/balance")

    suspend fun statement(cursor: String?, perPage: Int = 30): StatementPage =
        get("/statement/@me", mapOf("perPage" to perPage.toString(), "cursor" to cursor))

    // ---------- Transações ----------

    suspend fun createPix(request: CreatePixCharge, idempotencyKey: String): PixChargeCreated =
        post("/transactions", ApiJson.encodeToString(request), idempotencyKey)

    suspend fun transactions(cursor: String?, status: String? = null, perPage: Int = 20): TransactionPage =
        get("/transactions", mapOf("perPage" to perPage.toString(), "cursor" to cursor, "status" to status))

    suspend fun transaction(id: String): Transaction = get("/transactions/$id")

    // ---------- Saques ----------

    suspend fun withdraws(cursor: String?, perPage: Int = 20): WithdrawPage =
        get("/withdraws/@me", mapOf("perPage" to perPage.toString(), "cursor" to cursor))

    suspend fun withdraw(id: String): Withdraw = get("/withdraws/@me/$id")

    suspend fun feePreview(amount: Long): FeePreview =
        get("/withdraws/@me/fee-preview", mapOf("amount" to amount.toString()))

    suspend fun createWithdraw(request: CreateWithdraw, idempotencyKey: String): Withdraw =
        post("/withdraws/@me", ApiJson.encodeToString(request), idempotencyKey)

    suspend fun payQrCode(request: PayQrCode, idempotencyKey: String): Withdraw =
        post("/withdraws/@me/qr-code", ApiJson.encodeToString(request), idempotencyKey)

    suspend fun cancelWithdraw(id: String): Withdraw = post("/withdraws/@me/$id/cancel", "{}", null)

    // ---------- HTTP ----------

    private suspend inline fun <reified T> get(path: String, query: Map<String, String?> = emptyMap()): T =
        ApiJson.decodeFromString(request("GET", path, query, null, null))

    private suspend inline fun <reified T> post(path: String, body: String, idempotencyKey: String?): T =
        ApiJson.decodeFromString(request("POST", path, emptyMap(), body, idempotencyKey))

    private suspend fun request(
        method: String,
        path: String,
        query: Map<String, String?>,
        body: String?,
        idempotencyKey: String?,
    ): String = withContext(Dispatchers.IO) {
        val urlBuilder = (base + path).toHttpUrl().newBuilder()
        for ((k, v) in query) {
            if (!v.isNullOrBlank()) urlBuilder.addQueryParameter(k, v)
        }
        val url = urlBuilder.build()

        val builder = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Accept", "application/json")
        if (idempotencyKey != null) builder.header("X-Idempotency-Key", idempotencyKey)
        if (method == "GET") builder.get() else builder.method(method, (body ?: "{}").toRequestBody(jsonType))

        try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(response.code, friendlyError(response.code, text))
                text
            }
        } catch (e: IOException) {
            throw ApiException(0, "Sem conexão com a StrattonPay. Verifique a internet e tente de novo.")
        }
    }

    private fun friendlyError(code: Int, text: String): String {
        val raw = extractMessage(text)
        return when {
            code == 401 -> "Chave de acesso inválida. Confira o Client ID e o Client Secret."
            raw.contains("UserBlocked", ignoreCase = true) -> "Usuário bloqueado. Fale com o suporte da StrattonPay."
            raw.contains("InsufficientScope", ignoreCase = true) || code == 403 && raw.contains("scope", ignoreCase = true) ->
                "Sua chave não tem permissão para isso. Libere o escopo no painel (Configurações → Chaves de API)."
            raw.isNotBlank() -> raw
            code >= 500 -> "A StrattonPay está instável agora (erro $code). Tente de novo em instantes."
            else -> "Erro $code na StrattonPay."
        }
    }

    private fun extractMessage(text: String): String = try {
        val element = ApiJson.parseToJsonElement(text)
        if (element is JsonObject) {
            val message = when (val m = element["message"]) {
                null, is JsonNull -> ""
                is JsonPrimitive -> m.content
                is JsonArray -> m.joinToString("\n") { (it as? JsonPrimitive)?.content ?: it.toString() }
                else -> m.toString()
            }
            val name = (element["name"] as? JsonPrimitive)?.content.orEmpty()
            if (name.contains("Exception") && !message.contains(name)) "$name: $message".trim(':', ' ') else message
        } else {
            text.take(200)
        }
    } catch (e: Exception) {
        text.take(200)
    }
}
