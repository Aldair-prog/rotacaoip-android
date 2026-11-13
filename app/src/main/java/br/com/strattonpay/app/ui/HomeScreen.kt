@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.strattonpay.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.strattonpay.app.data.Balance
import br.com.strattonpay.app.data.Company
import br.com.strattonpay.app.data.StrattonApi
import br.com.strattonpay.app.data.Transaction
import br.com.strattonpay.app.data.TransactionPage
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    api: StrattonApi,
    onLogout: () -> Unit,
    onCharge: () -> Unit,
    onWithdraw: () -> Unit,
    onPayQr: () -> Unit,
    onOpenTx: (String) -> Unit,
    onSeeAllTx: () -> Unit,
    onSettings: () -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var company by remember { mutableStateOf<Load<Company>>(Load.Loading) }
    var balance by remember { mutableStateOf<Load<Balance>>(Load.Loading) }
    var recent by remember { mutableStateOf<Load<TransactionPage>>(Load.Loading) }
    var askLogout by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        balance = Load.Loading
        recent = Load.Loading
        launch { company = load { api.company() } }
        launch { balance = load { api.balance() } }
        launch { recent = load { api.transactions(cursor = null, perPage = 5) } }
    }

    val c = company
    val title = if (c is Load.Ok) c.data.tradeName ?: c.data.name ?: "Stratton" else "Stratton"

    ScreenScaffold(
        title = title,
        actions = {
            IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Atualizar") }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Configurações") }
            IconButton(onClick = { askLogout = true }) { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sair") }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BalanceCard(balance, onRetry = { refresh++ })

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction("Cobrar Pix", Icons.Filled.QrCode2, onCharge, Modifier.weight(1f))
                QuickAction("Sacar", Icons.Filled.Payments, onWithdraw, Modifier.weight(1f))
                QuickAction("Pagar QR", Icons.Filled.QrCodeScanner, onPayQr, Modifier.weight(1f))
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Últimas vendas", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSeeAllTx) { Text("Ver todas") }
                }
                when (val r = recent) {
                    Load.Loading -> LoadingBox()
                    is Load.Err -> ErrorBox(r.message, onRetry = { refresh++ })
                    is Load.Ok ->
                        if (r.data.items.isEmpty()) {
                            EmptyBox("Nenhuma venda ainda")
                        } else {
                            r.data.items.forEachIndexed { i, t ->
                                if (i > 0) HorizontalDivider(color = Outline)
                                TxRow(t) { onOpenTx(t.id) }
                            }
                        }
                }
            }

            if (c is Load.Ok) {
                SectionCard {
                    Text("Suas taxas", fontWeight = FontWeight.SemiBold)
                    InfoRow("Pix (entrada)", feeText(c.data.fees?.incoming?.pix))
                    InfoRow("Saque Pix", feeText(c.data.fees?.outgoing))
                    val doc = c.data.document
                    if (doc?.number != null) InfoRow(doc.type ?: "Documento", doc.number)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (askLogout) {
        ConfirmDialog(
            title = "Sair da conta?",
            confirmText = "Sair",
            onConfirm = { askLogout = false; onLogout() },
            onDismiss = { askLogout = false },
        ) {
            Text("As notificações param até você entrar de novo. Se \"Lembrar Client ID e Secret\" estiver ligado nas Configurações, seus dados continuam salvos para o próximo login.")
        }
    }
}

@Composable
private fun BalanceCard(balance: Load<Balance>, onRetry: () -> Unit) {
    SectionCard {
        Text("Saldo disponível", color = Muted, style = MaterialTheme.typography.labelLarge)
        when (balance) {
            Load.Loading -> Text("•••••", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Muted)
            is Load.Err -> ErrorBox(balance.message, onRetry = onRetry)
            is Load.Ok -> {
                Text(
                    money(balance.data.available),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (balance.data.available < 0) Danger else Gold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column {
                        Text("Total", color = Muted, style = MaterialTheme.typography.labelMedium)
                        Text(money(balance.data.total), fontWeight = FontWeight.Medium)
                    }
                    Column {
                        Text("Reservado em saques", color = Muted, style = MaterialTheme.typography.labelMedium)
                        Text(money(balance.data.reserved), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(vertical = 16.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = Gold)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun TxRow(t: Transaction, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                t.customer?.name?.takeIf { it.isNotBlank() } ?: "Cobrança ${methodLabel(t.method)}",
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${methodLabel(t.method)} · ${formatDate(t.paidAt ?: t.createdAt)}",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(money(t.amount), fontWeight = FontWeight.SemiBold)
            StatusPill(txStatusLabel(t.status), txStatusColor(t.status))
        }
    }
}
