package expo.modules.apppush

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Push do Android sem Firebase e sem Client ID/Secret.
 * O app entrega ao painel uma assinatura Web Push (igual a um navegador): endereço num tópico
 * secreto do ntfy + chaves. O painel manda o aviso criptografado e o app abre aqui no celular.
 */
class AppPushModule : Module() {
    private val context: Context
        get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

    override fun definition() = ModuleDefinition {
        Name("AppPush")

        /** Marca, cor, som e esquema usados nas notificações. */
        Function("configure") { brandName: String, color: String, sound: String, scheme: String ->
            val cfg = PushConfig(context)
            cfg.brandName = brandName
            cfg.colorHex = color
            cfg.sound = sound
            cfg.scheme = scheme
        }

        /** Assinatura no formato que o painel recebe em /notifications/@me/push-subscriptions */
        Function("getSubscription") {
            val store = PushStore(context)
            store.ensureKeys()
            mapOf(
                "endpoint" to store.endpoint,
                "publicKey" to PushStore.b64url(PushStore.rawPublicKey(store.publicKey())),
                "authenticationSecret" to PushStore.b64url(store.authSecret()),
            )
        }

        /** O painel aceitou a assinatura: liga a recepção (ou desliga). */
        Function("setSubscribed") { on: Boolean ->
            val store = PushStore(context)
            val cfg = PushConfig(context)
            store.subscribed = on
            cfg.enabled = on
            if (on) PushService.start(context) else PushService.stop(context)
        }

        Function("isSubscribed") {
            PushStore(context).subscribed && PushConfig(context).enabled
        }

        /** Religa a conexão se já estava ativa (chamado ao abrir o app). */
        Function("ensure") {
            PushService.ensure(context)
        }

        /** Novo endereço e novas chaves (ao sair da conta). */
        Function("reset") {
            PushService.stop(context)
            PushStore(context).reset()
        }

        /** Mostra um aviso no visual do app (usado pelo app para avisos locais). */
        Function("notify") { title: String, body: String ->
            Notifier.show(context, title, body, null)
        }

        /** Manda um teste pelo mesmo caminho do painel (ntfy → celular). */
        AsyncFunction("sendTest") {
            try {
                PushInbox.sendTest(PushStore(context))
                true
            } catch (e: Exception) {
                false
            }
        }

        Function("isIgnoringBatteryOptimizations") {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        /** Pede para o Android não pausar o app (as notificações chegam na hora). */
        Function("requestIgnoreBatteryOptimizations") {
            openBatterySettings(context)
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings(ctx: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(direct)
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }
}
