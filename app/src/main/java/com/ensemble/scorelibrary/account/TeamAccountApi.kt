package com.ensemble.scorelibrary.account

import com.ensemble.scorelibrary.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

/** Deployed team service; credentials are always entered by the account owner. */
const val TEAM_SERVICE_URL = "https://124355.xyz:18443"

data class TeamPrincipal(val accountId: String, val memberId: String, val teamId: String, val teamName: String,
    val username: String, val name: String, val role: String, val backupConsent: Boolean,
    val membership: String = "joined", val membershipSupported: Boolean = false, val numberingProtocol: Int = 1,
    val multiTeamProtocol:Int=0) {
    val joined: Boolean get() = membership == "joined"
    companion object {
        fun parse(json: JSONObject): TeamPrincipal {
            fun id(key: String) = json.getString(key).also { require(UUID.fromString(it).toString() == it) }
            fun text(key: String) = json.getString(key).also { require(it.length in 1..100) }
            val role = json.getString("role").also { require(it in setOf("admin", "member")) }
            val supported = json.has("membership")
            val membership = if (supported) json.getString("membership") else "joined"
            require(membership in MEMBERSHIP_STATES && (role != "admin" || membership == "joined"))
            val numbering = if (json.has("numberingProtocol")) json.get("numberingProtocol").also {
                require(it is Int && it in 1..2) { "服务编号协议不受支持" }
            } as Int else 1
            val multi=if(json.has("multiTeamProtocol"))json.get("multiTeamProtocol").also { require(it is Int && it==1) } as Int else 0
            return TeamPrincipal(id("accountId"), id("memberId"), id("teamId"), text("teamName"),
                text("username"), text("displayName"), role, json.getBoolean("backupConsent"), membership, supported, numbering,multi)
        }
    }
}

internal val MEMBERSHIP_STATES = setOf("unjoined", "pending", "rejected", "joined")
data class TeamMembership(val state: String, val revision: Long) {
    companion object {
        fun parse(json: JSONObject) = TeamMembership(json.getString("state").also { require(it in MEMBERSHIP_STATES) },
            json.getLong("revision").also { require(it in 0 until 9_007_199_254_740_992L) })
    }
}
data class TeamJoinRequest(val id: String, val username: String, val name: String, val revision: Long)

data class TeamSession(val server: String, val token: String, val expiresAt: Long, val principal: TeamPrincipal,
    val verifiedAt: Long = 0, val lastSeenAt: Long = verifiedAt, val deviceId: String = "", val offline: Boolean = false) {
    override fun toString() = "TeamSession(credentials=redacted)"
}
data class TeamDevice(val id: String, val name: String, val revoked: Boolean, val current: Boolean)
data class TeamMember(val id: String, val name: String, val username: String, val active: Boolean, val admin: Boolean)

class AccountRejected(val status: Int, val code: String) : IOException("账号请求未完成 ($status)")
class SignupUnconfirmed(cause:IOException) : IOException("注册结果尚未确认",cause)

/** HTTPS only in normal builds. Debug permits exact loopback for isolated automated tests. */
fun accountServer(value: String, allowLoopback: Boolean = BuildConfig.DEBUG): String {
    val uri = URI(value.trim().trimEnd('/'))
    require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535) && !uri.rawPath.contains("..")) { "团队服务地址无效" }
    require(uri.scheme == "https" || allowLoopback && uri.scheme == "http" && uri.host == "127.0.0.1") {
        "团队服务必须使用 HTTPS"
    }
    return uri.toASCIIString()
}

