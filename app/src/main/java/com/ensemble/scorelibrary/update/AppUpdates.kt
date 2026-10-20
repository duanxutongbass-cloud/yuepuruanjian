package com.ensemble.scorelibrary.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ensemble.scorelibrary.account.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject

data class AppRelease(val code: Long, val name: String, val size: Long, val hash: String, val notes: String) {
    companion object {
        fun parse(json: JSONObject, packageName: String, installed: Long): AppRelease? {
            if (!json.getBoolean("available")) return null
            require(json.getString("packageName") == packageName) { "更新包应用标识不符" }
            val code = json.getLong("versionCode").also { require(it in 1..2_099_999_999) }
            val name = json.getString("versionName").also { require(it.length in 1..40 && it.none { c -> c.isISOControl() }) }
            val size = json.getLong("size").also { require(it in 1..150L * 1024 * 1024) }
            val hash = json.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val notes = json.getString("notes").also { require(it.length <= 2000) }
            return if (code <= installed) null else AppRelease(code, name, size, hash, notes)
        }
    }
}

internal fun updateIdentityMatches(expectedPackage: String, packageName: String?, installed: Long, archiveVersion: Long,
    releaseVersion: Long, installedSigners: Set<String>, archiveSigners: Set<String>): Boolean =
    packageName == expectedPackage && archiveVersion == releaseVersion && archiveVersion > installed &&
        installedSigners.isNotEmpty() && installedSigners == archiveSigners

internal class AppUpdateClient(private val server: String = TEAM_SERVICE_URL) {
    fun check() = TeamAccountApi().request(server, "/v1/app-release", "")
    suspend fun download(release: AppRelease, target: File): Unit = withContext(Dispatchers.IO) {
        val connection = URL(accountServer(server) + "/v1/app-release/apk").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 10000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("If-Match", "\"${release.hash}\"")
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
            require(connection.responseCode == 200 && connection.contentLengthLong == release.size &&
                connection.getHeaderField("ETag") == "\"${release.hash}\"" &&
                connection.contentType?.substringBefore(';') == "application/vnd.android.package-archive") { "更新包已变化或服务不可用，请重新检查更新" }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    size += count; require(size <= release.size) { "更新包大小异常" }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                output.fd.sync()
            } }
            require(size == release.size && digest.digest().joinToString("") { "%02x".format(it) } == release.hash) { "更新包校验失败，请重新下载" }
        } finally { connection.disconnect() }
    }
}

class AppUpdates @Inject constructor(@ApplicationContext private val context: Context) {
    private val client = AppUpdateClient()
    @Suppress("DEPRECATION")
    private fun version(info: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    @Suppress("DEPRECATION")
    private fun flags() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION")
    private fun signers(info: android.content.pm.PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
        }.toSet()
    private fun verify(file: File, release: AppRelease) {
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags())
        val archive = checkNotNull(manager.getPackageArchiveInfo(file.path, flags())) { "无法读取更新安装包" }
        check(updateIdentityMatches(context.packageName, archive.packageName, version(installed), version(archive), release.code,
            signers(installed), signers(archive))) { "更新包签名、版本或应用标识不符，已阻止安装" }
        check((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "此设备的系统版本不支持该更新" }
        check(file.length() == release.size && com.ensemble.scorelibrary.sync.TeamLibrarySync.hash(file) == release.hash) { "更新包校验失败" }
    }
    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        try { AppRelease.parse(client.check(), context.packageName, version(context.packageManager.getPackageInfo(context.packageName, 0))) }
        catch (error: AccountRejected) { if (error.status == 404) null else throw error }
    }
    suspend fun download(release: AppRelease): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "app-updates").apply { check(isDirectory || mkdirs()) }
        val pending = File.createTempFile("update-", ".pending", directory)
        try {
            client.download(release, pending)
            verify(pending, release)
            val target = File(directory, release.hash + ".apk")
            java.nio.file.Files.move(pending.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            target
        } finally { pending.delete() }
    }
    suspend fun install(file: File, release: AppRelease): String {
        withContext(Dispatchers.IO) { verify(file, release) }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "请允许此 App 安装更新，然后返回点击「安装新版」"
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return "已打开系统安装界面，请确认覆盖安装；无需卸载旧版"
    }
}

data class AppUpdateState(val busy: Boolean = false, val release: AppRelease? = null, val file: File? = null, val message: String = "")
@HiltViewModel
class AppUpdateViewModel @Inject constructor(private val updates: AppUpdates) : ViewModel() {
    private val mutable = MutableStateFlow(AppUpdateState())
    val state = mutable.asStateFlow()
    private var lastAutomaticCheck: Long? = null
    fun checkAutomatically() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (mutable.value.busy || lastAutomaticCheck?.let { now - it < 15 * 60_000 } == true) return
        lastAutomaticCheck = now
        check()
    }
    fun check() = operation {
        val release = updates.check()
        mutable.value = AppUpdateState(busy = true, release = release, message = if (release == null) "当前没有可用的新版本" else "发现新版 ${release.name}")
    }
    fun download() = operation {
        val release = checkNotNull(mutable.value.release)
        mutable.value = mutable.value.copy(message = "正在下载更新，请保持网络连接")
        val file = updates.download(release)
        mutable.value = mutable.value.copy(file = file, message = "更新包已校验，可以覆盖安装")
    }
    fun install() = operation {
        mutable.value = mutable.value.copy(message = updates.install(checkNotNull(mutable.value.file), checkNotNull(mutable.value.release)))
    }
    private fun operation(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(message =
                if (error is IllegalStateException || error is IllegalArgumentException) error.message ?: "更新未完成"
                else "更新服务暂时无法连接，请稍后重试") }
            finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
