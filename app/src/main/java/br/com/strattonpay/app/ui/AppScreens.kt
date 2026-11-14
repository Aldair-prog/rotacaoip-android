@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.text.ExperimentalTextApi::class)

package br.com.strattonpay.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
    val haptics = LocalHapticFeedback.current
    var bioLoginOn by remember { mutableStateOf(store.biometricLoginEnabled) }
    LaunchedEffect(showSettings) {
        bioLoginOn = store.biometricLoginEnabled
        panel.biometricLogin = biometricAvailable && bioLoginOn
    }
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
        } else if (unlocked && biometricAvailable && bioLoginOn && !autoLoginTried && panel.hasSavedLogin()) {
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
            val barShine = rememberShine(periodMs = 7000, delayMs = 1500)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black, Bg)))
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    BrandAura(size = 46.dp, intensity = 0.55f)
                    Image(
                        painter = painterResource(R.drawable.logo_emblem),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).shineSweep(barShine),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    BuildConfig.BRAND_WORDMARK,
                    style = TextStyle(
                        brush = ChromeBrush,
                        fontFamily = Inter,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        letterSpacing = 4.sp,
                    ),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { panel.reload() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Recarregar", tint = Muted)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Configurações", tint = Muted)
                }
            }
            // Linha de luz: corre enquanto a página carrega, fica acesa e calma no resto do tempo.
            EnergyLine(height = 2.dp, speedMs = 900, active = panel.progress in 1..99)
            AndroidView(
                factory = { panel.webView },
                modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
            )
        }

        // Botão "Entrar com digital" sobre a tela de login do painel
        AnimatedVisibility(
            visible = panel.loginFormVisible && biometricAvailable && bioLoginOn && panel.hasSavedLogin() && !showSettings,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BiometricButton(onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                loginWithBiometric()
            })
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

/** Abertura: aura pulsando, reflexo passando no cromo e o nome surgindo letra por letra. */
@Composable
fun SplashView() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scale by animateFloatAsState(if (visible) 1f else 0.82f, tween(700, easing = FastOutSlowInEasing), label = "logoScale")
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(500), label = "logoAlpha")
    val shine = rememberShine(periodMs = 2200, delayMs = 250)

    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF14080B).takeIf { Brand.red > Brand.blue } ?: Color(0xFF0E0816), Color.Black)))) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                BrandAura(size = 300.dp)
                Image(
                    painter = painterResource(R.drawable.logo_emblem),
                    contentDescription = BuildConfig.BRAND_NAME,
                    modifier = Modifier
                        .width(190.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                        .shineSweep(shine),
                )
            }
            Spacer(Modifier.height(18.dp))
            TypewriterWordmark(BuildConfig.BRAND_WORDMARK)
            Spacer(Modifier.height(44.dp))
            EnergyLine(modifier = Modifier.width(180.dp), height = 2.dp)
            Spacer(Modifier.height(12.dp))
            Text("Carregando...", color = Muted, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
        }
        CopyrightLine(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp))
    }
}

