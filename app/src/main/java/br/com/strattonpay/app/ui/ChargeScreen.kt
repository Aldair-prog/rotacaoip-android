@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.strattonpay.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.strattonpay.app.data.CreatePixCharge
import br.com.strattonpay.app.data.Payer
import br.com.strattonpay.app.data.PayerDocument
import br.com.strattonpay.app.data.PixChargeCreated
import br.com.strattonpay.app.data.StrattonApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/** Guarda a cobrança em andamento, para não sumir ao trocar de aba. */
class ChargeSession {
    var result by mutableStateOf<PixChargeCreated?>(null)
    var status by mutableStateOf<String?>(null)
}

@Composable
fun ChargeScreen(api: StrattonApi, session: ChargeSession) {
    val result = session.result
    ScreenScaffold(title = if (result == null) "Nova cobrança Pix" else "Cobrança Pix") { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (result == null) {
                ChargeForm(api, onCreated = {
                    session.result = it
                    session.status = "PENDING"
                })
            } else {
                ChargeResult(
                    api = api,
                    result = result,
                    status = session.status,
                    onStatus = { session.status = it },
                    onNew = {
                        session.result = null
                        session.status = null
                    },
                )
            }
        }
    }
}

@Composable
private fun ChargeForm(api: StrattonApi, onCreated: (PixChargeCreated) -> Unit) {
    var amount by rememberSaveable { mutableStateOf(0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var doc by rememberSaveable { mutableStateOf("") }
    var desc by rememberSaveable { mutableStateOf("") }
    var idem by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var loading by remember { mutableStateOf(false) }
    var fillingDoc by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Corpo mudou -> nova chave de idempotência. Mesmo corpo (retentativa) -> mesma chave, sem cobrança duplicada.
    LaunchedEffect(amount, name, doc, desc) { idem = UUID.randomUUID().toString() }

    val docDigits = doc.filter { it.isDigit() }
    val docType = if (docDigits.length > 11) "CNPJ" else "CPF"
    val docOk = if (docType == "CPF") isValidCpf(docDigits) else isValidCnpj(docDigits)
    val docShowError = (docDigits.length == 11 || docDigits.length >= 14) && !docOk

    val submit: () -> Unit = {
        error = when {
            amount < 100 -> "O valor mínimo é R$ 1,00"
            !docOk -> "Informe um CPF ou CNPJ válido do pagador"
            else -> null
        }
        if (error == null) {
            loading = true
            scope.launch {
                val request = CreatePixCharge(
                    amount = amount,
                    payer = Payer(
                        name = name.trim().ifBlank { null },
                        document = PayerDocument(docType, docDigits),
                    ),
                    description = desc.trim().ifBlank { null },
                )
                when (val r = load { api.createPix(request, idem) }) {
                    is Load.Ok -> { onCreated(r.data) }
                    is Load.Err -> { error = r.message }
                    else -> {}
                }
                loading = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MoneyField(amount, { amount = it }, "Valor da cobrança")

        OutlinedTextField(
            value = doc,
            onValueChange = { v -> doc = v.filter { it.isDigit() || it == '.' || it == '-' || it == '/' }.take(18) },
            label = { Text("CPF ou CNPJ do pagador") },
            isError = docShowError,
            supportingText = { if (docShowError) Text("Documento inválido") else Text("Obrigatório pela StrattonPay") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            enabled = !fillingDoc,
            onClick = {
                fillingDoc = true
                scope.launch {
                    when (val r = load { api.company() }) {
                        is Load.Ok -> { r.data.document?.number?.let { doc = it } }
                        is Load.Err -> { error = r.message }
                        else -> {}
                    }
                    fillingDoc = false
                }
            },
        ) { Text("Usar o documento da minha empresa") }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(100) },
            label = { Text("Nome do pagador (opcional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = desc,
            onValueChange = { desc = it.take(140) },
            label = { Text("Descrição (opcional)") },
            placeholder = { Text("Pagamento realizado via PIX") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ErrorText(error)
        PrimaryButton("Gerar Pix", onClick = submit, loading = loading)
    }
}

@Composable
private fun ChargeResult(
    api: StrattonApi,
    result: PixChargeCreated,
    status: String?,
    onStatus: (String) -> Unit,
    onNew: () -> Unit,
) {
    val copy = rememberCopy()
    val share = rememberShare()
    val qr = remember(result.qrcode?.base64) { decodeDataUri(result.qrcode?.base64) }
    val code = result.qrcode?.code.orEmpty()
    val paid = status == "APPROVED"
    val pending = status == null || status in PENDING_STATES

    // Acompanha o pagamento consultando a transação a cada 5 s enquanto estiver pendente.
    LaunchedEffect(result.id) {
        if (!pending) return@LaunchedEffect
        while (true) {
            delay(5_000)
            val r = load { api.transaction(result.id) }
            if (r is Load.Ok) {
                val s = r.data.status ?: "PENDING"
                onStatus(s)
                if (s !in PENDING_STATES) break
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(money(result.amount), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Gold)

        when {
            paid -> {
                Spacer(Modifier.height(12.dp))
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(110.dp))
                Text("Pagamento recebido!", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Success)
            }
            pending -> {
                StatusPill("Aguardando pagamento", Warning)
                if (qr != null) {
                    Box(
                        Modifier
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                    ) {
                        Image(
                            bitmap = qr,
                            contentDescription = "QR Code Pix",
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.size(250.dp),
                        )
                    }
                }
            }
            else -> {
                StatusPill(txStatusLabel(status), txStatusColor(status))
            }
        }

        if (!paid && code.isNotBlank()) {
            SectionCard {
                Text("Pix copia e cola", color = Muted, style = MaterialTheme.typography.labelMedium)
                Text(code, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copy(code, "Código Pix copiado") }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copiar")
                    }
                    OutlinedButton(onClick = { share(code) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Enviar")
                    }
                }
            }
        }

        if (pending) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Muted)
                Spacer(Modifier.width(8.dp))
                Text("A tela atualiza sozinha quando o Pix cair.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }

        Text(
            "ID: ${result.id}",
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )

        PrimaryButton("Nova cobrança", onClick = onNew)
    }
}
