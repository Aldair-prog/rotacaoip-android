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

    @Volatile
    private var currentUrl: String = HOME_URL
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    val webView: WebView = WebView(activity).apply {
        setBackgroundColor(android.graphics.Color.parseColor("#0D0718"))
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
        fun onLogin(user: String?, pass: String?) {
            if (!isAllowedHost(Uri.parse(currentUrl).host)) return
            if (user.isNullOrBlank() || pass.isNullOrBlank()) return
            if (!store.savePanelLogin) return
            val changed = store.panelUser != user || store.panelPass != pass
            if (changed) {
                store.setPanelLogin(user.trim(), pass)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Usuário e senha salvos neste celular", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun onPageState(hasPasswordField: Boolean, path: String?) {
            if (!isAllowedHost(Uri.parse(currentUrl).host)) return
            val p = path?.lowercase().orEmpty()
            val looksLikeAuth = listOf("login", "signin", "sign-in", "entrar", "auth", "register", "cadastro", "recuperar", "forgot")
                .any { p.contains(it) }
            val logged = !hasPasswordField && !looksLikeAuth
            activity.runOnUiThread { loggedInView = logged }
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
        val saved = if (store.savePanelLogin && user != null && pass != null) {
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
  if (window.__strattonLoginHelper) { if (window.__strattonFill) window.__strattonFill(); return; }
  window.__strattonLoginHelper = true;

  function setVal(el, v){
    try {
      var proto = Object.getPrototypeOf(el);
      var desc = Object.getOwnPropertyDescriptor(proto, 'value');
      if (desc && desc.set) desc.set.call(el, v); else el.value = v;
    } catch(e) { el.value = v; }
    el.dispatchEvent(new Event('input', {bubbles:true}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
  }
  function fields(){
    var p = document.querySelector('input[type=password]');
    if (!p) return null;
    var root = p.form || document;
    var u = root.querySelector('input[type=email]')
      || root.querySelector('input[autocomplete=username]')
      || root.querySelector('input[name*=mail i], input[id*=mail i], input[name*=user i], input[id*=user i], input[name*=login i]')
      || root.querySelector('input[type=text]');
    return {u:u, p:p};
  }
  function fill(){
    var s = window.__strattonSaved;
    var f = fields();
    if (!f || !s || f.p.getAttribute('data-stratton-filled')) return;
    if (f.u && !f.u.value) setVal(f.u, s.u);
    if (!f.p.value) setVal(f.p, s.p);
    f.p.setAttribute('data-stratton-filled', '1');
  }
  function capture(){
    var f = fields();
    if (!f) return;
    var u = f.u ? f.u.value : '';
    var p = f.p.value;
    if (u && p) { try { StrattonBridge.onLogin(u, p); } catch(e){} }
  }
  var lastState = null;
  function report(){
    var has = !!document.querySelector('input[type=password]');
    var st = has + '|' + location.pathname;
    if (st !== lastState) { lastState = st; try { StrattonBridge.onPageState(has, location.pathname); } catch(e){} }
  }
  window.__strattonFill = function(){ fill(); report(); };
  document.addEventListener('submit', capture, true);
  document.addEventListener('click', function(e){
    var b = e.target && e.target.closest ? e.target.closest('button, input[type=submit]') : null;
    if (b && fields()) capture();
  }, true);
  document.addEventListener('keydown', function(e){ if (e.key === 'Enter' && fields()) capture(); }, true);
  new MutationObserver(function(){ fill(); report(); }).observe(document.documentElement, {childList:true, subtree:true});
  fill();
  report();
})();
""".trimIndent()
