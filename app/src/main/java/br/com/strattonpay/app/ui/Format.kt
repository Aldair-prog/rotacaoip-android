package br.com.strattonpay.app.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import br.com.strattonpay.app.data.Fee
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val PENDING_STATES = setOf("PENDING", "PROCESSING")

private val brl: NumberFormat = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/** Centavos -> "R$ 10,00" */
fun money(cents: Long): String = synchronized(brl) { brl.format(BigDecimal(cents).movePointLeft(2)) }

/** Pontos-base -> "1,5%" */
fun percent(basisPoints: Long): String =
    BigDecimal(basisPoints).movePointLeft(2).stripTrailingZeros().toPlainString().replace('.', ',') + "%"

fun feeText(fee: Fee?): String {
    if (fee == null || fee.available == false) return "Indisponível"
    val parts = listOfNotNull(
        fee.fixed?.takeIf { it > 0 }?.let { money(it) },
        fee.percentage?.takeIf { it > 0 }?.let { percent(it) },
    )
    if (fee.fixed == null && fee.percentage == null) return "—"
    return if (parts.isEmpty()) "Grátis" else parts.joinToString(" + ")
}

fun formatDate(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return try {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).format(dateFmt)
    } catch (e: Exception) {
        iso ?: "—"
    }
}

fun jsonText(element: JsonElement?): String? = when (element) {
    null, is JsonNull -> null
    is JsonPrimitive -> element.content
    else -> element.toString()
}

fun methodLabel(method: String?): String = when (method) {
    "PIX" -> "Pix"
    "CREDIT_CARD" -> "Cartão"
    "INVOICE" -> "Boleto"
    else -> method ?: "—"
}

fun txStatusLabel(status: String?): String = when (status) {
    "PENDING" -> "Aguardando"
    "PROCESSING" -> "Processando"
    "APPROVED" -> "Pago"
    "REFUSED" -> "Recusado"
    "REFUNDED" -> "Estornado"
    "CANCELLED" -> "Cancelado"
    "IN_PROTEST" -> "Em contestação"
    "PRE_CHARGEBACK" -> "Pré-chargeback"
    "CHARGEBACK" -> "Chargeback"
    else -> status ?: "—"
}

fun txStatusColor(status: String?): Color = when (status) {
    "APPROVED" -> Success
    "PENDING", "PROCESSING" -> Warning
    "REFUSED", "CHARGEBACK", "PRE_CHARGEBACK", "IN_PROTEST" -> Danger
    else -> Muted
}

fun withdrawStatusLabel(status: String?): String = when (status) {
    "PENDING_ANALYSIS" -> "Em análise"
    "APPROVED" -> "Enviando"
    "COMPLETED" -> "Pago"
    "REFUSED" -> "Recusado"
    "CANCELLED" -> "Cancelado"
    else -> status ?: "—"
}

fun withdrawStatusColor(status: String?): Color = when (status) {
    "COMPLETED" -> Success
    "PENDING_ANALYSIS", "APPROVED" -> Warning
    "REFUSED" -> Danger
    else -> Muted
}

fun destinationLabel(type: String?): String = when (type) {
    "PIX" -> "Pix"
    "QR_CODE" -> "QR Code"
    "CRYPTO" -> "Cripto (USDT)"
    else -> type ?: "—"
}

fun pixKeyTypeLabel(type: String?): String = when (type) {
    "CPF" -> "CPF"
    "CNPJ" -> "CNPJ"
    "EMAIL" -> "E-mail"
    "PHONE" -> "Telefone"
    "EVP" -> "Aleatória"
    else -> type ?: "—"
}

fun statementLabel(type: String?, description: String?): String = when (type) {
    "PAYMENT_CAPTURE" -> "Venda recebida"
    "MERCHANT_PAYOUT" -> "Saque"
    "MERCHANT_FEE_CHARGE" -> "Taxa"
    else -> description?.takeIf { it.isNotBlank() }
        ?: type?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
        ?: "Lançamento"
}

fun decodeDataUri(uri: String?): ImageBitmap? {
    if (uri.isNullOrBlank()) return null
    return try {
        val b64 = if (uri.contains("base64,")) uri.substringAfter("base64,") else uri
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

// ---------- Validações ----------

fun isValidCpf(raw: String): Boolean {
    val d = raw.filter { it.isDigit() }
    if (d.length != 11 || d.all { it == d[0] }) return false
    val n = d.map { it - '0' }
    var sum = 0
    for (i in 0 until 9) sum += n[i] * (10 - i)
    var dv1 = (sum * 10) % 11
    if (dv1 == 10) dv1 = 0
    if (dv1 != n[9]) return false
    sum = 0
    for (i in 0 until 10) sum += n[i] * (11 - i)
    var dv2 = (sum * 10) % 11
    if (dv2 == 10) dv2 = 0
    return dv2 == n[10]
}

fun isValidCnpj(raw: String): Boolean {
    val d = raw.filter { it.isDigit() }
    if (d.length != 14 || d.all { it == d[0] }) return false
    val n = d.map { it - '0' }
    val w1 = intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
    val w2 = intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
    fun dv(len: Int, w: IntArray): Int {
        var s = 0
        for (i in 0 until len) s += n[i] * w[i]
        val r = s % 11
        return if (r < 2) 0 else 11 - r
    }
    return dv(12, w1) == n[12] && dv(13, w2) == n[13]
}

private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private val evpRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Retorna a mensagem de erro, ou null se a chave Pix parece válida. */
fun pixKeyError(type: String, key: String): String? {
    val k = key.trim()
    val digits = k.filter { it.isDigit() }
    return when (type) {
        "CPF" -> if (isValidCpf(digits)) null else "CPF inválido"
        "CNPJ" -> if (isValidCnpj(digits)) null else "CNPJ inválido"
        "EMAIL" -> if (emailRegex.matches(k)) null else "E-mail inválido"
        "PHONE" -> if (digits.length in 10..13) null else "Telefone inválido (inclua o DDD)"
        "EVP" -> if (evpRegex.matches(k)) null else "Chave aleatória inválida"
        else -> null
    }
}

/** Normaliza a chave no formato que o Pix espera. */
fun normalizePixKey(type: String, key: String): String {
    val k = key.trim()
    val digits = k.filter { it.isDigit() }
    return when (type) {
        "CPF", "CNPJ" -> digits
        "EMAIL", "EVP" -> k.lowercase()
        "PHONE" -> if (digits.length <= 11) "+55$digits" else "+$digits"
        else -> k
    }
}
