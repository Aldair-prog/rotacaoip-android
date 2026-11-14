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
import androidx.compose.animation.fadeIn
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
import br.com.strattonpay.app.BuildConfig
import br.com.strattonpay.app.R
import br.com.strattonpay.app.data.CredentialStore
import br.com.strattonpay.app.notify.AppSettings
import br.com.strattonpay.app.notify.Notifier
import br.com.strattonpay.app.push.PushService
import br.com.strattonpay.app.push.PushStore
import androidx.compose.foundation.shape.CircleShape
import br.com.strattonpay.app.web.PanelController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// =====================================================================
// Tela principal: painel dentro do app
// =====================================================================

@Composable
fun PanelScreen(
    panel: PanelController,
    store: CredentialStore,
    unlocked: Boolean,
    biometricAvailable: Boolean,
    recentlyUnlocked: () -> Boolean,
    authenticate: (String, () -> Unit) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var autoLoginTried by remember { mutableStateOf(false) }

    // Entrar com digital: a sessão do painel caiu e há usuário/senha salvos ->
    // pede a digital (ou aproveita o desbloqueio que acabou de acontecer) e entra sozinho.
    val loginWithBiometric: () -> Unit = {
        if (recentlyUnlocked()) panel.submitSavedLogin()
        else authenticate("Entrar no ${BuildConfig.BRAND_NAME}") { panel.submitSavedLogin() }
    }
    LaunchedEffect(panel.loginFormVisible, unlocked) {
        if (!panel.loginFormVisible) {
            autoLoginTried = false
        } else if (unlocked && biometricAvailable && !autoLoginTried && panel.hasSavedLogin()) {
            autoLoginTried = true
            delay(400)
            loginWithBiometric()
        }
    }

    // Só nos testes automáticos: abre as Configurações direto.
    LaunchedEffect(panel.showSetupRequest) {
        if (panel.showSetupRequest) {
            showSettings = true
            panel.showSetupRequest = false
        }
    }

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
                    .height(54.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_emblem),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(BuildConfig.BRAND_WORDMARK, color = TextMain, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 3.5.sp)
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
                HorizontalDivider(thickness = 2.dp, color = Surface1)
            }
            AndroidView(
                factory = { panel.webView },
                modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
            )
        }

        // Botão "Entrar com digital" sobre a tela de login do painel
        AnimatedVisibility(
            visible = panel.loginFormVisible && biometricAvailable && panel.hasSavedLogin() && !showSettings,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ExtendedFloatingActionButton(
                onClick = loginWithBiometric,
                containerColor = Brand,
                contentColor = Color.White,
                icon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
                text = { Text("Entrar com digital", fontWeight = FontWeight.SemiBold) },
                modifier = Modifier.navigationBarsPadding().padding(bottom = 24.dp),
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
        Text("Toque para desbloquear", color = Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(14.dp))
        Button(onClick = onUnlock, modifier = Modifier.height(52.dp)) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Desbloquear")
        }
    }
}

private fun openNotificationSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

/** Ignorar a otimização de bateria (Xiaomi e outros fecham apps em segundo plano). */
private fun openBatterySettings(context: android.content.Context) {
    val intents = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // tenta o próximo
        }
    }
}

// =====================================================================
// Configurações
// =====================================================================



@Composable
fun SettingsScreen(store: CredentialStore, panel: PanelController, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }

    // Login salvo / bloqueio
    var saveLogin by remember { mutableStateOf(store.savePanelLogin) }
    var savedUser by remember { mutableStateOf(store.panelUser) }
    var lock by remember { mutableStateOf(store.lockEnabled) }
    var askLogout by remember { mutableStateOf(false) }

    // Notificações (vêm do próprio painel, sem chave de API)
    val scope = rememberCoroutineScope()
    val pushStore = remember { PushStore(context) }
    var enabled by remember { mutableStateOf(settings.notifyEnabled) }
    var registered by remember { mutableStateOf(pushStore.subscribed) }
    var allowed by remember { mutableStateOf(Notifier.canNotify(context)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = Notifier.canNotify(context)
        if (!allowed) openNotificationSettings(context)
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
                    Text(
                        "Os avisos vêm do próprio painel (Pix pago, saques e tudo que ele notifica), " +
                            "na hora e sem precisar de chave de API.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    StatusLine(
                        ok = allowed,
                        okText = "Permissão do Android: liberada",
                        badText = "Permissão do Android: falta liberar",
                    )
                    if (!allowed) {
                        OutlinedButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= 33 && !Notifier.hasPermission(context)) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    openNotificationSettings(context)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Liberar notificações") }
                    }
                    StatusLine(
                        ok = registered,
                        okText = "Celular registrado no painel",
                        badText = "Ainda não registrado no painel",
                    )
                    if (!registered) {
                        Text(
                            "Entre no painel e ative as notificações quando ele pedir (ou pelo sininho / configurações " +
                                "de notificação do painel). O app faz o resto sozinho.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    HorizontalDivider(color = Outline)
                    SwitchRow("Receber neste celular", "Desligue para silenciar os avisos aqui", enabled) { on ->
                        enabled = on
                        settings.notifyEnabled = on
                        if (on) PushService.ensure(context) else PushService.stop(context)
                    }
                    if (registered && enabled) {
                        OutlinedButton(
                            onClick = {
                                testing = true
                                testResult = null
                                scope.launch {
                                    val ok = PushService.sendTest(context)
                                    testing = false
                                    testResult = if (ok) "Teste enviado: a notificação deve aparecer em segundos." else "Sem internet para enviar o teste."
                                }
                            },
                            enabled = !testing,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (testing) "Enviando…" else "Enviar notificação de teste") }
                        testResult?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                    HorizontalDivider(color = Outline)
                    Text(
                        "Xiaomi e outros celulares fecham apps em segundo plano. Para não perder avisos, " +
                            "deixe o ${BuildConfig.BRAND_NAME} sem restrição de bateria e com início automático.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { openBatterySettings(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Liberar uso de bateria")
                    }
                }

                Text(
                    "${BuildConfig.BRAND_NAME} · versão ${BuildConfig.VERSION_NAME}",
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
private fun StatusLine(ok: Boolean, okText: String, badText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (ok) Success else Warning, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(if (ok) okText else badText, style = MaterialTheme.typography.bodyMedium)
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
