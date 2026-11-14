@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.strattonpay.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.strattonpay.app.R
import br.com.strattonpay.app.data.ApiException
import kotlinx.coroutines.CancellationException

// ---------- Estado de carregamento ----------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val data: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

suspend fun <T> load(block: suspend () -> T): Load<T> = try {
    Load.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: ApiException) {
    Load.Err(e.message)
} catch (e: Exception) {
    Load.Err(e.message ?: "Erro inesperado")
}

// ---------- Estrutura de tela ----------

@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        content = content,
    )
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun BrandMark(modifier: Modifier = Modifier, width: Dp = 260.dp) {
    Image(
        painter = painterResource(R.drawable.logo_full),
        contentDescription = br.com.strattonpay.app.BuildConfig.BRAND_NAME,
        modifier = modifier.width(width),
    )
}

@Composable
fun StatusPill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Brand)
    }
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, color = Danger, textAlign = TextAlign.Center)
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Tentar de novo") }
    }
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) {
        Text(
            message,
            color = Danger,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(Danger.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
                .padding(12.dp),
        )
    }
}

@Composable
fun EmptyBox(message: String) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Muted, textAlign = TextAlign.Center)
    }
}

@Composable
fun InfoRow(label: String, value: String?, onCopy: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
        Text(
            value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (onCopy != null && !value.isNullOrBlank()) {
            TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Copiar") }
        }
    }
}

/** Campo de valor que preenche da direita: digitar 1, 0, 0, 0 vira R$ 10,00. */
@Composable
fun MoneyField(cents: Long, onChange: (Long) -> Unit, label: String, modifier: Modifier = Modifier, supporting: String? = null) {
    val text = if (cents == 0L) "" else money(cents)
    OutlinedTextField(
        value = TextFieldValue(text, selection = TextRange(text.length)),
        onValueChange = { v ->
            val digits = v.text.filter { it.isDigit() }.take(11)
            onChange(digits.toLongOrNull() ?: 0L)
        },
        label = { Text(label) },
        placeholder = { Text("R$ 0,00") },
        supportingText = { if (supporting != null) Text(supporting) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Copiar para a área de transferência com aviso. */
@Composable
fun rememberCopy(): (text: String, toast: String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(clipboard, context) {
        { text, toast ->
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun rememberShare(): (text: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { text ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "Compartilhar"))
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        containerColor = Surface1,
    )
}
