package br.com.strattonpay.app.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import br.com.strattonpay.app.data.CredentialStore
import org.json.JSONObject

/**
 * O painel da StrattonPay rodando dentro do app (igual ao app da Connect Pay):
 * login com e-mail, senha e 2FA do próprio site, sessão salva, login preenchido sozinho,
 * downloads de comprovante e envio de arquivos.
 */
class PanelController(
    private val activity: Activity,
    private val store: CredentialStore,
    private val launchFileChooser: (Intent) -> Unit,
) {
    companion object {
        const val HOME_URL = "https://app.strattonpay.com.br/"
        private const val DOMAIN = "strattonpay.com.br"

        fun isAllowedHost(host: String?): Boolean {
            val h = host?.lowercase() ?: return false
            return h == DOMAIN || h.endsWith(".$DOMAIN")
        }
    }

    var progress by mutableIntStateOf(0)
    var firstLoadDone by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var canGoBack by mutableStateOf(false)

    /** true quando a página aberta é o painel já logado (sem campo de senha na tela). */
    var loggedInView by mutableStateOf(false)

    /** true quando a tela de login do painel está aberta. */
    var loginFormVisible by mutableStateOf(false)

    /** Pedido (só em teste) para abrir a configuração inicial. */
    var showSetupRequest by mutableStateOf(false)

    /**
     * Com digital disponível, a senha salva só vai para a página depois da digital
     * (não fica preenchida sozinha para qualquer um que pegue o celular).
     */
    var biometricLogin: Boolean = false

    fun hasSavedLogin(): Boolean =
        store.savePanelLogin && !store.panelUser.isNullOrBlank() && !store.panelPass.isNullOrBlank()

    /** Até quando o app continua o login automático na etapa da senha (login em 2 etapas). */
    private var pendingPasswordStepUntil = 0L

    /** E-mail digitado na 1ª etapa do login, para salvar junto com a senha da 2ª etapa. */
    @Volatile
    private var typedEmail: String? = null

    /**
     * Preenche e envia o login salvo. O login da Stratton tem 2 etapas
     * (e-mail → Continuar → senha): se estiver na etapa do e-mail, o app
     * continua sozinho quando a tela da senha aparecer.
     */
    fun submitSavedLogin() {
        val user = store.panelUser ?: return
        val pass = store.panelPass ?: return
        if (!isAllowedHost(Uri.parse(currentUrl).host)) return
        webView.evaluateJavascript(
            "window.__strattonLogin ? window.__strattonLogin(${JSONObject.quote(user)}, ${JSONObject.quote(pass)}, true) : 'sem-ajudante';",
        ) { result ->
            pendingPasswordStepUntil = if (result?.contains("email") == true) {
                android.os.SystemClock.elapsedRealtime() + 30_000
            } else {
                0L
            }
        }
    }

    @Volatile
    private var currentUrl: String = HOME_URL
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(activity).apply {
        setBackgroundColor(android.graphics.Color.parseColor("#0B0E12"))
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString StrattonApp/1.0"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(Bridge(), "StrattonBridge")
        webViewClient = Client()
        webChromeClient = Chrome()
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            when {
                url.startsWith("blob:") -> evaluateJavascript(blobScript(url, name), null)
                url.startsWith("data:") -> Downloads.saveDataUrl(activity, url, name)
                else -> Downloads.enqueue(activity, url, userAgent, contentDisposition, mimeType)
            }
        }
    }

    fun start(savedState: android.os.Bundle?) {
        if (savedState == null || webView.restoreState(savedState) == null) webView.loadUrl(HOME_URL)
    }

    fun reload() {
        error = null
        if (webView.url.isNullOrBlank()) webView.loadUrl(HOME_URL) else webView.reload()
    }

    fun goBack() = webView.goBack()

    fun persist() {
        CookieManager.getInstance().flush()
    }

    /** Sai do painel neste celular: apaga cookies e dados do site. */
    fun clearSiteData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearCache(true)
        webView.clearHistory()
        webView.loadUrl(HOME_URL)
    }

    fun onFileChosen(resultCode: Int, data: Intent?) {
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        fileCallback = null
    }

    // ---------------------------------------------------------------

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && isAllowedHost(uri.host)) return false
            openExternal(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            currentUrl = url
            error = null
            loggedInView = false
            loginFormVisible = false
        }

        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url
            firstLoadDone = true
            canGoBack = view.canGoBack()
            injectLoginHelper()
            CookieManager.getInstance().flush()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            currentUrl = url
            canGoBack = view.canGoBack()
            injectLoginHelper()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
            if (request.isForMainFrame) {
                error = "Não foi possível abrir o painel. Verifique a internet e tente de novo."
                firstLoadDone = true
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                launchFileChooser(fileChooserParams.createIntent())
                true
            } catch (e: Exception) {
                fileCallback = null
                false
            }
        }
    }

    /** Ponte JS → app. Só aceita chamadas quando a página aberta é do domínio da Stratton. */
    private inner class Bridge {
        @JavascriptInterface
        fun onEmail(email: String?) {
            if (!isAllowedHost(Uri.parse(currentUrl).host)) return
            if (!email.isNullOrBlank()) typedEmail = email.trim()
        }

        @JavascriptInterface
        fun onLogin(userFromPage: String?, pass: String?) {
            if (!isAllowedHost(Uri.parse(currentUrl).host)) return
            val user = userFromPage?.takeIf { it.isNotBlank() } ?: typedEmail ?: store.panelUser
            if (user.isNullOrBlank() || pass.isNullOrBlank()) return
            if (!store.savePanelLogin) return
            val changed = store.panelUser != user.trim() || store.panelPass != pass
            if (changed) {
                store.setPanelLogin(user.trim(), pass)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Usuário e senha salvos neste celular", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun onPageState(hasPasswordField: Boolean, hasEmailField: Boolean, path: String?) {
            if (!isAllowedHost(Uri.parse(currentUrl).host)) return
            val p = path?.lowercase().orEmpty()
            val looksLikeAuth = listOf("login", "signin", "sign-in", "entrar", "auth")
                .any { p.contains(it) }
            val isSignUpOrRecovery = listOf("sign-up", "signup", "register", "cadastro", "recuperar", "forgot", "reset")
                .any { p.contains(it) }
            val loginForm = !isSignUpOrRecovery && (hasPasswordField || (hasEmailField && looksLikeAuth))
            val logged = !hasPasswordField && !looksLikeAuth && !isSignUpOrRecovery
            activity.runOnUiThread {
                loggedInView = logged
                loginFormVisible = loginForm
                // 2ª etapa do login automático: a tela da senha apareceu.
                if (hasPasswordField && android.os.SystemClock.elapsedRealtime() < pendingPasswordStepUntil) {
                    pendingPasswordStepUntil = 0L
                    submitSavedLogin()
                }
            }
        }

        @JavascriptInterface
        fun saveBase64(dataUrl: String?, fileName: String?) {
            if (!isAllowedHost(Uri.parse(currentUrl).host)) return
            activity.runOnUiThread { Downloads.saveDataUrl(activity, dataUrl, fileName) }
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "Nenhum app para abrir este link", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // link inválido
        }
    }

    private fun injectLoginHelper() {
        if (!isAllowedHost(Uri.parse(currentUrl).host)) return
        val user = store.panelUser
        val pass = store.panelPass
        val saved = if (!biometricLogin && store.savePanelLogin && user != null && pass != null) {
            "{u:${JSONObject.quote(user)},p:${JSONObject.quote(pass)}}"
        } else {
            "null"
        }
        webView.evaluateJavascript(LOGIN_SCRIPT.replace("__SAVED__", saved), null)
    }

    private fun blobScript(url: String, name: String): String = """
        (function(){
          fetch(${JSONObject.quote(url)}).then(function(r){ return r.blob(); }).then(function(b){
            var fr = new FileReader();
            fr.onloadend = function(){ StrattonBridge.saveBase64(fr.result, ${JSONObject.quote(name)}); };
            fr.readAsDataURL(b);
          }).catch(function(){ StrattonBridge.saveBase64('', ${JSONObject.quote(name)}); });
        })();
    """.trimIndent()
}

