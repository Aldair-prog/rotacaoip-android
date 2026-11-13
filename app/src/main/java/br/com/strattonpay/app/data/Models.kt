package br.com.strattonpay.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// ---------- Empresa e saldo ----------

@Serializable
data class DocumentInfo(val number: String? = null, val type: String? = null)

@Serializable
data class Fee(val available: Boolean? = null, val fixed: Long? = null, val percentage: Long? = null)

@Serializable
data class IncomingFees(
    val pix: Fee? = null,
    @SerialName("credit_card") val creditCard: Fee? = null,
    val invoice: Fee? = null,
)

@Serializable
data class Fees(val incoming: IncomingFees? = null, val outgoing: Fee? = null)

@Serializable
data class Company(
    val id: String = "",
    val name: String? = null,
    @SerialName("trade_name") val tradeName: String? = null,
    val document: DocumentInfo? = null,
    val status: String? = null,
    val fees: Fees? = null,
)

@Serializable
data class Balance(
    @SerialName("total_balance") val total: Long = 0,
    @SerialName("reserved_amount") val reserved: Long = 0,
    @SerialName("available_balance") val available: Long = 0,
)

// ---------- Cobranças ----------

@Serializable
data class PayerDocument(val type: String, val number: String)

@Serializable
data class Payer(
    val name: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val document: PayerDocument,
)

@Serializable
data class CreatePixCharge(
    val method: String = "PIX",
    val amount: Long,
    val payer: Payer,
    val description: String? = null,
    val expiresIn: Long? = null,
)

@Serializable
data class QrCode(val code: String? = null, val base64: String? = null)

@Serializable
data class PixChargeCreated(
    val id: String = "",
    val amount: Long = 0,
    val externalId: String? = null,
    val qrcode: QrCode? = null,
)

@Serializable
data class Customer(
    val name: String? = null,
    val email: String? = null,
    @SerialName("document_number") val documentNumber: String? = null,
)

@Serializable
data class PixInfo(
    val qrcode: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("end_to_end_id") val endToEndId: String? = null,
)

@Serializable
data class Transaction(
    val id: String = "",
    val method: String? = null,
    val status: String? = null,
    val amount: Long = 0,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("external_id") val externalId: String? = null,
    val customer: Customer? = null,
    val pix: PixInfo? = null,
    @SerialName("refused_reason") val refusedReason: JsonElement? = null,
)

@Serializable
data class PageMeta(val total: Long? = null, val nextCursor: String? = null)

@Serializable
data class TransactionPage(val items: List<Transaction> = emptyList(), val meta: PageMeta? = null)

// ---------- Extrato ----------

@Serializable
data class StatementItem(
    val id: String = "",
    val type: String? = null,
    val description: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("withdraw_id") val withdrawId: String? = null,
    val entrada: Long = 0,
    val saida: Long = 0,
    @SerialName("posted_at") val postedAt: String? = null,
)

@Serializable
data class StatementPage(val items: List<StatementItem> = emptyList(), val meta: PageMeta? = null)

// ---------- Saques ----------

@Serializable
data class WithdrawFailure(val reason: String? = null, @SerialName("created_at") val createdAt: String? = null)

@Serializable
data class Withdraw(
    val id: String = "",
    val status: String? = null,
    val amount: Long = 0,
    val fee: Long = 0,
    @SerialName("net_amount") val netAmount: Long = 0,
    @SerialName("destination_type") val destinationType: String? = null,
    @SerialName("pix_key") val pixKey: String? = null,
    @SerialName("pix_key_type") val pixKeyType: String? = null,
    val description: String? = null,
    @SerialName("admin_message") val adminMessage: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("failure_history") val failureHistory: List<WithdrawFailure> = emptyList(),
)

@Serializable
data class Pagination(@SerialName("next_cursor") val nextCursor: String? = null, val total: Long? = null)

@Serializable
data class WithdrawPage(val data: List<Withdraw> = emptyList(), val pagination: Pagination? = null)

@Serializable
data class FeePreview(
    val amount: Long = 0,
    @SerialName("fixed_fee") val fixedFee: Long = 0,
    @SerialName("percent_fee") val percentFee: Long = 0,
    val fee: Long = 0,
    @SerialName("net_amount") val netAmount: Long = 0,
)

@Serializable
data class CreateWithdraw(
    val pixKeyType: String,
    val pixKey: String,
    val amount: Long,
    val description: String? = null,
)

@Serializable
data class PayQrCode(val qrcode: String, val amount: Long? = null)
