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
import androidx.compose.ui.graphics.toArgb
import br.com.strattonpay.app.BuildConfig
import br.com.strattonpay.app.data.CredentialStore
import br.com.strattonpay.app.notify.Notifier
import br.com.strattonpay.app.push.PushService
import br.com.strattonpay.app.push.PushStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
    /** Pede a permissão de notificação do Android e devolve se foi concedida. */
    private val requestNotificationPermission: ((Boolean) -> Unit) -> Unit,
) {
    private val pushStore = PushStore(activity.applicationContext)
    companion object {
        val HOME_URL = BuildConfig.PANEL_URL
        private val DOMAIN = BuildConfig.PANEL_DOMAIN

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
        setBackgroundColor(br.com.strattonpay.app.ui.Bg.toArgb())
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
            userAgentString = "$userAgentString ${BuildConfig.BRAND_NAME.replace(" ", "")}App/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(Bridge(), "StrattonBridge")
        addJavascriptInterface(PushBridge(), "AppPush")
        // Injeta o "push de navegador" antes do painel carregar, quando o Android suporta.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val origin = Uri.parse(HOME_URL).let { "${it.scheme}://${it.host}" }
            WebViewCompat.addDocumentStartJavaScript(this, NATIVE_PUSH_SCRIPT, setOf(origin))
        }
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
            injectPush()
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

    private fun injectPush() {
        if (isAllowedHost(Uri.parse(currentUrl).host)) webView.evaluateJavascript(NATIVE_PUSH_SCRIPT, null)
    }

    private fun permissionState(): String =
        if (Notifier.canNotify(activity)) "granted" else "default"

    /**
     * Ponte do push: o painel pede uma assinatura como pediria a um navegador,
     * e o app entrega o endereço de entrega + chaves. O painel registra isso na conta.
     */
    private inner class PushBridge {
        private fun allowed() = isAllowedHost(Uri.parse(currentUrl).host)

        @JavascriptInterface
        fun permission(): String = if (allowed()) permissionState() else "denied"

        @JavascriptInterface
        fun requestPermission() {
            if (!allowed()) return
            activity.runOnUiThread {
                requestNotificationPermission { granted ->
                    Notifier.ensureChannels(activity)
                    val state = if (granted && Notifier.canNotify(activity)) "granted" else "denied"
                    webView.evaluateJavascript("window.__appPushPermissionResult && window.__appPushPermissionResult('$state');", null)
                }
            }
        }

        @JavascriptInterface
        fun subscribe(vapid: String?): String {
            if (!allowed()) return ""
            if (!vapid.isNullOrBlank()) pushStore.vapid = vapid
            pushStore.ensureKeys()
            pushStore.subscribed = true
            PushService.start(activity.applicationContext)
            return pushStore.subscriptionJson()
        }

        @JavascriptInterface
        fun getSubscription(): String =
            if (allowed() && pushStore.subscribed) pushStore.subscriptionJson() else ""

        /** O painel confirmou que salvou a assinatura na conta. */
        @JavascriptInterface
        fun confirm(endpoint: String?) {
            if (!allowed() || endpoint != pushStore.endpoint) return
            pushStore.subscribed = true
            PushService.start(activity.applicationContext)
        }

        @JavascriptInterface
        fun unsubscribe(): Boolean {
            if (!allowed()) return false
            pushStore.subscribed = false
            PushService.stop(activity.applicationContext)
            return true
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


/**
 * "Push de navegador" para o painel dentro do app (o WebView do Android não tem).
 * Mesma ideia do app oficial da Connect: o painel chama pushManager.subscribe() e
 * Notification.requestPermission() normalmente, e quem responde é o app.
 */
private val NATIVE_PUSH_SCRIPT = """
(function(){
  if (window.top !== window) return;
  if (window.__appPushInstalled || !window.AppPush) return;
  window.__appPushInstalled = true;

  function err(name, msg){ try { return new DOMException(msg || name, name); } catch(e){ var x = new Error(msg || name); x.name = name; return x; } }
  function toB64u(buf){
    if (!buf) return '';
    if (typeof buf === 'string') return buf.replace(/\+/g,'-').replace(/\//g,'_').replace(/=+${'$'}/,'');
    var u = buf instanceof ArrayBuffer ? new Uint8Array(buf) : new Uint8Array(buf.buffer, buf.byteOffset || 0, buf.byteLength);
    var s = ''; for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
    return btoa(s).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+${'$'}/,'');
  }
  function fromB64u(v){
    var s = String(v || '').replace(/-/g,'+').replace(/_/g,'/');
    while (s.length % 4) s += '=';
    var b = atob(s), u = new Uint8Array(b.length);
    for (var i = 0; i < b.length; i++) u[i] = b.charCodeAt(i);
    return u.buffer;
  }
  function makeSub(raw){
    if (!raw) return null;
    var d; try { d = JSON.parse(raw); } catch(e){ return null; }
    if (!d || !d.endpoint) return null;
    return {
      endpoint: d.endpoint,
      expirationTime: null,
      options: { userVisibleOnly: true, applicationServerKey: d.vapid ? fromB64u(d.vapid) : null },
      getKey: function(n){ return n === 'p256dh' ? fromB64u(d.p256dh) : (n === 'auth' ? fromB64u(d.auth) : null); },
      toJSON: function(){ return { endpoint: d.endpoint, expirationTime: null, keys: { p256dh: d.p256dh, auth: d.auth } }; },
      unsubscribe: function(){ return Promise.resolve(AppPush.unsubscribe()); }
    };
  }

  var waiting = [];
  window.__appPushPermissionResult = function(state){
    var list = waiting; waiting = [];
    list.forEach(function(cb){ try { cb(state); } catch(e){} });
  };
  function askPermission(){
    return new Promise(function(resolve){
      var p = AppPush.permission();
      if (p === 'granted') { resolve(p); return; }
      waiting.push(resolve);
      AppPush.requestPermission();
    });
  }

  function NativePushManager(){}
  NativePushManager.supportedContentEncodings = ['aes128gcm'];
  NativePushManager.prototype.subscribe = function(options){
    var vapid = toB64u(options && options.applicationServerKey);
    return askPermission().then(function(state){
      if (state !== 'granted') throw err('NotAllowedError', 'Permissão de notificação negada.');
      var sub = makeSub(AppPush.subscribe(vapid));
      if (!sub) throw err('InvalidStateError', 'Não foi possível criar a assinatura.');
      return sub;
    });
  };
  NativePushManager.prototype.getSubscription = function(){ return Promise.resolve(makeSub(AppPush.getSubscription())); };
  NativePushManager.prototype.permissionState = function(){
    var p = AppPush.permission(); return Promise.resolve(p === 'default' ? 'prompt' : p);
  };
  var pushManager = new NativePushManager();
  try { Object.defineProperty(window, 'PushManager', { value: NativePushManager, configurable: true, writable: true }); }
  catch(e){ window.PushManager = NativePushManager; }

  function NativeNotification(){ /* o app mostra as notificações que chegam pelo push */ }
  Object.defineProperty(NativeNotification, 'permission', { configurable: true, enumerable: true, get: function(){ return AppPush.permission(); } });
  NativeNotification.requestPermission = function(cb){
    return askPermission().then(function(state){ if (typeof cb === 'function') cb(state); return state; });
  };
  try { Object.defineProperty(window, 'Notification', { value: NativeNotification, configurable: true, writable: true }); }
  catch(e){ window.Notification = NativeNotification; }

  var virtualReg = {
    scope: location.origin + '/', active: { state: 'activated' }, installing: null, waiting: null,
    pushManager: pushManager,
    showNotification: function(){ return Promise.resolve(); },
    getNotifications: function(){ return Promise.resolve([]); },
    update: function(){ return Promise.resolve(); },
    unregister: function(){ return Promise.resolve(true); }
  };
  function attach(reg){
    if (!reg) return virtualReg;
    try { if (!reg.pushManager || reg.pushManager !== pushManager) Object.defineProperty(reg, 'pushManager', { configurable: true, get: function(){ return pushManager; } }); } catch(e){}
    return reg.pushManager ? reg : virtualReg;
  }
  try {
    if (window.ServiceWorkerRegistration && window.ServiceWorkerRegistration.prototype) {
      Object.defineProperty(window.ServiceWorkerRegistration.prototype, 'pushManager', { configurable: true, get: function(){ return pushManager; } });
    }
  } catch(e){}
  var sw = navigator.serviceWorker;
  if (sw) {
    try { var reg0 = sw.register.bind(sw); sw.register = function(){ return Promise.resolve(reg0.apply(sw, arguments)).then(attach, function(){ return virtualReg; }); }; } catch(e){}
    try { var get0 = sw.getRegistration.bind(sw); sw.getRegistration = function(){ return Promise.resolve(get0.apply(sw, arguments)).then(attach, function(){ return virtualReg; }); }; } catch(e){}
    try { var all0 = sw.getRegistrations.bind(sw); sw.getRegistrations = function(){ return Promise.resolve(all0.apply(sw, arguments)).then(function(l){ return (l && l.length ? l : [virtualReg]).map(attach); }, function(){ return [virtualReg]; }); }; } catch(e){}
    try {
      var proto = window.ServiceWorkerContainer && window.ServiceWorkerContainer.prototype;
      var rd = proto && Object.getOwnPropertyDescriptor(proto, 'ready');
      if (rd && rd.get && rd.configurable) {
        Object.defineProperty(proto, 'ready', { configurable: true, get: function(){
          var nat; try { nat = rd.get.call(this); } catch(e){ nat = Promise.reject(); }
          var fb = new Promise(function(r){ setTimeout(function(){ r(virtualReg); }, 4000); });
          return Promise.race([Promise.resolve(nat).then(attach), fb]);
        }});
      }
    } catch(e){}
  } else {
    var vc = {
      register: function(){ return Promise.resolve(virtualReg); },
      getRegistration: function(){ return Promise.resolve(virtualReg); },
      getRegistrations: function(){ return Promise.resolve([virtualReg]); },
      ready: Promise.resolve(virtualReg), controller: null,
      addEventListener: function(){}, removeEventListener: function(){}
    };
    try { Object.defineProperty(navigator, 'serviceWorker', { value: vc, configurable: true }); }
    catch(e){ try { Object.defineProperty(Object.getPrototypeOf(navigator), 'serviceWorker', { configurable: true, get: function(){ return vc; } }); } catch(_){} }
  }

  // Quando o painel salva a assinatura na conta, avisa o app (liga a recepção).
  function isSubPost(rawUrl, method){
    try {
      var u = new URL(String(rawUrl || ''), location.href);
      return u.origin === location.origin && String(method || 'GET').toUpperCase() === 'POST' &&
        /\/notifications\/@me\/push-subscriptions\/?${'$'}/.test(u.pathname);
    } catch(e){ return false; }
  }
  function endpointOf(body){ try { var d = JSON.parse(typeof body === 'string' ? body : ''); return d && typeof d.endpoint === 'string' ? d.endpoint : ''; } catch(e){ return ''; } }
  var f0 = window.fetch;
  if (typeof f0 === 'function') {
    window.fetch = function(input, init){
      var url = typeof input === 'string' || input instanceof URL ? String(input) : (input && input.url);
      var method = init && init.method ? init.method : (input && input.method);
      var ep = isSubPost(url, method) ? endpointOf(init && init.body) : '';
      return Promise.resolve(f0.apply(this, arguments)).then(function(r){ if (ep && r && r.ok) { try { AppPush.confirm(ep); } catch(e){} } return r; });
    };
  }
  var open0 = XMLHttpRequest.prototype.open, send0 = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url){ this.__appPushPost = isSubPost(url, method); return open0.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function(body){
    var ep = this.__appPushPost ? endpointOf(body) : '';
    if (ep) this.addEventListener('loadend', function(){ if (this.status >= 200 && this.status < 300) { try { AppPush.confirm(ep); } catch(e){} } });
    return send0.apply(this, arguments);
  };
})();
""".trimIndent()