/**
 * Preenche e-mail/senha salvos na tela de login do painel e avisa o app quando a pessoa entra.
 * Funciona com sites em React/Vue (dispara os eventos de input que o framework escuta).
 */
private val LOGIN_SCRIPT = """
(function(){
  var saved = __SAVED__;
  window.__strattonSaved = saved;
  if (window.__strattonLoginHelper) { if (window.__strattonCheck) window.__strattonCheck(); return; }
  if (!document.documentElement || !document.body) return; // página ainda montando: o app injeta de novo depois

  function visible(el){ return !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length)); }
  function first(sel){ var l = document.querySelectorAll(sel); for (var i = 0; i < l.length; i++) if (visible(l[i])) return l[i]; return null; }
  function setVal(el, v){
    try {
      var desc = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
      if (desc && desc.set) desc.set.call(el, v); else el.value = v;
    } catch(e) { el.value = v; }
    el.dispatchEvent(new Event('input', {bubbles:true}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
  }
  function fields(){
    var p = first('input[type=password]');
    var u = first('input[type=email], input[autocomplete=username], input[autocomplete=email], input[name*=mail i], input[id*=mail i]');
    if (!p && !u) return null;
    return {u:u, p:p};
  }
  // Botão principal do login ("Continuar"/"Entrar"), nunca "Crie uma conta", Google ou Apple.
  function mainButton(){
    var bad = /google|apple|crie|criar|cadastr|esqueci|forgot|conta/i;
    var good = /^\s*(continuar|entrar|acessar|login|sign in|avan)/i;
    var l = document.querySelectorAll('button, input[type=submit]');
    for (var i = 0; i < l.length; i++) {
      var t = (l[i].textContent || l[i].value || '');
      if (visible(l[i]) && good.test(t) && !bad.test(t)) return l[i];
    }
    return null;
  }

  // Sem digital no celular: só preenche (não envia).
  function fill(){
    var s = window.__strattonSaved, f = fields();
    if (!f || !s) return;
    if (f.u && !f.u.value && !f.u.getAttribute('data-stratton')) { setVal(f.u, s.u); f.u.setAttribute('data-stratton', '1'); }
    if (f.p && !f.p.value && !f.p.getAttribute('data-stratton')) { setVal(f.p, s.p); f.p.setAttribute('data-stratton', '1'); }
  }

  var lastState = null;
  function report(){
    var f = fields();
    var hasP = !!(f && f.p), hasE = !!(f && f.u);
    var st = hasP + '|' + hasE + '|' + location.pathname;
    if (st !== lastState) {
      lastState = st;
      try { StrattonBridge.onPageState(hasP, hasE, location.pathname); } catch(e){}
    }
  }

  // Guarda o que a pessoa digitou ao tocar em Continuar/Entrar.
  function capture(){
    var f = fields();
    if (!f) return;
    try {
      if (f.u && f.u.value) StrattonBridge.onEmail(f.u.value);
      if (f.p && f.p.value) StrattonBridge.onLogin(f.u ? f.u.value : '', f.p.value);
    } catch(e){}
  }
  document.addEventListener('submit', capture, true);
  document.addEventListener('click', function(e){
    var b = e.target && e.target.closest ? e.target.closest('button, input[type=submit]') : null;
    if (b && b === mainButton()) capture();
  }, true);
  document.addEventListener('keydown', function(e){
    if (e.key === 'Enter' && e.target && e.target.tagName === 'INPUT') capture();
  }, true);

  // Chamado pelo app depois da digital: preenche a etapa atual e toca em Continuar/Entrar.
  window.__strattonLogin = function(u, pw, submit){
    var f = fields();
    if (!f) return 'sem-formulario';
    if (f.u && u && f.u.value !== u) setVal(f.u, u);
    if (f.p) setVal(f.p, pw);
    if (submit) {
      setTimeout(function(){
        var b = mainButton();
        if (b) { b.click(); return; }
        var form = (f.p || f.u).form;
        if (form && form.requestSubmit) form.requestSubmit();
      }, 350);
    }
    return f.p ? 'senha' : 'email';
  };

  var pending = false;
  window.__strattonCheck = function(){
    if (pending) return;
    pending = true;
    setTimeout(function(){ pending = false; fill(); report(); }, 150);
  };
  try {
    new MutationObserver(window.__strattonCheck).observe(document.documentElement, {childList:true, subtree:true});
  } catch(e) {}
  window.__strattonLoginHelper = true;
  fill();
  report();
})();
""".trimIndent()
