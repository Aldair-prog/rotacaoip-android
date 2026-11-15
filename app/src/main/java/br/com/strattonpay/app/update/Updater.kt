package br.com.strattonpay.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import br.com.strattonpay.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Atualização direto pelo app, sem Play Store.
 * As versões novas ficam publicadas nos "releases" do GitHub; o app baixa, confere
 * se é do mesmo dono (mesma assinatura) e abre o instalador do Android.
 */
object Updater {

    data class Release(val build: Int, val version: String, val url: String, val notes: String, val sizeBytes: Long)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val TAG_PREFIX = "stratton-build-"

    /** Procura uma versão mais nova que a instalada. null = já está na última. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val list = JSONArray(response.body?.string().orEmpty())
            var best: Release? = null
            for (i in 0 until list.length()) {
                val rel = list.getJSONObject(i)
                val tag = rel.optString("tag_name")
                if (!tag.startsWith(TAG_PREFIX)) continue
                // Builds temporários (sem a chave fixa) não instalam por cima: ficam de fora.
                if (rel.optString("name").contains("temporário", ignoreCase = true)) continue
                val build = tag.removePrefix(TAG_PREFIX).toIntOrNull() ?: continue
                if (build <= BuildConfig.VERSION_CODE || (best != null && build <= best.build)) continue
                val assets = rel.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (asset.optString("name") == BuildConfig.UPDATE_ASSET) {
                        best = Release(
                            build = build,
                            version = "1.2.$build",
                            url = asset.optString("browser_download_url"),
                            notes = cleanNotes(rel.optString("body")),
                            sizeBytes = asset.optLong("size"),
                        )
                    }
                }
            }
            best
        }
    }

    /** Baixa o APK novo para a pasta interna do app, avisando o progresso (0..1). */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "atualizacao-${release.build}.apk")
            val request = Request.Builder().url(release.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("download ${response.code}")
                val body = response.body ?: throw IllegalStateException("download vazio")
                val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            file
        }

    sealed interface InstallResult {
        data object Started : InstallResult
        data object NeedsPermission : InstallResult
        data class Rejected(val reason: String) : InstallResult
    }

    /** Confere o arquivo e abre o instalador do Android. */
    fun install(activity: Activity, file: File): InstallResult {
        val pm = activity.packageManager
        val archive = archiveInfo(pm, file) ?: return InstallResult.Rejected("O arquivo baixado está corrompido. Tente de novo.")
        if (archive.packageName != activity.packageName) {
            return InstallResult.Rejected("O arquivo baixado não é deste app.")
        }
        val installed = signatures(installedInfo(pm, activity.packageName))
        val incoming = signatures(archive)
        if (installed.isEmpty() || incoming.isEmpty() || installed != incoming) {
            return InstallResult.Rejected(
                "Esta versão foi assinada com outra chave e não instala por cima. " +
                    "Desinstale o app e instale a versão nova manualmente uma única vez.",
            )
        }
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return InstallResult.NeedsPermission
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
        return InstallResult.Started
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return pm.getPackageArchiveInfo(file.absolutePath, flags)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, pkg: String): PackageInfo? = try {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        pm.getPackageInfo(pkg, flags)
    } catch (e: Exception) {
        null
    }

    /** Impressões digitais (SHA-256) dos certificados de assinatura. */
    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { si -> if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory }
        } else {
            info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Notas da versão sem as linhas técnicas do commit. */
    private fun cleanNotes(body: String): String =
        body.lines()
            .filterNot { it.startsWith("Co-Authored-By") || it.startsWith("Claude-Session") || it.isBlank() }
            .joinToString("\n")
            .trim()
            .take(600)
}
