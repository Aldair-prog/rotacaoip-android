@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.strattonpay.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.strattonpay.app.data.StrattonApi
import br.com.strattonpay.app.data.Transaction
import kotlinx.coroutines.launch

private val txFilters = listOf<Pair<String?, String>>(
    null to "Todas",
    "APPROVED" to "Pagas",
    "PENDING" to "Pendentes",
    "REFUNDED" to "Estornadas",
    "CANCELLED" to "Canceladas",
)

@Composable
fun TransactionsScreen(api: StrattonApi, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var txs by remember { mutableStateOf(emptyList<Transaction>()) }
    var next by remember { mutableStateOf<String?>(null) }
    var total by remember { mutableStateOf<Long?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filter, refresh) {
        loading = true
        error = null
        when (val r = load { api.transactions(cursor = null, status = filter) }) {
            is Load.Ok -> {
                txs = r.data.items
                next = r.data.meta?.nextCursor
                total = r.data.meta?.total
            }
            is Load.Err -> {
                error = r.message
                txs = emptyList()
                next = null
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
                when (val r = load { api.transactions(cursor = cursor, status = filter) }) {
                    is Load.Ok -> {
                        txs = txs + r.data.items
                        next = r.data.meta?.nextCursor
                    }
                    is Load.Err -> { error = r.message }
                    else -> {}
                }
                loading = false
            }
        }
    }

    ScreenScaffold(
        title = "Vendas",
        actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Atualizar") } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                txFilters.forEach { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { filter = value },
                        label = { Text(label) },
                    )
                }
            }
            total?.let {
                Text(
                    "$it ${if (it == 1L) "venda" else "vendas"}",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            when {
                loading && txs.isEmpty() -> LoadingBox()
                error != null && txs.isEmpty() -> ErrorBox(error ?: "", onRetry = { refresh++ })
                txs.isEmpty() -> EmptyBox("Nenhuma venda encontrada")
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(txs, key = { it.id }) { t ->
                        TxRow(t) { onOpen(t.id) }
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
fun TransactionDetailScreen(api: StrattonApi, id: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<Load<Transaction>>(Load.Loading) }
    var refresh by remember { mutableIntStateOf(0) }
    val copy = rememberCopy()

    LaunchedEffect(id, refresh) {
        state = Load.Loading
        state = load { api.transaction(id) }
    }

    ScreenScaffold(
        title = "Detalhes da venda",
        onBack = onBack,
        actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Atualizar") } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().navigationBarsPadding()) {
            when (val s = state) {
                Load.Loading -> LoadingBox()
                is Load.Err -> ErrorBox(s.message, onRetry = { refresh++ })
                is Load.Ok -> {
                    val t = s.data
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(money(t.amount), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Gold)
                            Spacer(Modifier.height(6.dp))
                            StatusPill(txStatusLabel(t.status), txStatusColor(t.status))
                        }

                        SectionCard {
                            InfoRow("Meio", methodLabel(t.method))
                            InfoRow("Criada em", formatDate(t.createdAt))
                            InfoRow("Paga em", formatDate(t.paidAt))
                            InfoRow("Pagador", t.customer?.name)
                            InfoRow("Documento", t.customer?.documentNumber)
                            InfoRow("E-mail", t.customer?.email)
                        }

                        jsonText(t.refusedReason)?.let { reason ->
                            SectionCard {
                                Text("Motivo da recusa", fontWeight = FontWeight.SemiBold, color = Danger)
                                Text(reason, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        val pixCode = t.pix?.qrcode
                        if (t.status in PENDING_STATES && !pixCode.isNullOrBlank()) {
                            SectionCard {
                                Text("Pix copia e cola", color = Muted, style = MaterialTheme.typography.labelMedium)
                                Text(pixCode, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                                InfoRow("Expira em", formatDate(t.pix?.expiresAt))
                                OutlinedButton(onClick = { copy(pixCode, "Código Pix copiado") }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Copiar código")
                                }
                            }
                        }

                        SectionCard {
                            InfoRow("ID", t.id, onCopy = { copy(t.id, "ID copiado") })
                            InfoRow("ID adquirente", t.externalId, onCopy = t.externalId?.let { e -> { copy(e, "Copiado") } })
                            InfoRow("End to end", t.pix?.endToEndId, onCopy = t.pix?.endToEndId?.let { e -> { copy(e, "Copiado") } })
                        }
                    }
                }
            }
        }
    }
}
