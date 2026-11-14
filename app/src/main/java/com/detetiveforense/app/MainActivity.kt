package com.detetiveforense.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class MainActivity : Activity() {

    companion object {
        const val HOME = "https://detetiveforense.com/"
        const val HOST = "detetiveforense.com"
        const val FOLDER = "Detetive Forense"
        const val REQ_FILE = 101
        const val REQ_STORAGE = 102
        val DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert
        val EXTERNAL_HOSTS = listOf("wa.me", "api.whatsapp.com", "whatsapp.com", "t.me", "instagram.com", "play.google.com")
    }

    private class PendingFile(
        val name: String,
        val mime: String,
        val out: OutputStream,
        val uri: Uri?,
        val file: File?
    )

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var savedBar: LinearLayout
    private lateinit var savedText: TextView
    private lateinit var savedOpen: TextView
    private lateinit var vault: CredentialVault
    private lateinit var injectJs: String

    @Volatile
    private var currentUrl: String = HOME
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val openFiles = ConcurrentHashMap<String, PendingFile>()
    private var savePromptShowing = false
    private var lastBack = 0L
    private var printWebView: WebView? = null
    private var splash: SplashView? = null
    private val ui = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = CredentialVault(this)
        injectJs = assets.open("inject.js").bufferedReader().use { it.readText() }
        buildUi()
        setupWebView()

        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            splash = SplashView(this).also {
                root.addView(it, FrameLayout.LayoutParams(-1, -1))
                it.start()
            }
            ui.postDelayed({ hideSplash() }, 9000) // segurança: nunca trava na abertura
            webView.loadUrl(HOME)
        }
    }

    private fun hideSplash() {
        splash?.dismiss()
        splash = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B0F17"))

        webView = WebView(this)
        root.addView(webView, FrameLayout.LayoutParams(-1, -1))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP))

        // Tela de erro (sem internet)
        errorView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0F17"))
            visibility = View.GONE
            isClickable = true
            addView(TextView(this@MainActivity).apply {
                text = "Sem conexão com o site"
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
            })
            addView(Button(this@MainActivity).apply {
                text = "Tentar novamente"
                setOnClickListener {
                    errorView.visibility = View.GONE
                    webView.reload()
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        }
        root.addView(errorView, FrameLayout.LayoutParams(-1, -1))

        // Barra "arquivo salvo"
        savedText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
        }
        savedOpen = TextView(this).apply {
            text = "ABRIR"
            setTextColor(Color.parseColor("#60A5FA"))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            setPadding(dp(16), dp(8), dp(4), dp(8))
        }
        savedBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#EE1F2937"))
            }
            visibility = View.GONE
            addView(savedText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(savedOpen)
        }
        root.addView(savedBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(12), 0, dp(12), dp(16))
        })

        // Botão de menu flutuante (arrastável)
        val menuBtn = TextView(this).apply {
            text = "⋮"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.55f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1F2937"))
            }
            elevation = dp(6).toFloat()
        }
        root.addView(menuBtn, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(14), dp(90))
        })
        menuBtn.setOnClickListener { showMenu() }
        menuBtn.setOnTouchListener(object : View.OnTouchListener {
            var dX = 0f
            var dY = 0f
            var downX = 0f
            var downY = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = v.x - e.rawX; dY = v.y - e.rawY
                        downX = e.rawX; downY = e.rawY
                        moved = false; v.alpha = 0.95f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.rawX - downX) > dp(8) || abs(e.rawY - downY) > dp(8)) moved = true
                        if (moved) {
                            v.x = (e.rawX + dX).coerceIn(0f, (root.width - v.width).toFloat())
                            v.y = (e.rawY + dY).coerceIn(0f, (root.height - v.height).toFloat())
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 0.55f
                        if (!moved && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    }
                }
                return true
            }
        })

        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            userAgentString = userAgentString.replace("; wv", "") + " DetetiveForenseApp/1.0"
        }

        webView.addJavascriptInterface(Bridge(), "DFApp")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView, injectJs, setOf("https://$HOST", "https://*.$HOST")
            )
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url.toString())

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                url?.let { currentUrl = it }
                errorView.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                url?.let { currentUrl = it }
                view.evaluateJavascript(injectJs, null) // fallback p/ WebViews antigos
                CookieManager.getInstance().flush()
                if (view.progress >= 100) ui.postDelayed({ hideSplash() }, 400)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                url?.let { currentUrl = it }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    errorView.visibility = View.VISIBLE
                    hideSplash()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                return try {
                    startActivityForResult(Intent.createChooser(intent, "Escolher arquivo"), REQ_FILE)
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message
            ): Boolean {
                val temp = WebView(this@MainActivity)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        openNewWindowUrl(request.url.toString())
                        v.destroy()
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.setDownloadListener { url, ua, cd, mime, _ -> handleDownload(url, ua, cd, mime) }

        webView.setOnLongClickListener {
            val r = webView.hitTestResult
            if (r.type == WebView.HitTestResult.IMAGE_TYPE ||
                r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            ) {
                imageMenu(r.extra)
                true
            } else false
        }
    }

    // ------------------------------------------------------------------ navegação

    private fun isOwnHost(url: String?): Boolean {
        val h = try { Uri.parse(url ?: "").host } catch (e: Exception) { null } ?: return false
        return h == HOST || h.endsWith(".$HOST")
    }

    private fun handleUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return false
        return when {
            scheme == "blob" -> { jsDownload(url, ""); true }
            scheme == "data" -> { saveDataUrl(url, ""); true }
            scheme == "http" || scheme == "https" -> {
                val host = uri.host ?: ""
                if (EXTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }) { openExternal(url); true } else false
            }
            scheme == "intent" -> {
                try {
                    val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    try { startActivity(i) } catch (e: ActivityNotFoundException) {
                        i.getStringExtra("browser_fallback_url")?.let { webView.loadUrl(it) }
                    }
                } catch (ignored: Exception) { }
                true
            }
            else -> { openExternal(url); true }
        }
    }

    private fun openNewWindowUrl(url: String) {
        when {
            url.startsWith("blob:") -> jsDownload(url, "")
            url.startsWith("data:") -> saveDataUrl(url, "")
            isOwnHost(url) -> webView.loadUrl(url)
            else -> if (!handleUrl(url)) openExternal(url)
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("Nenhum app para abrir este link")
        }
    }

    // ------------------------------------------------------------------ downloads

    private fun jsDownload(url: String, name: String) {
        webView.evaluateJavascript(
            "window.__df&&__df.downloadUrl(${JSONObject.quote(url)},${JSONObject.quote(name)})", null
        )
    }

    private fun handleDownload(url: String, ua: String?, cd: String?, mime: String?) {
        when {
            url.startsWith("blob:") -> {
                val name = if (cd.isNullOrBlank()) "" else URLUtil.guessFileName(url, cd, mime)
                jsDownload(url, name)
            }
            url.startsWith("data:") -> saveDataUrl(url, "")
            isOwnHost(url) -> jsDownload(url, sanitize(URLUtil.guessFileName(url, cd, mime)))
            else -> enqueueDownload(url, ua, cd, mime)
        }
    }

    private fun enqueueDownload(url: String, ua: String?, cd: String?, mimeIn: String?) {
        try {
            val name = sanitize(URLUtil.guessFileName(url, cd, mimeIn))
            var mime = mimeIn
            if (mime.isNullOrBlank() || mime == "application/octet-stream") {
                val ext = name.substringAfterLast('.', "")
                mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: mime
            }
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", ua ?: webView.settings.userAgentString)
                addRequestHeader("Referer", currentUrl)
                if (!mime.isNullOrBlank()) setMimeType(mime)
                setTitle(name)
                setDescription(FOLDER)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$FOLDER/$name")
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Baixando: $name")
        } catch (e: Exception) {
            jsDownload(url, "")
        }
    }

    private fun sanitize(name: String): String {
        var n = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim()
        if (n.isBlank()) n = "arquivo_${System.currentTimeMillis()}"
        if (n.length > 120) {
            val ext = n.substringAfterLast('.', "")
            n = n.take(110) + if (ext.isNotEmpty() && ext.length < 8) ".$ext" else ""
        }
        return n
    }

    private fun withExt(name: String, mime: String): String {
        if (name.contains('.')) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        return "$name.$ext"
    }

    private fun isGalleryImage(mime: String) =
        mime == "image/png" || mime == "image/jpeg" || mime == "image/webp" || mime == "image/gif"

    /** Cria o arquivo de saída: fotos vão para Imagens/Detetive Forense, o resto para Download/Detetive Forense. */
    private fun openOutput(rawName: String, mime: String): PendingFile? {
        val name = sanitize(withExt(rawName, mime))
        val image = isGalleryImage(mime)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/" + FOLDER
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(collection, cv) ?: return null
                val out = contentResolver.openOutputStream(uri) ?: return null
                PendingFile(name, mime, out, uri, null)
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread {
                        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
                    }
                    toast("Permita o acesso aos arquivos e tente de novo")
                    return null
                }
                val base = Environment.getExternalStoragePublicDirectory(
                    if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
                )
                val dir = File(base, FOLDER).apply { mkdirs() }
                val stem = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "")
                var f = File(dir, name)
                var i = 1
                while (f.exists()) {
                    f = File(dir, if (ext.isEmpty()) "$stem ($i)" else "$stem ($i).$ext"); i++
                }
                PendingFile(f.name, mime, FileOutputStream(f), null, f)
            }
        } catch (e: Exception) {
            toast("Não foi possível criar o arquivo: ${e.message}")
            null
        }
    }

    private fun finishFile(pf: PendingFile) {
        try { pf.out.flush() } catch (ignored: Exception) { }
        try { pf.out.close() } catch (ignored: Exception) { }
        var openUri: Uri? = null
        if (pf.uri != null) {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                try { contentResolver.update(pf.uri, cv, null, null) } catch (ignored: Exception) { }
            }
            openUri = pf.uri
        } else if (pf.file != null) {
            MediaScannerConnection.scanFile(this, arrayOf(pf.file.absolutePath), arrayOf(pf.mime), null)
            openUri = try {
                FileProvider.getUriForFile(this, "$packageName.files", pf.file)
            } catch (ignored: Exception) { null }
        }
        val where = if (isGalleryImage(pf.mime)) "Galeria" else "Downloads"
        runOnUiThread { showSaved("${pf.name} ($where)", openUri, pf.mime) }
    }

    private fun discardFile(pf: PendingFile) {
        try { pf.out.close() } catch (ignored: Exception) { }
        try { pf.uri?.let { contentResolver.delete(it, null, null) } } catch (ignored: Exception) { }
        try { pf.file?.delete() } catch (ignored: Exception) { }
    }

    private fun saveDataUrl(dataUrl: String, nameHint: String) {
        Thread {
            try {
                val comma = dataUrl.indexOf(',')
                val meta = dataUrl.substring(5, comma)
                val payload = dataUrl.substring(comma + 1)
                val isB64 = meta.endsWith(";base64", ignoreCase = true)
                val mime = meta.split(";")[0].ifBlank { "application/octet-stream" }
                val bytes = if (isB64) Base64.decode(payload, Base64.DEFAULT)
                else URLDecoder.decode(payload, "UTF-8").toByteArray()
                val name = nameHint.ifBlank { "detetive_${System.currentTimeMillis()}" }
                val pf = openOutput(name, mime) ?: return@Thread
                pf.out.write(bytes)
                finishFile(pf)
            } catch (e: Exception) {
                toast("Falha ao salvar: ${e.message}")
            }
        }.start()
    }

    private fun showSaved(text: String, uri: Uri?, mime: String) {
        savedText.text = "✓ Salvo: $text"
        savedOpen.visibility = if (uri != null) View.VISIBLE else View.GONE
        savedOpen.setOnClickListener {
            if (uri != null) {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } catch (e: Exception) {
                    toast("Nenhum app para abrir este arquivo")
                }
            }
            savedBar.visibility = View.GONE
        }
        savedBar.visibility = View.VISIBLE
        ui.removeCallbacks(hideSaved)
        ui.postDelayed(hideSaved, 6000)
    }

    private val hideSaved = Runnable { savedBar.visibility = View.GONE }

    // ------------------------------------------------------------------ imagens (toque longo)

    private fun imageMenu(extra: String?) {
        val h = Handler(Looper.getMainLooper()) { m ->
            val src = m.data?.getString("url")?.takeIf { it.isNotBlank() } ?: extra
            if (src != null) showImageDialog(src)
            true
        }
        webView.requestImageRef(h.obtainMessage())
    }

    private fun showImageDialog(src: String) {
        AlertDialog.Builder(this, DIALOG)
            .setItems(arrayOf("Salvar imagem", "Copiar link da imagem")) { _, which ->
                when (which) {
                    0 -> saveImage(src)
                    1 -> copyText(src)
                }
            }
            .show()
    }

    private fun saveImage(src: String) {
        when {
            src.startsWith("data:") -> saveDataUrl(src, "")
            src.startsWith("blob:") -> jsDownload(src, "")
            isOwnHost(src) -> jsDownload(src, sanitize(URLUtil.guessFileName(src, null, null)))
            else -> enqueueDownload(src, null, null, null)
        }
    }

    // ------------------------------------------------------------------ PDF / impressão

    private fun printWebViewNow(wv: WebView, title: String) {
        val pm = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val name = sanitize(title.ifBlank { "Detetive Forense" })
        pm.print(
            name, wv.createPrintDocumentAdapter(name),
            PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build()
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun printHtml(html: String, title: String) {
        printWebView?.destroy()
        val pv = WebView(this)
        printWebView = pv
        pv.settings.javaScriptEnabled = true
        pv.settings.domStorageEnabled = true
        var done = false
        pv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (done) return
                done = true
                view.postDelayed({ printWebViewNow(view, title) }, 600)
            }
        }
        pv.loadDataWithBaseURL(currentUrl, html, "text/html", "UTF-8", null)
    }

    // ------------------------------------------------------------------ menu

    private fun copyText(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("texto", text))
        if (Build.VERSION.SDK_INT < 33) toast("Copiado")
    }

    private fun showMenu() {
        val hasPass = vault.pass() != null
        val items = arrayOf(
            "Recarregar página",
            "Voltar ao início",
            "Salvar página em PDF",
            "Copiar link da página",
            if (hasPass) "Esquecer senha salva" else "Senha salva: nenhuma",
            "Sair da conta (limpar dados)"
        )
        AlertDialog.Builder(this, DIALOG)
            .setTitle("Detetive Forense")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> webView.reload()
                    1 -> webView.loadUrl(HOME)
                    2 -> printWebViewNow(webView, webView.title ?: "Detetive Forense")
                    3 -> copyText(webView.url ?: HOME)
                    4 -> if (hasPass) { vault.clear(); toast("Senha apagada do aparelho") }
                    5 -> confirmLogout()
                }
            }
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this, DIALOG)
            .setTitle("Sair da conta?")
            .setMessage("Apaga cookies, cache e dados do site neste aparelho. A senha salva continua (apague no menu se quiser).")
            .setPositiveButton("Sair") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                webView.clearCache(true)
                webView.clearHistory()
                webView.loadUrl(HOME)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------------ ponte JS -> app

    inner class Bridge {
        @JavascriptInterface
        fun fileStart(id: String, name: String, mime: String): Boolean {
            val pf = openOutput(name, mime.ifBlank { "application/octet-stream" }) ?: return false
            openFiles[id] = pf
            return true
        }

        @JavascriptInterface
        fun fileChunk(id: String, b64: String) {
            val pf = openFiles[id] ?: return
            try {
                pf.out.write(Base64.decode(b64, Base64.DEFAULT))
            } catch (e: Exception) {
                openFiles.remove(id)
                discardFile(pf)
                toast("Falha ao salvar: ${e.message}")
            }
        }

        @JavascriptInterface
        fun fileEnd(id: String) {
            val pf = openFiles.remove(id) ?: return
            finishFile(pf)
        }

        @JavascriptInterface
        fun fileError(id: String, msg: String) {
            openFiles.remove(id)?.let { discardFile(it) }
            toast("Não foi possível baixar o arquivo")
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread { copyText(text) }
        }

        @JavascriptInterface
        fun print() {
            runOnUiThread { printWebViewNow(webView, webView.title ?: "Detetive Forense") }
        }

        @JavascriptInterface
        fun printHtml(html: String, title: String) {
            runOnUiThread { this@MainActivity.printHtml(html, title) }
        }

        @JavascriptInterface
        fun getCredentials(): String {
            if (!isOwnHost(currentUrl)) return "null"
            val p = vault.pass() ?: return "null"
            return JSONObject().put("u", vault.user() ?: "").put("p", p).toString()
        }

        @JavascriptInterface
        fun offerSave(user: String, pass: String) {
            if (!isOwnHost(currentUrl) || pass.isBlank() || vault.never) return
            val su = vault.user()
            val sp = vault.pass()
            if (su == user && sp == pass) return
            val update = sp != null && su == user
            runOnUiThread {
                if (savePromptShowing || isFinishing) return@runOnUiThread
                savePromptShowing = true
                AlertDialog.Builder(this@MainActivity, DIALOG)
                    .setTitle(if (update) "Atualizar senha salva?" else "Salvar senha?")
                    .setMessage(
                        (if (user.isNotBlank()) "Usuário: $user\n\n" else "") +
                            "O login será preenchido automaticamente nas próximas vezes. " +
                            "A senha fica criptografada só neste aparelho."
                    )
                    .setPositiveButton(if (update) "Atualizar" else "Salvar") { _, _ ->
                        vault.save(user, pass)
                        toast("Senha salva")
                    }
                    .setNegativeButton("Agora não", null)
                    .setNeutralButton("Nunca") { _, _ -> vault.never = true }
                    .setOnDismissListener { savePromptShowing = false }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val uris: Array<Uri>? = if (resultCode == RESULT_OK && data != null) {
                val cd = data.clipData
                if (cd != null) Array(cd.itemCount) { cd.getItemAt(it).uri }
                else data.data?.let { arrayOf(it) }
            } else null
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        webView.evaluateJavascript("(window.__df&&__df.closeOverlay())?'1':'0'") { res ->
            when {
                res != null && res.contains("1") -> Unit
                webView.canGoBack() -> webView.goBack()
                System.currentTimeMillis() - lastBack < 2000 -> finish()
                else -> {
                    lastBack = System.currentTimeMillis()
                    toast("Toque de novo para sair")
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        printWebView?.destroy()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
