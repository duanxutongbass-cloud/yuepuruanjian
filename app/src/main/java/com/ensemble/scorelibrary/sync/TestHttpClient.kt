package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.BuildConfig
import com.ensemble.scorelibrary.domain.sync.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Deliberately restricted run-local protocol; not commercial account policy. */
class TestHttpClient(private val base: String = "http://127.0.0.1:8765") {
    init {
        check(BuildConfig.HTTP_TEST) { "Temporary test transport is disabled in this build" }
        require(base == "http://127.0.0.1:8765")
    }
    data class Login(val token: String, val team: String, val account: String, val member: String, val name: String)
    class Rejected(val status: Int) : java.io.IOException("服务拒绝请求 ($status)")
    private fun connection(path: String, login: Login?, device: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = 4000; instanceFollowRedirects = false
            setRequestProperty("X-Device-Id", device)
            login?.let { setRequestProperty("Authorization", "Bearer ${it.token}") }
        }
    fun json(path: String, device: String, login: Login? = null, body: JSONObject? = null): JSONObject {
        val c = connection(path, login, device)
        try {
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode != 200) throw Rejected(c.responseCode)
            return c.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(1024 * 1024)
                var used = 0
                while (used < chars.size) { val n = reader.read(chars, used, chars.size - used); if (n < 0) break; used += n }
                require(used < chars.size) { "清单过大" }
                JSONObject(String(chars, 0, used))
            }
        } finally { c.disconnect() }
    }
    fun login(account: String, code: String, device: String): Login {
        val response = json("/login", device, body = JSONObject().put("account", account).put("loginCode", code).put("deviceId", device))
        val p = response.getJSONObject("principal")
        listOf("teamId", "accountId", "memberId").forEach { key ->
            require(java.util.UUID.fromString(p.getString(key)).toString() == p.getString(key))
        }
        return Login(response.getString("token"), p.getString("teamId"), p.getString("accountId"), p.getString("memberId"), p.getString("displayName"))
    }
    fun manifest(login: Login, device: String): RemoteManifest = RemoteManifest.parse(json("/manifest", device, login), login)
    fun authorize(login: Login, device: String, manifest: RemoteManifest) {
        json("/authorize-commit", device, login, JSONObject().put("revision", manifest.core.revision).put("artifacts", manifest.json.getJSONArray("artifacts")))
    }
    /** Partial file is private to identity + immutable hash. Retry always re-authorizes at server. */
    fun download(login: Login, device: String, artifact: SyncArtifact, partial: File) {
        if (partial.length() >= artifact.size) partial.delete()
        val offset = partial.length()
        val c = connection("/artifacts/${artifact.artifactId}", login, device)
        try {
            c.setRequestProperty("If-Match", artifact.hash)
            if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
            if (c.responseCode != if (offset > 0) 206 else 200) throw Rejected(c.responseCode)
            require(c.getHeaderField("ETag") == artifact.hash)
            if (offset > 0) require(c.getHeaderField("Content-Range") == "bytes $offset-${artifact.size - 1}/${artifact.size}")
            var size = offset
            c.inputStream.use { input -> FileOutputStream(partial, offset > 0).use { out ->
                val buffer = ByteArray(16384)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    size += n
                    require(size <= artifact.size) { "资源长度超出清单" }
                    out.write(buffer, 0, n)
                }
                out.fd.sync()
            } }
            require(size == artifact.size) { "下载中断，请重试；原有可用版本已保留" }
            if (hash(partial) != artifact.hash) { partial.delete(); error("字节校验失败；原有可用版本已保留") }
        } finally { c.disconnect() }
    }
    companion object {
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val b = ByteArray(16384); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class RemoteScore(val artifact: SyncArtifact, val pdf: String, val piece: String, val part: String,
    val definition: String, val title: String, val partName: String, val version: Int)
data class RemoteManifest(val core: SyncManifest, val scores: List<RemoteScore>, val json: JSONObject) {
    companion object {
        fun parse(json: JSONObject, login: TestHttpClient.Login): RemoteManifest {
            val p = json.getJSONObject("principal")
            require(p.getString("accountId") == login.account && p.getString("memberId") == login.member && p.getString("teamId") == login.team)
            val array = json.getJSONArray("artifacts")
            require(array.length() <= 100)
            val scores = (0 until array.length()).map { index ->
                val a = array.getJSONObject(index)
                fun id(key: String) = a.getString(key).also { require(java.util.UUID.fromString(it).toString() == it) }
                val map = a.getJSONArray("pageMap")
                require(a.getBoolean("independentSource")) { "此最小适配只接收独立原始分谱" }
                val pages = (0 until map.length()).map { n ->
                    val page = map.getJSONObject(n)
                    require(page.getInt("sourcePage") == n && page.getString("transform") == "identity")
                    SyncPage(n, "identity")
                }
                val artifact = SyncArtifact(id("artifactId"), id("sourceVersionId"), a.getString("hash"), a.getLong("size"), pages)
                require(artifact.hash.matches(Regex("[a-f0-9]{64}")) && artifact.size in 1..(16L * 1024 * 1024) && pages.size in 1..100)
                RemoteScore(artifact, id("scorePdfId"), id("pieceId"), id("partId"), id("definitionId"), a.getString("title"), a.getString("partName"), a.getInt("versionNumber").also { require(it > 0) })
            }
            require(scores.map { it.pdf }.distinct().size == scores.size && scores.map { it.part }.distinct().size == scores.size)
            return RemoteManifest(SyncManifest(json.getInt("protocolVersion"), json.getLong("revision"), json.getString("operationId"), scores.map { it.artifact }), scores, json)
        }
    }
}
