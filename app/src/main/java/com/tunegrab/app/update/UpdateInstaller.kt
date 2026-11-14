package com.tunegrab.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.core.content.FileProvider
import com.tunegrab.app.security.boundedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** An update is executable code, not a media download: fail closed before handing it to Android. */
object UpdateInstaller {
    private val downloadMutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.MINUTES)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            if (!UpdatePolicy.allowedTransport(chain.request().url.toString())) {
                throw IOException("origem de atualização não permitida")
            }
            chain.proceed(chain.request())
        }
        .build()

    /** Internal private storage prevents other apps on Android 7/8 from replacing an update APK. */
    fun updatesDir(context: Context): File = File(context.applicationContext.filesDir, "updates")

    fun apkFile(context: Context, info: UpdateChecker.UpdateInfo): File {
        require(UpdatePolicy.isVersion(info.version)) { "versão de atualização inválida" }
        return File(updatesDir(context), "tunegrab-${info.version}.apk")
    }

    private fun receipt(file: File) = File(file.parentFile, "${file.name}.sha256")

    fun clearAllApks(context: Context): Int {
        val legacy = context.getExternalFilesDir(null)?.let { File(it, "updates") }
        var removed = 0
        listOfNotNull(updatesDir(context), legacy).distinctBy { it.absolutePath }.forEach { dir ->
            dir.listFiles()?.forEach { item ->
                val count = if (item.isDirectory) item.walkBottomUp().count() else 1
                if (item.deleteRecursively()) removed += count
            }
        }
        return removed
    }

    private fun cleanOldApks(keep: File) {
        val keepNames = setOf(keep.name, receipt(keep).name, "${keep.name}.part")
        keep.parentFile?.listFiles()?.forEach { if (it.isFile && it.name !in keepNames) it.delete() }
    }

    /** Cheap UI check. Full digest/package/signature checks run again immediately before installation. */
    fun isReady(context: Context, info: UpdateChecker.UpdateInfo): Boolean = runCatching {
        val file = apkFile(context, info)
        val proof = receipt(file)
        file.isFile && file.length() in 1..UpdatePolicy.MAX_APK_BYTES &&
            (info.apkSize <= 0 || file.length() == info.apkSize) &&
            proof.isFile && proof.length() in 64..66 && UpdatePolicy.isSha256(proof.readText().trim())
    }.getOrDefault(false)

    fun isOnMetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    suspend fun download(context: Context, info: UpdateChecker.UpdateInfo, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            downloadMutex.withLock {
                requireMetadata(info)
                val target = apkFile(context, info)
                val dir = target.parentFile ?: throw IOException("pasta de update indisponível")
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("pasta de update indisponível")
                cleanOldApks(target)
                val partial = File(dir, "${target.name}.part")
                val coroutineContext = currentCoroutineContext()
                try {
                    var effective = info
                    try {
                        downloadTo(info.apkUrl, partial, info, onProgress) { coroutineContext.ensureActive() }
                    } catch (notFound: Http404) {
                        effective = UpdateChecker.resolveApkInfo(info) ?: throw notFound
                        requireMetadata(effective)
                        if (effective.apkUrl == info.apkUrl) throw notFound
                        downloadTo(effective.apkUrl, partial, effective, onProgress) { coroutineContext.ensureActive() }
                    }
                    coroutineContext.ensureActive()
                    val expected = expectedDigest(effective)
                    validateApk(context, partial, effective, expected)
                    coroutineContext.ensureActive()
                    if (!partial.renameTo(target)) throw IOException("não foi possível concluir o APK")
                    receipt(target).writeText(expected.lowercase() + "\n")
                    target
                } catch (error: Throwable) {
                    partial.delete()
                    target.delete()
                    receipt(target).delete()
                    throw error
                }
            }
        }

    private fun requireMetadata(info: UpdateChecker.UpdateInfo) {
        if (!UpdatePolicy.isRelease(info.url, info.version) || !UpdatePolicy.isApkUrl(info.apkUrl, info.version) ||
            info.apkSize < 0 || info.apkSize > UpdatePolicy.MAX_APK_BYTES) {
            throw IOException("metadados de atualização inválidos")
        }
    }

    private fun downloadTo(
        url: String, target: File, info: UpdateChecker.UpdateInfo,
        onProgress: (Int) -> Unit, checkCancelled: () -> Unit
    ) {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code == 404) throw Http404()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("resposta sem corpo")
            val declared = body.contentLength()
            if (declared > UpdatePolicy.MAX_APK_BYTES) throw IOException("APK excede o tamanho permitido")
            val total = if (declared > 0) declared else info.apkSize
            var read = 0L
            var lastPercent = -1
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        if (read > UpdatePolicy.MAX_APK_BYTES || (info.apkSize > 0 && read > info.apkSize)) {
                            throw IOException("APK excede o tamanho esperado")
                        }
                        output.write(buffer, 0, n)
                        if (total > 0) {
                            val percent = (read * 100 / total).toInt().coerceIn(0, 99)
                            if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                        }
                    }
                }
            }
            if (read <= 0 || (info.apkSize > 0 && read != info.apkSize) || (declared >= 0 && read != declared)) {
                throw IOException("APK incompleto ou de tamanho inesperado")
            }
        }
    }

    /** Atom users get a CDN-hosted checksum without depending on the GitHub API IP quota. */
    private suspend fun expectedDigest(info: UpdateChecker.UpdateInfo): String {
        if (UpdatePolicy.isSha256(info.apkSha256)) return info.apkSha256
        val checksumUrl = UpdatePolicy.checksumUrl(info.url) ?: throw IOException("release inválida")
        val filename = info.apkUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull() ?: throw IOException("APK inválido")
        val fromSums = http.newCall(Request.Builder().url(checksumUrl).build()).execute().use { response ->
            if (response.isSuccessful) {
                response.body?.boundedText(65_536)?.let { UpdatePolicy.checksumFor(it, filename) }
            } else null
        }
        if (fromSums != null) return fromSums
        val resolved = UpdateChecker.resolveApkInfo(info)
        if (resolved != null && resolved.apkUrl == info.apkUrl && UpdatePolicy.isSha256(resolved.apkSha256)) {
            return resolved.apkSha256
        }
        throw IOException("release sem checksum SHA-256 verificável; atualização não será instalada")
    }

    @Suppress("DEPRECATION")
    private fun validateApk(context: Context, file: File, info: UpdateChecker.UpdateInfo, expected: String) {
        if (!UpdatePolicy.isSha256(expected) || !sha256(file).equals(expected, ignoreCase = true)) {
            throw IOException("a integridade do APK não confere")
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IOException("APK inválido")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (candidate.packageName != context.packageName || candidate.versionName != info.version) {
            throw IOException("APK não pertence a esta atualização do TuneGrab")
        }
        val candidateVersion = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val installedVersion = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (candidateVersion <= installedVersion) throw IOException("atualização antiga ou repetida")
        val appFlags = candidate.applicationInfo?.flags ?: throw IOException("APK sem informações do aplicativo")
        if (appFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0) throw IOException("APK de depuração não pode atualizar o release")
        val current = currentSigners(installed)
        val next = currentSigners(candidate)
        val history = if (Build.VERSION.SDK_INT >= 28) {
            candidate.signingInfo?.signingCertificateHistory?.map { digest(it.toByteArray()) }?.toSet().orEmpty()
        } else next
        if (!UpdatePolicy.trustsSigner(current, next, history)) throw IOException("assinatura do APK não é confiável")
    }

    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { digest(it.toByteArray()) }?.toSet().orEmpty()

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private class Http404 : IOException("HTTP 404")

    fun needsInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesScreen(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(context: Context, info: UpdateChecker.UpdateInfo): Intent {
        if (!isReady(context, info)) throw IOException("APK de atualização não está pronto")
        val file = apkFile(context, info)
        validateApk(context, file, info, receipt(file).readText().trim())
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
}
