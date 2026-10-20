package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.*
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class InkHttpReply(val status: Int, val body: JSONObject)
interface TeamInkTransport {
    suspend fun request(session: TeamSession, path: String, body: JSONObject? = null): InkHttpReply
}

class TeamInkClient @Inject constructor(private val gateway: TeamAccountGateway) : TeamInkTransport {
    override suspend fun request(session: TeamSession, path: String, body: JSONObject?): InkHttpReply {
        if (session.offline) throw OfflineUnavailable("请联网核验后同步批注")
        require(path.startsWith("/v1/") && !path.contains('?') && !path.contains('#') && !path.contains(".."))
        val connection = URL(accountServer(session.server) + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 15000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${session.token}")
            connection.setRequestProperty("X-Score-Team",session.principal.teamId)
            connection.setRequestProperty("X-Device-Id", gateway.device)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 8 * 1024 * 1024 + 16384)
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            coroutineContext.ensureActive()
            val status = connection.responseCode
            if (status in 300..399) throw AccountRejected(status, "redirect_rejected")
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val bytes = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(16384)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    require(bytes.size() + count <= 17 * 1024 * 1024) { "批注响应过大" }
                    bytes.write(buffer, 0, count)
                }
            }
            val result = JSONObject(bytes.toString("UTF-8"))
            if(status==409 && result.optString("error")=="pressure_data_required" && body!=null) {
                // An outbox written by an older app can survive an update. Surface both versions
                // through the existing guarded conflict flow, without resubmitting or flattening.
                val target=Regex("/v1/annotations/([a-f0-9-]{36})/(0|[1-9][0-9]{0,4})/(personal|draft|published)").matchEntire(path)
                    ?: throw IllegalArgumentException("批注目标无效，原笔迹保留")
                val remote=request(session,path).body
                require(remote.getString("accountId")==session.principal.accountId &&
                    remote.getString("artifactHash")==body.getString("artifactHash") &&
                    remote.getString("artifactId")==target.groupValues[1] && remote.get("pageIndex")==target.groupValues[2].toInt() &&
                    remote.getString("kind")==target.groupValues[3]) { "批注身份或原谱已变化，双方笔迹保留" }
                return InkHttpReply(409,JSONObject().put("error","annotation_conflict")
                    .put("requestId",body.getString("requestId")).put("candidate",body.getJSONArray("marks"))
                    .put("current",JSONObject().put("revision",remote.get("revision")).put("marks",remote.getJSONArray("marks")))
                    .put("revision",remote.get("revision")))
            }
            if (status != 200 && !(status == 409 && result.optString("error") == "annotation_conflict"))
                throw AccountRejected(status, result.optString("error"))
            return InkHttpReply(status, result)
        } finally { connection.disconnect() }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class TeamInkModule {
    @dagger.Binds abstract fun transport(client: TeamInkClient): TeamInkTransport
}
