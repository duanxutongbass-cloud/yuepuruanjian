package com.ensemble.scorelibrary.account

import org.json.JSONObject
import java.io.IOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

internal fun temporarilyUnavailable(error: Exception): Boolean = when (error) {
    is AccountRejected -> error.status in 500..599
    is SSLException, is ProtocolException, is OfflineUnavailable -> false
    is IOException -> true
    else -> false
}

internal fun TeamPrincipal.json() = JSONObject().put("accountId", accountId).put("memberId", memberId).put("teamId", teamId)
    .put("teamName", teamName).put("username", username).put("displayName", name).put("role", role).put("backupConsent", backupConsent)
    .put("numberingProtocol", numberingProtocol)
    .also { if (membershipSupported || !joined) it.put("membership", membership)
        if(multiTeamProtocol>0)it.put("multiTeamProtocol",multiTeamProtocol) }

/** A successful server check, not a remembered password or a failed connection, creates a lease. */
internal class VerifiedSessionMemory(private val vault: AccountVault, private val server: String, private val device: String,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    fun read(): TeamSession? {
        val json = vault.readForServer(server) ?: return null
        return try {
            val token = json.getString("token").also { require(it.matches(Regex("[A-Za-z0-9_-]{32,128}"))) }
            val binding = json.optString("deviceId")
            require(binding.isEmpty() || binding == device)
            TeamSession(server, token, json.getLong("expiresAt"), TeamPrincipal.parse(json.getJSONObject("principal")),
                json.optLong("verifiedAt"), json.optLong("lastSeenAt"), binding)
        } catch (error: Exception) { vault.clear(); throw OfflineUnavailable("本机登录凭据无效，请联网重新登录") }
    }
    fun confirm(session: TeamSession): TeamSession {
        val now = clock()
        require(session.server == server && session.expiresAt > now && session.expiresAt <= now + OFFLINE_SECONDS + CLOCK_SKEW_SECONDS)
        return session.copy(verifiedAt = now, lastSeenAt = now, deviceId = device, offline = false)
    }
    fun save(session: TeamSession) {
        require(session.server == server && session.deviceId == device)
        vault.write(JSONObject().put("server", session.server).put("token", session.token).put("expiresAt", session.expiresAt)
            .put("principal", session.principal.json()).put("verifiedAt", session.verifiedAt)
            .put("lastSeenAt", session.lastSeenAt).put("deviceId", device).toString())
    }
    fun verify(session: TeamSession, fetch: (TeamSession) -> JSONObject): TeamSession {
        require(session.server == server && (session.deviceId.isEmpty() || session.deviceId == device))
        if (session.expiresAt <= clock()) { vault.clear(); throw AccountRejected(401, "session_invalid") }
        val response = try { fetch(session.copy(offline = false)) } catch (error: Exception) {
            if (error is AccountRejected && error.status in setOf(401, 403)) { vault.clear(); throw error }
            if (!temporarilyUnavailable(error)) throw error
            if (session.deviceId != device) throw OfflineUnavailable()
            val lease = OfflineLease(session.verifiedAt, session.expiresAt, session.lastSeenAt).verify(clock())
            return session.copy(offline = true, lastSeenAt = lease.lastSeenAt).also { save(it) }
        }
        val principal = TeamPrincipal.parse(response.getJSONObject("principal"))
        require(principal.accountId == session.principal.accountId && principal.teamId == session.principal.teamId &&
            principal.memberId == session.principal.memberId) { "团队身份不匹配" }
        return confirm(session.copy(principal = principal, expiresAt = response.getLong("expiresAt"))).also { save(it) }
    }
}
