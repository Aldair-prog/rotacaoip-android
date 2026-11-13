@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.strattonpay.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.strattonpay.app.R
import br.com.strattonpay.app.data.ApiException
import br.com.strattonpay.app.data.CredentialStore
import br.com.strattonpay.app.data.StrattonApi
import br.com.strattonpay.app.notify.AppSettings
import br.com.strattonpay.app.notify.Notifier
import br.com.strattonpay.app.notify.NotifyScheduler
import br.com.strattonpay.app.web.PanelController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// =====================================================================
// Tela principal: painel dentro do app
// =====================================================================

@Composable
fun PanelScreen(panel: PanelController, store: CredentialStore) {
    var showSettings by remember { mutableStateOf(false) }

    BackHandler(enabled = showSettings || panel.canGoBack) {
        if (showSettings) showSettings = false else panel.goBack()
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            // Barra fina com a logo
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Bg)
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_emblem),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("STRATTON", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 3.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { panel.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Recarregar", tint = Muted)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Configurações", tint = Muted)
                }
            }
            if (panel.progress in 1..99) {
                LinearProgressIndicator(
                    progress = { panel.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = BrandLight,
                    trackColor = Bg,
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
            AndroidView(
                factory = { panel.webView },
                modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
            )
        }

        // Abertura: logo + "Carregando..."
        AnimatedVisibility(visible = !panel.firstLoadDone, exit = fadeOut()) {
            SplashView()
        }

        // Sem internet
        val err = panel.error
        if (err != null) {
            Column(
                Modifier.fillMaxSize().background(Bg).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                BrandMark(width = 220.dp)
                Spacer(Modifier.height(32.dp))
                Text(err, color = TextMain, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { panel.reload() }) { Text("Tentar de novo") }
            }
        }

        if (showSettings) {
            SettingsScreen(store = store, panel = panel, onBack = { showSettings = false })
        }
    }
}

@Composable
fun SplashView() {
    Column(
        Modifier.fillMaxSize().background(Bg).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark(width = 260.dp)
        Spacer(Modifier.height(40.dp))
        CircularProgressIndicator(color = BrandLight, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(14.dp))
        Text("Carregando...", color = Muted)
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark(width = 260.dp)
        Spacer(Modifier.height(56.dp))
        Button(onClick = onUnlock, modifier = Modifier.height(52.dp)) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Desbloquear")
        }
    }
}

// =====================================================================
// Configurações
// =====================================================================

private val intervals = listOf(2, 5, 15, 30)

@Composable
fun SettingsScreen(store: CredentialStore, panel: PanelController, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { AppSettings(context) }

    // Login salvo / bloqueio
    var saveLogin by remember { mutableStateOf(store.savePanelLogin) }
    var savedUser by remember { mutableStateOf(store.panelUser) }
    var lock by remember { mutableStateOf(store.lockEnabled) }
    var askLogout by remember { mutableStateOf(false) }

    // Notificações
    var hasKey by remember { mutableStateOf(store.load() != null) }
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(settings.notifyEnabled && Notifier.canNotify(context) && hasKey) }
    var sales by remember { mutableStateOf(settings.notifySales) }
    var withdraws by remember { mutableStateOf(settings.notifyWithdraws) }
    var disputes by remember { mutableStateOf(settings.notifyDisputes) }
    var interval by remember { mutableIntStateOf(settings.intervalMinutes) }
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

    val requestOn: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && !Notifier.hasPermission(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!Notifier.canNotify(context)) {
            blocked = true
        } else {
            turnOn()
        }
    }

    val connect: () -> Unit = {
        keyError = if (clientId.isBlank() || secret.isBlank()) "Preencha o Client ID e o Client Secret" else null
        if (keyError == null) {
            connecting = true
            val creds = CredentialStore.Creds(CredentialStore.DEFAULT_BASE_URL, clientId.trim(), secret.trim())
            scope.launch {
                var ok = false
                try {
                    StrattonApi(creds.baseUrl, creds.clientId, creds.clientSecret).company()
                    ok = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    if (e.code == 403 && !e.message.contains("bloqueado")) {
                        ok = true
                    } else {
                        keyError = e.message
                    }
                } catch (e: Exception) {
                    keyError = e.message ?: "Não foi possível conectar"
                }
                connecting = false
                if (ok) {
                    store.saveApi(creds)
                    hasKey = true
                    clientId = ""
                    secret = ""
                    requestOn()
                }
            }
        }
    }

    val openSystemSettings: () -> Unit = {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        ScreenScaffold(title = "Configurações", onBack = onBack) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ---------- Login ----------
                SectionCard {
                    Text("Login", fontWeight = FontWeight.SemiBold)
                    SwitchRow(
                        "Salvar usuário e senha",
                        "Preenche o login do painel sozinho. Fica criptografado só neste celular.",
                        saveLogin,
                    ) {
                        saveLogin = it
                        store.savePanelLogin = it
                        if (!it) savedUser = null
                    }
                    if (saveLogin) {
                        Text(
                            if (savedUser != null) "Salvo: $savedUser" else "Será salvo no próximo login no painel.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (savedUser != null) {
                            TextButton(onClick = {
                                store.clearPanelLogin()
                                savedUser = null
                            }) { Text("Apagar usuário e senha salvos", color = Danger) }
                        }
                    }
                    HorizontalDivider(color = Outline)
                    SwitchRow("Pedir digital ao abrir", "Digital, rosto ou senha do celular", lock) {
                        lock = it
                        store.lockEnabled = it
                    }
                    HorizontalDivider(color = Outline)
                    TextButton(onClick = { askLogout = true }) { Text("Sair do painel neste celular", color = Danger) }
                }

                // ---------- Notificações ----------
                SectionCard {
                    Text("Notificações", fontWeight = FontWeight.SemiBold)
                    if (!hasKey) {
                        Text(
                            "Para avisar de vendas e saques com o app fechado, o celular precisa de uma chave de API. " +
                                "Crie no painel em Configurações → Chaves de API com os escopos TRANSACTIONS e WITHDRAWS " +
                                "(só leitura basta) e cole aqui.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            label = { Text("Client ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it },
                            label = { Text("Client Secret") },
                            singleLine = true,
                            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showSecret = !showSecret }) {
                                    Icon(if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ErrorText(keyError)
                        PrimaryButton("Ativar notificações", onClick = connect, loading = connecting)
                    } else {
                        SwitchRow("Ativar notificações", "Avisos mesmo com o app fechado", enabled) { on ->
                            if (on) requestOn() else turnOff()
                        }
                        if (blocked) {
                            Text(
                                "As notificações estão bloqueadas para o Stratton no Android. Libere e volte aqui.",
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
                            OutlinedButton(onClick = { Notifier.test(context) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Enviar notificação de teste")
                            }
                        }
                        TextButton(onClick = {
                            turnOff()
                            store.clearApi()
                            hasKey = false
                        }) { Text("Remover chave de API", color = Danger) }
                    }
                }

                Text(
                    "Stratton · versão 1.1",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    if (askLogout) {
        ConfirmDialog(
            title = "Sair do painel?",
            confirmText = "Sair",
            onDismiss = { askLogout = false },
            onConfirm = {
                askLogout = false
                panel.clearSiteData()
                onBack()
            },
        ) {
            Text("A sessão do painel é encerrada neste celular. O usuário e a senha salvos continuam, para entrar de novo rápido.")
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
            colors = SwitchDefaults.colors(checkedTrackColor = Brand, checkedThumbColor = Color.White),
        )
    }
}
