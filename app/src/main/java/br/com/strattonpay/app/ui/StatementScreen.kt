@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.strattonpay.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.strattonpay.app.data.StatementItem
import br.com.strattonpay.app.data.StrattonApi
import kotlinx.coroutines.launch

@Composable
fun StatementScreen(api: StrattonApi, onOpenTx: (String) -> Unit, onOpenWithdraw: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<StatementItem>()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        loading = true
        error = null
        when (val r = load { api.statement(cursor = null) }) {
            is Load.Ok -> {
                rows = r.data.items
                next = r.data.meta?.nextCursor
            }
            is Load.Err -> {
                error = r.message
                rows = emptyList()
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
                when (val r = load { api.statement(cursor = cursor) }) {
                    is Load.Ok -> {
                        rows = rows + r.data.items
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
        title = "Extrato",
        actions = { IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Atualizar") } },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && rows.isEmpty() -> LoadingBox()
                error != null && rows.isEmpty() -> ErrorBox(error ?: "", onRetry = { refresh++ })
                rows.isEmpty() -> EmptyBox("Nenhum lançamento ainda")
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    items(rows, key = { it.id }) { row ->
                        StatementRow(row, onClick = {
                            when {
                                row.transactionId != null -> onOpenTx(row.transactionId)
                                row.withdrawId != null -> onOpenWithdraw(row.withdrawId)
                                else -> {}
                            }
                        })
                        HorizontalDivider(color = Outline)
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> CircularProgressIndicator(Modifier.size(24.dp), color = Gold)
                                next != null -> OutlinedButton(onClick = loadMore) { Text("Carregar mais") }
                                else -> Text("Fim do extrato", color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatementRow(row: StatementItem, onClick: () -> Unit) {
    val credit = row.entrada > 0
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    statementLabel(row.type, row.description),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatDate(row.postedAt), color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                (if (credit) "+ " else "− ") + money(if (credit) row.entrada else row.saida),
                fontWeight = FontWeight.SemiBold,
                color = if (credit) Success else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
