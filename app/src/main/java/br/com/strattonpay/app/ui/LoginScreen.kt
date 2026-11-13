@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.strattonpay.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.strattonpay.app.data.ApiException
import br.com.strattonpay.app.data.CredentialStore
import br.com.strattonpay.app.data.StrattonApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    saved: CredentialStore.Creds?,
    onLoggedIn: (creds: CredentialStore.Creds, remember: Boolean) -> Unit,
    onForget: () -> Unit,
) {
    var clientId by rememberSaveable { mutableStateOf(saved?.clientId.orEmpty()) }
    var secret by rememberSaveable { mutableStateOf(saved?.clientSecret.orEmpty()) }
    var baseUrl by rememberSaveable { mutableStateOf(saved?.baseUrl ?: CredentialStore.DEFAULT_BASE_URL) }
    var rememberMe by rememberSaveable { mutableStateOf(true) }
    var hasSaved by rememberSaveable { mutableStateOf(saved != null) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val submit: () -> Unit = {
        error = when {
            clientId.isBlank() -> "Informe o Client ID"
            secret.isBlank() -> "Informe o Client Secret"
            !baseUrl.trim().startsWith("https://") -> "A URL da API precisa começar com https://"
            else -> null
        }
        if (error == null) {
            val creds = CredentialStore.Creds(baseUrl.trim().trimEnd('/'), clientId.trim(), secret.trim())
            loading = true
            scope.launch {
                try {
                    StrattonApi(creds.baseUrl, creds.clientId, creds.clientSecret).company()
                    onLoggedIn(creds, rememberMe)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    // 403 por falta de escopo COMPANY:READ ainda prova que a chave é válida.
                    if (e.code == 403 && !e.message.contains("bloqueado")) {
                        onLoggedIn(creds, rememberMe)
                    } else {
                        error = e.message
                    }
                } catch (e: Exception) {
                    error = e.message ?: "Não foi possível entrar"
                } finally {
                    loading = false
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        BrandMark(Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            if (hasSaved) "Bem-vindo de volta! Seus dados estão salvos — é só entrar."
            else "Entre com a chave de API da sua conta StrattonPay.",
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
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
                    Icon(
                        if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showSecret) "Esconder" else "Mostrar",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it })
            Text("Salvar Client ID e Secret neste celular", modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Esconder avançado" else "Opções avançadas")
            }
            Spacer(Modifier.weight(1f))
            if (hasSaved) {
                TextButton(onClick = {
                    onForget()
                    hasSaved = false
                    clientId = ""
                    secret = ""
                    baseUrl = CredentialStore.DEFAULT_BASE_URL
                }) { Text("Esquecer dados salvos", color = Danger) }
            }
        }
        if (showAdvanced) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("URL da API") },
                supportingText = { Text("Mude só se a sua conta usa domínio próprio") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ErrorText(error)
        PrimaryButton("Entrar", onClick = submit, loading = loading)

        SectionCard {
            Text("Como criar a chave", fontWeight = FontWeight.SemiBold)
            Text(
                "No painel da StrattonPay, vá em Configurações → Chaves de API e crie uma chave. " +
                    "Para usar tudo no app, marque: TRANSACTIONS (escrita), BALANCE, COMPANY e WITHDRAWS (escrita). " +
                    "O secret aparece uma única vez — copie na hora.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    LaunchedEffect(Unit) { onUnlock() }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark()
        Spacer(Modifier.height(48.dp))
        Button(onClick = onUnlock, modifier = Modifier.height(52.dp)) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Desbloquear")
        }
    }
}