class TeamAccountApi {
    fun signup(server: String, device: String, deviceName: String, username: String, password: String,
        name: String, consent: Boolean): TeamSession {
        val base = accountServer(server)
        val body = JSONObject().put("username", username.trim()).put("password", password).put("displayName", name.trim())
            .put("deviceId", device).put("deviceName", deviceName).put("backupConsent", consent)
        val response = try { request(base, "/v1/auth/signup", device, body = body) }
            catch (error: AccountRejected) {
                // Older instances authenticate unknown paths, producing 401 rather
                // than 404. Anonymous signup is unavailable in either case.
                if (error.status in setOf(401, 404)) throw AccountRejected(404, "registration_unavailable") else throw error
            }
        return parseSession(base, response).also { require(it.principal.membershipSupported && !it.principal.joined) }
    }
    fun request(server: String, path: String, device: String, session: TeamSession? = null, body: JSONObject? = null): JSONObject {
        val base = accountServer(server)
        require((path.startsWith("/v1/") || path.startsWith("/v2/")) && !path.contains('?') && !path.contains('#'))
        require(session == null || session.server == base)
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("X-Device-Id", device)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Score-Number-Reuse", "1")
            session?.let { connection.setRequestProperty("Authorization", "Bearer ${it.token}") }
            session?.let { connection.setRequestProperty("X-Score-Team",it.principal.teamId) }
            if (body != null) {
                val data = body.toString().toByteArray(Charsets.UTF_8)
                require(data.size <= 16384)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(data.size)
                connection.outputStream.use { it.write(data) }
            }
            val status = connection.responseCode
            if (status in 300..399) throw AccountRejected(status, "redirect_rejected")
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            val data = stream?.use {
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                val maximum=when {
                    path.matches(Regex("/v[12]/program-freezes/[a-f0-9-]{36}/[a-f0-9-]{36}")) -> 64*1024*1024+65536
                    path.replaceFirst("/v2/", "/v1/") in setOf("/v1/catalog", "/v1/admin/ensemble", "/v1/admin/artifacts") -> 4*1024*1024
                    else -> 256*1024
                }
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= maximum) { "服务响应过大" }
                    bytes.write(buffer, 0, count)
                }
                bytes.toString("UTF-8")
            }.orEmpty()
            if (status != 200) {
                val code = runCatching { JSONObject(data).optString("error") }.getOrDefault("")
                throw AccountRejected(status, code)
            }
            return JSONObject(data)
        } finally { connection.disconnect() }
    }

    fun login(server: String, device: String, deviceName: String, username: String, password: String,
        name: String? = null, invitation: String? = null, consent: Boolean = false): TeamSession {
        val base = accountServer(server)
        val body = JSONObject().put("username", username.trim()).put("password", password)
            .put("deviceId", device).put("deviceName", deviceName)
        if (name != null) body.put("displayName", name.trim()).put("invitation", invitation.orEmpty().trim()).put("backupConsent", consent)
        val result = request(base, if (name == null) "/v1/auth/login" else "/v1/auth/register", device, body = body)
        return parseSession(base, result)
    }
    private fun parseSession(base: String, result: JSONObject): TeamSession {
        val token = result.getString("token").also { require(it.matches(Regex("[A-Za-z0-9_-]{32,128}"))) }
        val expires = result.getLong("expiresAt").also { require(it > System.currentTimeMillis() / 1000) }
        return TeamSession(base, token, expires, TeamPrincipal.parse(result.getJSONObject("principal")))
    }
}

fun accountError(error: Exception): String = when (error) {
    is SignupUnconfirmed -> "注册连接中断，结果尚未确认。请先用刚才的账号和密码登录；若提示账号名已使用，也请登录已有账号，无需重复注册。"
    is com.ensemble.scorelibrary.sync.TeamCleanupFailure -> error.message.orEmpty()
    is OfflineUnavailable -> error.message.orEmpty()
    is AccountRejected -> when (error.code) {
        "login_failed" -> "账号或密码不正确，或该账号已停用"
        "session_invalid" -> "登录已过期或设备已撤销，请重新登录"
        "device_revoked" -> "此设备已撤销，请联系管理员恢复设备"
        "invitation_invalid" -> "邀请码无效、已使用或已过期"
        "username_unavailable" -> "账号名已被使用，请尝试登录已有账号，或换一个账号名"
        "invalid_username" -> "账号名需为 3–64 位小写字母、数字、点、下划线或短横线"
        "password_length" -> "密码需要 12–128 个字符"
        "try_later" -> "尝试次数过多，请 15 分钟后再试"
        "consent_required" -> "请先阅读并确认团队批注备份说明"
        "pressure_data_required" -> "此页包含压感笔迹，请先更新应用后再编辑；原批注和本机待传内容均保留"
        "registration_unavailable" -> "NAS 尚未开放自助注册，请等待服务更新；已有账号仍可登录或使用邀请码注册"
        "membership_required" -> "请先通过乐团码申请加入，等待管理员审核"
        "join_code_invalid" -> "乐团码不正确或已更换，请向管理员索取新码"
        "membership_changed", "application_exists" -> "申请状态已变化，请刷新账号状态后再操作"
        "join_code_changed" -> "乐团码已被更换，请刷新后再操作"
        "team_membership_unavailable" -> "你在当前团队已停用或无法进入；账号仍登录，可在「我的团队」选择其他团队"
        "team_session_mismatch" -> "团队状态已变化，请刷新账号后重新选择团队"
        "team_client_upgrade_required" -> "请更新应用后进入这个团队"
        "team_creation_authorization_expired" -> "创建任务的旧核验已到期，请重新确认创建；会沿用原任务，不重复创建"
        "team_creation_capacity" -> "已达到当前团队数量上限，请联系 NAS 管理人员"
        "team_creation_failed" -> "团队创建未完成，请重试原任务；已有团队和文件保持"
        "request_reused" -> "本次请求已变化，请刷新状态后重试"
        "forbidden" -> "当前账号没有此操作权限"
        "number_conflict" -> "此曲目序号已有不同名称或分类，请核对已入库曲目后补传"
        "part_version_conflict" -> "该编号的乐器分谱已存在；请勿重复上传，同曲其他乐器可以继续补传"
        "publication_cancelled" -> "本次批次已取消，请重新选择 PDF"
        "https_required", "redirect_rejected" -> "团队服务连接配置异常，请联系管理员"
        else -> "服务暂时无法完成操作，请稍后重试"
    }
    is IllegalArgumentException -> "填写内容或服务响应有误，请检查后重试"
    else -> "无法连接团队服务，请检查网络后重试；持续失败请联系管理员"
}
