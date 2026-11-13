@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.strattonpay.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.strattonpay.app.data.Balance
import br.com.strattonpay.app.data.CreateWithdraw
import br.com.strattonpay.app.data.FeePreview
import br.com.strattonpay.app.data.PayQrCode
import br.com.strattonpay.app.data.StrattonApi
import br.com.strattonpay.app.data.Withdraw
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** requireAuth(título, aoConfirmar): pede digital/senha do celular antes de mexer em dinheiro. */
typealias RequireAuth = (String, () -> Unit) -> Unit

// =====================================================================
// Lista de saques
// =====================================================================

@Composable
fun WithdrawsScreen(api: StrattonApi, onNew: () -> Unit, onPayQr: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf(emptyList<Withdraw>()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        when (val r = load { api.withdraws(cursor = null) }) {
            is Load.Ok -> {
                list = r.data.data
                next = r.data.pagination?.nextCursor
            }
            is Load.Err -> {
                error = r.message
                list = emptyList()
            }
            else -> {}
        }
        loading = false
    }

    val loadMore: () -> Unit = {
        val cursor = next
        if (cursor != null && !loading) {
            loading = true
            scope.launch {
                when (val r = load { api.withdraws(cursor = cursor) }) {
                    is Load.Ok -> {
                        list = list + r.data.data
                        next = r.data.pagination?.nextCursor
                    }
                    is Load.Err -> { error = r.message }
                    else -> {}
                }
                loading = false
            }
        }
    }

    ScreenScaffold(
        title = "Saques",
        actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Atualizar") } },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onNew, modifier = Modifier.weight(1f).height(50.dp)) {
                        Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sacar via Pix")
                    }
                    OutlinedButton(onClick = onPayQr, modifier = Modifier.weight(1f).height(50.dp)) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pagar QR")
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            when {
                loading && list.isEmpty() -> item { LoadingBox() }
                error != null && list.isEmpty() -> item { ErrorBox(error ?: "", onRetry = { refresh++ }) }
                list.isEmpty() -> item { EmptyBox("Nenhum saque ainda") }
                else -> {
                    items(list, key = { it.id }) { w ->
                        WithdrawRow(w) { onOpen(w.id) }
                        HorizontalDivider(color = Outline)
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> CircularProgressIndicator(Modifier.size(24.dp), color = Gold)
                                next != null -> OutlinedButton(onClick = loadMore) { Text("Carregar mais") }
                                else -> Text("Fim da lista", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WithdrawRow(w: Withdraw, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(withdrawTarget(w), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${destinationLabel(w.destinationType)} · ${formatDate(w.createdAt)}",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(money(w.amount), fontWeight = FontWeight.SemiBold)
            StatusPill(withdrawStatusLabel(w.status), withdrawStatusColor(w.status))
        }
    }
}

private fun withdrawTarget(w: Withdraw): String = when (w.destinationType) {
    "QR_CODE" -> w.description?.takeIf { it.isNotBlank() } ?: "Pagamento de QR Code"
    "CRYPTO" -> "Carteira ${w.description?.take(10) ?: ""}…"
    else -> w.pixKey?.let { "${pixKeyTypeLabel(w.pixKeyType)}: $it" } ?: "Saque Pix"
}

// =====================================================================
// Detalhe do saque (com cancelamento)
// =====================================================================

@Composable
fun WithdrawDetailScreen(api: StrattonApi, id: String, requireAuth: RequireAuth, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val copy = rememberCopy()
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<Load<Withdraw>>(Load.Loading) }
    var askCancel by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id, refresh) {
        state = Load.Loading
        state = load { api.withdraw(id) }
    }

    ScreenScaffold(
        title = "Detalhes do saque",
        onBack = onBack,
        actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Atualizar") } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().navigationBarsPadding()) {
            when (val s = state) {
                Load.Loading -> LoadingBox()
                is Load.Err -> ErrorBox(s.message, onRetry = { refresh++ })
                is Load.Ok -> {
                    val w = s.data
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(money(w.amount), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Gold)
                            Spacer(Modifier.height(6.dp))
                            StatusPill(withdrawStatusLabel(w.status), withdrawStatusColor(w.status))
                        }
                        SectionCard {
                            InfoRow("Destino", destinationLabel(w.destinationType))
                            if (w.pixKey != null) InfoRow(pixKeyTypeLabel(w.pixKeyType), w.pixKey)
                            if (!w.description.isNullOrBlank()) {
                                InfoRow(if (w.destinationType == "QR_CODE") "Recebedor" else "Descrição", w.description)
                            }
                            InfoRow("Taxa", money(w.fee))
                            InfoRow("Recebe", money(w.netAmount))
                        }
                        SectionCard {
                            InfoRow("Pedido em", formatDate(w.createdAt))
                            InfoRow("Aprovado em", formatDate(w.approvedAt))
                            InfoRow("Pago em", formatDate(w.paidAt))
                            if (w.cancelledAt != null) InfoRow("Cancelado em", formatDate(w.cancelledAt))
                            InfoRow("ID", w.id, onCopy = { copy(w.id, "ID copiado") })
                        }
                        if (!w.adminMessage.isNullOrBlank()) {
                            SectionCard {
                                Text("Mensagem da análise", fontWeight = FontWeight.SemiBold)
                                Text(w.adminMessage, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (w.failureHistory.isNotEmpty()) {
                            SectionCard {
                                Text("Tentativas que falharam", fontWeight = FontWeight.SemiBold, color = Warning)
                                w.failureHistory.forEach { f ->
                                    Text("${formatDate(f.createdAt)} — ${f.reason ?: "sem motivo"}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        ErrorText(error)
                        if (w.status == "PENDING_ANALYSIS") {
                            OutlinedButton(
                                onClick = { askCancel = true },
                                enabled = !cancelling,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                            ) { Text(if (cancelling) "Cancelando…" else "Cancelar saque") }
                        }
                    }
                }
            }
        }
    }

    if (askCancel) {
        ConfirmDialog(
            title = "Cancelar este saque?",
            confirmText = "Cancelar saque",
            onDismiss = { askCancel = false },
            onConfirm = {
                askCancel = false
                requireAuth("Confirmar cancelamento") {
                    cancelling = true
                    error = null
                    scope.launch {
                        when (val r = load { api.cancelWithdraw(id) }) {
                            is Load.Ok -> { refresh++ }
                            is Load.Err -> { error = r.message }
                            else -> {}
                        }
                        cancelling = false
                    }
                }
            },
        ) {
            Text("O valor volta na hora para o saldo disponível.")
        }
    }
}

// =====================================================================
// Novo saque via chave Pix
// =====================================================================

private val keyTypes = listOf("CPF", "CNPJ", "EMAIL", "PHONE", "EVP")

@Composable
fun NewWithdrawScreen(api: StrattonApi, requireAuth: RequireAuth, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var keyType by rememberSaveable { mutableStateOf("CPF") }
    var key by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf(0L) }
    var desc by rememberSaveable { mutableStateOf("") }
    var balance by remember { mutableStateOf<Balance?>(null) }
    var preview by remember { mutableStateOf<FeePreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var idem by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<Withdraw?>(null) }

    LaunchedEffect(Unit) {
        val r = load { api.balance() }
        if (r is Load.Ok) balance = r.data
    }
    LaunchedEffect(amount) {
        preview = null
        if (amount >= 100) {
            delay(500)
            val r = load { api.feePreview(amount) }
            if (r is Load.Ok) preview = r.data
        }
    }

    val keyError = if (key.isBlank()) null else pixKeyError(keyType, key)
    val available = balance?.available

    val review: () -> Unit = {
        error = when {
            amount < 100 -> "O valor mínimo é R$ 1,00"
            available != null && amount > available -> "Saldo disponível insuficiente (${money(available)})"
            key.isBlank() -> "Informe a chave Pix de destino"
            keyError != null -> keyError
            else -> null
        }
        if (error == null) {
            idem = UUID.randomUUID().toString()
            confirm = true
        }
    }

    ScreenScaffold(title = "Sacar via Pix", onBack = onBack) { padding ->
        val result = done
        if (result != null) {
            DoneView(
                modifier = Modifier.padding(padding),
                title = "Saque solicitado",
                withdraw = result,
                onDetails = { onOpen(result.id) },
                onClose = onBack,
            )
        } else {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Disponível: " + (available?.let { money(it) } ?: "…"),
                    color = Muted,
                )
                MoneyField(
                    cents = amount,
                    onChange = { amount = it },
                    label = "Valor do saque",
                    supporting = preview?.let { "Taxa ${money(it.fee)} · chega ${money(it.netAmount)}" },
                )
                if (available != null && available > 0) {
                    TextButton(onClick = { amount = available }) { Text("Sacar tudo") }
                }

                Text("Tipo de chave", color = Muted, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    keyTypes.forEach { t ->
                        FilterChip(
                            selected = keyType == t,
                            onClick = { keyType = t },
                            label = { Text(pixKeyTypeLabel(t)) },
                        )
                    }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.take(77) },
                    label = { Text("Chave Pix") },
                    isError = keyError != null,
                    supportingText = { if (keyError != null) Text(keyError) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (keyType) {
                            "CPF", "CNPJ" -> KeyboardType.Number
                            "PHONE" -> KeyboardType.Phone
                            "EMAIL" -> KeyboardType.Email
                            else -> KeyboardType.Text
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it.take(255) },
                    label = { Text("Anotação (opcional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A StrattonPay não confere se a chave é sua. Pix enviado não volta — confira antes.",
                    color = Warning,
                    style = MaterialTheme.typography.bodySmall,
                )
                ErrorText(error)
                PrimaryButton("Revisar saque", onClick = review, loading = loading)
            }
        }
    }

    if (confirm) {
        val fee = preview?.fee
        ConfirmDialog(
            title = "Confirmar saque",
            confirmText = "Sacar",
            onDismiss = { confirm = false },
            onConfirm = {
                confirm = false
                requireAuth("Confirmar saque de ${money(amount)}") {
                    loading = true
                    error = null
                    scope.launch {
                        val request = CreateWithdraw(
                            pixKeyType = keyType,
                            pixKey = normalizePixKey(keyType, key),
                            amount = amount,
                            description = desc.trim().ifBlank { null },
                        )
                        when (val r = load { api.createWithdraw(request, idem) }) {
                            is Load.Ok -> { done = r.data }
                            is Load.Err -> { error = r.message }
                            else -> {}
                        }
                        loading = false
                    }
                }
            },
        ) {
            InfoRow("Chave", "${pixKeyTypeLabel(keyType)}: ${normalizePixKey(keyType, key)}")
            InfoRow("Valor", money(amount))
            InfoRow("Taxa", fee?.let { money(it) } ?: "calculada no envio")
            InfoRow("Destino recebe", preview?.let { money(it.netAmount) } ?: "valor menos a taxa")
            Text("Pix enviado não volta.", color = Warning, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// =====================================================================
// Pagar QR Code (Pix copia e cola)
// =====================================================================

@Composable
fun PayQrScreen(api: StrattonApi, requireAuth: RequireAuth, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var code by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var idem by remember { mutableStateOf("") }
    var done by remember { mutableStateOf<Withdraw?>(null) }
    var available by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        val r = load { api.balance() }
        if (r is Load.Ok) available = r.data.available
    }

    val clean = code.trim().replace("\n", "").replace("\r", "")

    val review: () -> Unit = {
        error = when {
            clean.length < 50 -> "Cole o código Pix copia e cola completo"
            else -> null
        }
        if (error == null) {
            idem = UUID.randomUUID().toString()
            confirm = true
        }
    }

    ScreenScaffold(title = "Pagar QR Code", onBack = onBack) { padding ->
        val result = done
        if (result != null) {
            DoneView(
                modifier = Modifier.padding(padding),
                title = "Pagamento solicitado",
                withdraw = result,
                onDetails = { onOpen(result.id) },
                onClose = onBack,
            )
        } else {
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Disponível: " + (available?.let { money(it) } ?: "…"), color = Muted)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(4096) },
                    label = { Text("Pix copia e cola") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { clipboard.getText()?.text?.let { code = it.trim() } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Colar")
                }
                MoneyField(
                    cents = amount,
                    onChange = { amount = it },
                    label = "Valor (só se o QR não tiver valor)",
                    supporting = "Deixe vazio se o QR Code já define o valor",
                )
                Text(
                    "A taxa vai por cima: o recebedor recebe o valor do QR e sai do seu saldo o valor + taxa. " +
                        "O nome do recebedor aparece depois do envio. Pix pago não volta.",
                    color = Warning,
                    style = MaterialTheme.typography.bodySmall,
                )
                ErrorText(error)
                PrimaryButton("Revisar pagamento", onClick = review, loading = loading)
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = "Pagar este QR Code?",
            confirmText = "Pagar",
            onDismiss = { confirm = false },
            onConfirm = {
                confirm = false
                requireAuth("Confirmar pagamento") {
                    loading = true
                    error = null
                    scope.launch {
                        val request = PayQrCode(qrcode = clean, amount = amount.takeIf { it > 0 })
                        when (val r = load { api.payQrCode(request, idem) }) {
                            is Load.Ok -> { done = r.data }
                            is Load.Err -> { error = r.message }
                            else -> {}
                        }
                        loading = false
                    }
                }
            },
        ) {
            Text(clean.take(60) + "…", style = MaterialTheme.typography.bodySmall, color = Muted)
            InfoRow("Valor", if (amount > 0) money(amount) else "o definido no QR Code")
            Text("Confira se o código é de quem você quer pagar.", color = Warning, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DoneView(modifier: Modifier, title: String, withdraw: Withdraw, onDetails: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(96.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        StatusPill(withdrawStatusLabel(withdraw.status), withdrawStatusColor(withdraw.status))
        SectionCard {
            if (withdraw.destinationType == "QR_CODE" && !withdraw.description.isNullOrBlank()) {
                InfoRow("Recebedor", withdraw.description)
            }
            InfoRow("Sai do saldo", money(withdraw.amount))
            InfoRow("Taxa", money(withdraw.fee))
            InfoRow("Destino recebe", money(withdraw.netAmount))
        }
        Text(
            "Você é avisado do resultado na aba Saques.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onDetails, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Ver detalhes") }
        PrimaryButton("Concluir", onClick = onClose)
    }
}