/** "© 2026 OPERAÇÕES DIGITAIS - …" — titular dos direitos do app. */
@Composable
fun CopyrightLine(modifier: Modifier = Modifier) {
    val year = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
    Text(
        "© $year ${BuildConfig.COPYRIGHT_HOLDER}",
        color = Muted.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.sp,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/** Tela de digital: aura respirando e anel de scanner girando. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    val shine = rememberShine(periodMs = 3200, delayMs = 600)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF14080B).takeIf { Brand.red > Brand.blue } ?: Color(0xFF0E0816), Color.Black)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onUnlock),
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                BrandAura(size = 280.dp)
                Image(
                    painter = painterResource(R.drawable.logo_emblem),
                    contentDescription = BuildConfig.BRAND_NAME,
                    modifier = Modifier.width(170.dp).shineSweep(shine),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                BuildConfig.BRAND_WORDMARK,
                style = TextStyle(brush = ChromeBrush, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 7.sp),
            )
            Spacer(Modifier.height(72.dp))
            Box(contentAlignment = Alignment.Center) {
                ScannerRing(size = 92.dp)
                Box(
                    Modifier.size(72.dp).background(Brand.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = "Desbloquear", tint = TextMain, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Toque para desbloquear", color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
        CopyrightLine(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp))
    }
}

/**
 * Boas-vindas (só na primeira vez): explica e pede as permissões,
 * e deixa a pessoa escolher se quer entrar com digital/rosto.
 */
@Composable
fun OnboardingScreen(store: CredentialStore, biometricAvailable: Boolean, onContinue: () -> Unit) {
    var bio by remember { mutableStateOf(store.biometricLoginEnabled && store.savePanelLogin) }
    val shine = rememberShine(periodMs = 3600, delayMs = 400)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF14080B).takeIf { Brand.red > Brand.blue } ?: Color(0xFF0E0816), Color.Black))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                BrandAura(size = 190.dp, intensity = 0.8f)
                Image(
                    painter = painterResource(R.drawable.logo_emblem),
                    contentDescription = null,
                    modifier = Modifier.width(110.dp).shineSweep(shine),
                )
            }
            Text(
                "Bem-vindo ao ${BuildConfig.BRAND_NAME}",
                style = TextStyle(brush = ChromeBrush, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp),
                textAlign = TextAlign.Center,
            )
            Text(
                "Libere o que o app precisa para funcionar por completo. Dá para mudar tudo depois na engrenagem.",
                color = Muted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            PermissionItem(Icons.Filled.Notifications, "Notificações", "Aviso na hora de cada Pix pago, saque e alerta do painel.")
            PermissionItem(Icons.Filled.PhotoCamera, "Câmera", "Foto de documento, comprovante e leitura de QR Code dentro do painel.")
            if (biometricAvailable) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = BrandLight, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Entrar com digital ou rosto", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Salva seu usuário e senha criptografados neste celular. Quando precisar entrar de novo, é só a biometria.",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = bio,
                            onCheckedChange = {
                                bio = it
                                store.biometricLoginEnabled = it
                                if (it) store.savePanelLogin = true
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Brand, checkedThumbColor = Color.White),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Permitir e continuar", onClick = onContinue)
            CopyrightLine(Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun PermissionItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(Brand.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = BrandLight)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(text, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Botão "Entrar com digital" com a borda neon dando a volta. */
@Composable
fun BiometricButton(onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "neon")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "neonAngle")
    Box(
        Modifier
            .navigationBarsPadding()
            .padding(bottom = 28.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.horizontalGradient(listOf(BrandDark, Brand)))
            .neonBorder({ angle }, radius = 30.dp, width = 2.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text("Entrar com digital ou rosto", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
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

private fun openAppSettings(context: android.content.Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        // sem tela de configurações do app
    }
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
    var bioLogin by remember { mutableStateOf(store.biometricLoginEnabled) }
    var cameraOk by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraOk = ok
        if (!ok) openAppSettings(context)
    }
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
                // ---------- Cabeçalho ----------
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        BrandAura(size = 150.dp, intensity = 0.7f)
                        Image(
                            painter = painterResource(R.drawable.logo_emblem),
                            contentDescription = null,
                            modifier = Modifier.width(84.dp).shineSweep(rememberShine(periodMs = 4200, delayMs = 800)),
                        )
                    }
                    Text(
                        BuildConfig.BRAND_WORDMARK,
                        style = TextStyle(brush = ChromeBrush, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 6.sp),
                    )
                    Text("versão ${BuildConfig.VERSION_NAME}", color = Muted, style = MaterialTheme.typography.labelSmall)
                }

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
                    SwitchRow(
                        "Entrar com digital ou rosto",
                        "Quando a sessão cair, o app usa a biometria e entra sozinho com o usuário e a senha salvos",
                        bioLogin && saveLogin,
                    ) {
                        bioLogin = it
                        store.biometricLoginEnabled = it
                        if (it && !saveLogin) {
                            saveLogin = true
                            store.savePanelLogin = true
                        }
                    }
                    HorizontalDivider(color = Outline)
                    SwitchRow("Pedir digital ou rosto ao abrir", "Ninguém abre o app sem a sua biometria ou a senha do celular", lock) {
                        lock = it
                        store.lockEnabled = it
                    }
                    HorizontalDivider(color = Outline)
                    TextButton(onClick = { askLogout = true }) { Text("Sair do painel neste celular", color = Danger) }
                }

                // ---------- Câmera ----------
                SectionCard {
                    Text("Câmera", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Usada pelo painel para foto de documento, envio de comprovante e leitura de QR Code.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    StatusLine(ok = cameraOk, okText = "Câmera: liberada", badText = "Câmera: não liberada")
                    if (!cameraOk) {
                        OutlinedButton(onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Liberar câmera")
                        }
                    }
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
                    "© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} ${BuildConfig.COPYRIGHT_HOLDER}\n" +
                        "Todos os direitos reservados · versão ${BuildConfig.VERSION_NAME}",
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
