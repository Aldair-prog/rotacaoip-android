@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.strattonpay.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.strattonpay.app.data.CredentialStore
import br.com.strattonpay.app.notify.AppSettings
import br.com.strattonpay.app.notify.Notifier
import br.com.strattonpay.app.notify.NotifyScheduler

private val intervals = listOf(2, 5, 15, 30)

@Composable
fun SettingsScreen(store: CredentialStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }

    var enabled by remember { mutableStateOf(settings.notifyEnabled && Notifier.canNotify(context)) }
    var sales by remember { mutableStateOf(settings.notifySales) }
    var withdraws by remember { mutableStateOf(settings.notifyWithdraws) }
    var disputes by remember { mutableStateOf(settings.notifyDisputes) }
    var interval by remember { mutableIntStateOf(settings.intervalMinutes) }
    var rememberLogin by remember { mutableStateOf(store.remember) }
    var blocked by remember { mutableStateOf(false) }

    fun turnOn() {
        Notifier.ensureChannels(context)
        settings.notifyEnabled = true
        settings.resetTracking()
        NotifyScheduler.restart(context)
        enabled = true
        blocked = false
        Notifier.test(context)
    }

    fun turnOff() {
        settings.notifyEnabled = false
        NotifyScheduler.stop(context)
        enabled = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && Notifier.canNotify(context)) turnOn() else blocked = true
    }

    val openSystemSettings: () -> Unit = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    ScreenScaffold(title = "Configurações", onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard {
                Text("Notificações", fontWeight = FontWeight.SemiBold)
                SwitchRow("Ativar notificações", "Avisos mesmo com o app fechado", enabled) { on ->
                    if (!on) {
                        turnOff()
                    } else if (Build.VERSION.SDK_INT >= 33 && !Notifier.hasPermission(context)) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else if (!Notifier.canNotify(context)) {
                        blocked = true
                    } else {
                        turnOn()
                    }
                }

                if (blocked) {
                    Text(
                        "As notificações estão bloqueadas para o Stratton no Android. Libere nas configurações do celular e volte aqui.",
                        color = Warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = openSystemSettings) { Text("Abrir configurações do Android") }
                }

                if (enabled) {
                    HorizontalDivider(color = Outline)
                    SwitchRow("Vendas pagas", "Quando um Pix cair", sales) {
                        sales = it
                        settings.notifySales = it
                    }
                    SwitchRow("Saques", "Saque pago ou recusado", withdraws) {
                        withdraws = it
                        settings.notifyWithdraws = it
                    }
                    SwitchRow("Contestações", "MED, pré-chargeback e chargeback", disputes) {
                        disputes = it
                        settings.notifyDisputes = it
                    }
                    HorizontalDivider(color = Outline)
                    Text("Checar a cada", color = Muted, style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        intervals.forEach { m ->
                            FilterChip(
                                selected = interval == m,
                                onClick = {
                                    interval = m
                                    settings.intervalMinutes = m
                                    NotifyScheduler.restart(context)
                                },
                                label = { Text("$m min") },
                            )
                        }
                    }
                    Text(
                        "Intervalos menores avisam mais rápido e gastam um pouco mais de bateria. " +
                            "Com o celular parado por muito tempo, o Android pode atrasar algumas checagens.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { Notifier.test(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Enviar notificação de teste")
                    }
                }
            }

            SectionCard {
                Text("Login", fontWeight = FontWeight.SemiBold)
                SwitchRow(
                    "Lembrar Client ID e Secret",
                    "Ao sair da conta, os dados continuam salvos (criptografados) para entrar de novo com um toque",
                    rememberLogin,
                ) {
                    rememberLogin = it
                    store.remember = it
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Gold, checkedThumbColor = Bg),
        )
    }
}
