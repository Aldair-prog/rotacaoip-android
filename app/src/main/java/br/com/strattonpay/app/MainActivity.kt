package br.com.strattonpay.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import br.com.strattonpay.app.data.CredentialStore
import br.com.strattonpay.app.notify.Notifier
import br.com.strattonpay.app.ui.LockScreen
import br.com.strattonpay.app.ui.StrattonApp
import br.com.strattonpay.app.ui.StrattonTheme

class MainActivity : FragmentActivity() {

    /** Rota aberta por toque em notificação (ex.: "tx/<id>"). */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannels(this)
        if (savedInstanceState == null) pendingRoute = intent?.getStringExtra(Notifier.EXTRA_ROUTE)
        val store = CredentialStore(applicationContext)

        setContent {
            StrattonTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val lockAvailable = remember { canUseDeviceAuth() }
                    var unlocked by rememberSaveable { mutableStateOf(!lockAvailable) }

                    if (!unlocked) {
                        LockScreen(onUnlock = { authenticate("Desbloquear Stratton") { unlocked = true } })
                    } else {
                        StrattonApp(
                            store = store,
                            requireAuth = { title, onOk ->
                                if (lockAvailable) authenticate(title, onOk) else onOk()
                            },
                            pendingRoute = pendingRoute,
                            onRouteConsumed = { pendingRoute = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_ROUTE)?.let { pendingRoute = it }
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
        private val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }
}
