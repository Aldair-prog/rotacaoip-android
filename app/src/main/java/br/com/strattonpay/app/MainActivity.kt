package br.com.strattonpay.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import br.com.strattonpay.app.data.CredentialStore
import br.com.strattonpay.app.notify.AppSettings
import br.com.strattonpay.app.notify.Notifier
import br.com.strattonpay.app.notify.NotifyScheduler
import br.com.strattonpay.app.ui.LockScreen
import br.com.strattonpay.app.ui.PanelScreen
import br.com.strattonpay.app.ui.StrattonTheme
import br.com.strattonpay.app.web.PanelController

class MainActivity : FragmentActivity() {

    private lateinit var store: CredentialStore
    private lateinit var panel: PanelController
    private var unlocked by mutableStateOf(false)
    private var lastAuthAt = 0L

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        panel.onFileChosen(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannels(this)
        // Só na versão de teste: permite inspecionar o painel pelo computador.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        store = CredentialStore(applicationContext)
        panel = PanelController(this, store) { intent -> fileChooser.launch(intent) }
        panel.start(savedInstanceState)

        val deviceAuth = canUseDeviceAuth()
        panel.biometricLogin = deviceAuth
        val lockAvailable = store.lockEnabled && deviceAuth
        unlocked = !lockAvailable || savedInstanceState?.getBoolean(KEY_UNLOCKED) == true
        handleTestExtras(intent)

        if (AppSettings(this).notifyEnabled && store.load() != null) NotifyScheduler.ensure(this)

        setContent {
            StrattonTheme {
                // O painel já vai carregando por trás enquanto pede a digital.
                PanelScreen(
                    panel = panel,
                    store = store,
                    unlocked = unlocked,
                    biometricAvailable = deviceAuth,
                    recentlyUnlocked = { SystemClock.elapsedRealtime() - lastAuthAt < 60_000 },
                    authenticate = { title, onOk -> authenticate(title, onOk) },
                )
                if (!unlocked) {
                    LockScreen(onUnlock = { authenticate("Desbloquear ${BuildConfig.BRAND_NAME}") { unlocked = true } })
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, unlocked)
        panel.webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        panel.persist()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Toque em notificação com o app aberto: traz o painel para frente atualizado.
        if (intent.hasExtra(Notifier.EXTRA_ROUTE)) panel.reload()
        handleTestExtras(intent)
    }

    /** Atalhos usados só pelos testes automáticos (versão de teste). */
    private fun handleTestExtras(intent: Intent?) {
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("test_setup", false) == true) panel.showSetupRequest = true
    }

    override fun onDestroy() {
        panel.webView.destroy()
        super.onDestroy()
    }

    /** Digital, rosto ou a senha/PIN do próprio celular. */
    private fun canUseDeviceAuth(): Boolean =
        BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate(title: String, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lastAuthAt = SystemClock.elapsedRealtime()
                    onSuccess()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Use a digital, o rosto ou a senha do celular")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    companion object {
        private const val KEY_UNLOCKED = "unlocked"
        private val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
