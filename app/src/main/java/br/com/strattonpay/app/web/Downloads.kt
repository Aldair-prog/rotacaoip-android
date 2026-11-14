package br.com.strattonpay.app.web

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import java.io.File

/** Salva comprovantes e arquivos que o painel manda baixar. */
object Downloads {

    /** Pasta dos arquivos baixados, com o nome do titular: Downloads/OPERAÇÕES DIGITAIS - … */
    private val FOLDER = br.com.strattonpay.app.BuildConfig.COPYRIGHT_HOLDER

    /** Download normal (http/https), levando os cookies da sessão do painel. */
    fun enqueue(context: Context, url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                if (userAgent != null) addRequestHeader("User-Agent", userAgent)
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$FOLDER/$name")
            }
            context.getSystemService(DownloadManager::class.java)?.enqueue(request)
            Toast.makeText(context, "Baixando $name…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Não foi possível baixar o arquivo", Toast.LENGTH_LONG).show()
        }
    }

    /** Arquivo gerado no próprio navegador (blob/data URL), ex.: comprovante em PDF. */
    fun saveDataUrl(context: Context, dataUrl: String?, suggestedName: String?) {
        if (dataUrl.isNullOrBlank() || !dataUrl.startsWith("data:")) {
            Toast.makeText(context, "Não foi possível baixar o arquivo", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val header = dataUrl.substringBefore(',')
            val payload = dataUrl.substringAfter(',')
            val mime = header.removePrefix("data:").substringBefore(';').ifBlank { "application/octet-stream" }
            val bytes = if (header.contains(";base64")) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
            val name = fixName(suggestedName, mime)

            val uri: Uri? = if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                }
                val resolver = context.contentResolver
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.also { u ->
                    resolver.openOutputStream(u)?.use { it.write(bytes) }
                }
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, FOLDER).apply { mkdirs() }
                File(dir, name).writeBytes(bytes)
                null
            }

            Toast.makeText(context, "$name salvo em Downloads/$FOLDER", Toast.LENGTH_SHORT).show()
            if (uri != null) {
                val open = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(open)
                } catch (e: Exception) {
                    // nenhum app para abrir esse tipo; o arquivo fica em Downloads
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Não foi possível salvar o arquivo", Toast.LENGTH_LONG).show()
        }
    }

    private fun fixName(suggested: String?, mime: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        var base = suggested?.takeIf { it.isNotBlank() && !it.startsWith("downloadfile") }
            ?: "stratton-${System.currentTimeMillis()}"
        if (base.endsWith(".bin") && ext != null) base = base.removeSuffix(".bin")
        return if (ext != null && !base.lowercase().endsWith(".$ext")) "$base.$ext" else base
    }
}
