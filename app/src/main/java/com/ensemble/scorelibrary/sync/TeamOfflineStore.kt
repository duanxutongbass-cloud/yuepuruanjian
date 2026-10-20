package com.ensemble.scorelibrary.sync

import android.content.Context
import com.ensemble.scorelibrary.account.*
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

internal data class OfflineCatalog(val catalog: TeamCatalog, val lease: OfflineLease)

/** Private encrypted authorization checkpoint, never a replacement for a live revocation. */
@Singleton
class TeamOfflineStore internal constructor(root: File, private val key: () -> SecretKey,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val root=root.canonicalFile
    @Inject constructor(@ApplicationContext context: Context) : this(context.noBackupFilesDir, { teamSecretKey("team-offline-v1") })
    companion object { private val diskLock = Any() }
    private fun directory(session: TeamSession): File {
        val id = teamId(session.server + "/" + session.principal.teamId + "/" + session.principal.accountId)
        return File(root, "team-libraries/$id").also {
            check(root.canonicalFile == root.absoluteFile && it.canonicalFile == it.absoluteFile)
            check(it.isDirectory || it.mkdirs())
        }
    }

    private fun vault(session: TeamSession): AccountVault {
        return AccountVault(File(directory(session), "authorization.enc"), 5 * 1024 * 1024, key)
    }
    private fun retirement(session: TeamSession) = AccountVault(File(directory(session), "authorization-retired.enc"), 32 * 1024 * 1024, key)
    private data class Retired(val artifacts:Set<String> = emptySet(),val programs:Set<String> = emptySet(),val freezes:Set<String> = emptySet())
    private fun retired(session: TeamSession): Retired {
        val row = retirement(session).read()?.let(::JSONObject) ?: return Retired()
        require(row.keys().asSequence().toSet() == setOf("format", "server", "team", "account", "artifacts", "programs", "freezes"))
        require(row.get("format") == 1 && row.get("server") == session.server && row.get("team") == session.principal.teamId &&
            row.get("account") == session.principal.accountId)
        fun ids(name:String):Set<String> {
        val values = row.getJSONArray(name); require(values.length() <= 500_000)
        return (0 until values.length()).map { values.get(it) as String }.also { ids ->
            require(ids.distinct().size == ids.size && ids.all { java.util.UUID.fromString(it).toString() == it })
        }.toSet()
        }
        return Retired(ids("artifacts"),ids("programs"),ids("freezes"))
    }
    private fun filtered(catalog: TeamCatalog, ids: Retired): TeamCatalog {
        val artifacts = catalog.artifacts.filterNot { it.id in ids.artifacts }
        val keys = artifacts.map { it.pieceKey }.toSet()
        return catalog.copy(artifacts = artifacts, programs = catalog.programs.mapNotNull { p ->
            if (p.freezeId in ids.freezes || p.freezeId != null && p.frozenArtifacts.any { it in ids.artifacts }) null
            else p.copy(pieceKeys = p.pieceKeys.filter { it in keys }).takeIf { it.pieceKeys.isNotEmpty() }
        })
    }
    internal fun retire(session: TeamSession, artifacts: Set<String>, programs:Set<String> = emptySet(), freezes:Set<String> = emptySet(),
        guard: () -> Unit) = synchronized(diskLock) {
        require((artifacts+programs+freezes).all { java.util.UUID.fromString(it).toString() == it }); guard()
        val old=retired(session)
        val ids=Retired(old.artifacts+artifacts,old.programs+programs,old.freezes+freezes)
        require(listOf(ids.artifacts,ids.programs,ids.freezes).all { it.size <= 500_000 })
        retirement(session).write(JSONObject().put("format", 1).put("server", session.server).put("team", session.principal.teamId)
            .put("account", session.principal.accountId).put("artifacts", JSONArray(ids.artifacts.sorted()))
            .put("programs",JSONArray(ids.programs.sorted())).put("freezes",JSONArray(ids.freezes.sorted())).toString())
        val cache = vault(session)
        cache.read()?.let { raw ->
            val data = JSONObject(raw)
            require(data.get("server") == session.server && data.get("account") == session.principal.accountId &&
                data.get("team") == session.principal.teamId && data.get("member") == session.principal.memberId)
            val checkpoint=data.getJSONObject("catalog")
            val cachedPrincipal=TeamPrincipal.parse(checkpoint.getJSONObject("principal"))
            // Cleanup must decode the authenticated checkpoint in its original protocol.
            // A 1 -> 2 login upgrade never upgrades an old checkpoint into a fresh grant.
            require(cachedPrincipal.numberingProtocol==session.principal.numberingProtocol ||
                cachedPrincipal.numberingProtocol==1 && session.principal.numberingProtocol==2) { "离线缓存编号协议不能回退" }
            val checkpointSession=session.copy(principal=session.principal.copy(numberingProtocol=cachedPrincipal.numberingProtocol))
            val before = TeamCatalog.parse(checkpoint, checkpointSession) // Still checks current account/team/member.
            guard(); cache.write(data.put("catalog", filtered(before, ids).json(session.copy(principal=cachedPrincipal))).toString())
        }
    }
    private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun invalidate(session: TeamSession) = synchronized(diskLock) { vault(session).clear() }
    internal fun save(session: TeamSession, catalog: TeamCatalog) = synchronized(diskLock) {
        check(!session.offline && session.verifiedAt > 0 && session.deviceId.isNotEmpty())
        val now = clock()
        val lease = OfflineLease(now, session.expiresAt, now).verify(now)
        val data = JSONObject().put("format", 1).put("server", session.server).put("account", session.principal.accountId)
            .put("team", session.principal.teamId).put("member", session.principal.memberId).put("device", session.deviceId)
            .put("credentialHash", digest(session.token)).put("verifiedAt", lease.verifiedAt).put("expiresAt", lease.expiresAt)
            .put("lastSeenAt", now).put("catalog", filtered(catalog, retired(session)).json(session))
        vault(session).write(data.toString())
    }
    internal fun read(session: TeamSession): OfflineCatalog = synchronized(diskLock) {
        if (session.verifiedAt <= 0 || session.deviceId.isEmpty()) throw OfflineUnavailable()
        val vault = vault(session)
        try {
            val data = vault.read()?.let(::JSONObject) ?: throw OfflineUnavailable("此账号尚无已核验的离线曲库，请联网同步一次")
            require(data.getInt("format") == 1 && data.getString("server") == session.server &&
                data.getString("account") == session.principal.accountId && data.getString("team") == session.principal.teamId &&
                data.getString("member") == session.principal.memberId && data.getString("device") == session.deviceId &&
                data.getString("credentialHash") == digest(session.token))
            val lease = OfflineLease(data.getLong("verifiedAt"), minOf(data.getLong("expiresAt"), session.expiresAt),
                maxOf(data.getLong("lastSeenAt"), session.lastSeenAt)).verify(clock())
            val catalog = filtered(TeamCatalog.parse(data.getJSONObject("catalog"), session), retired(session))
            // Persist the clock high-water mark even when no new content has arrived.
            vault.write(data.put("lastSeenAt", lease.lastSeenAt).put("catalog", catalog.json(session)).toString())
            OfflineCatalog(catalog, lease)
        } catch (error: OfflineUnavailable) { throw error }
        catch (error: Exception) {
            vault.clear()
            throw OfflineUnavailable("本机离线授权无法核验，请联网重新同步")
        }
    }
}

internal fun TeamCatalog.json(session: TeamSession): JSONObject = JSONObject().put("principal", session.principal.json())
    .put("protocolVersion",session.principal.numberingProtocol)
    .put("revision", revision).put("artifacts", JSONArray().apply { artifacts.forEach { a -> put(JSONObject()
        .put("artifactId", a.id).put("title", a.title).put("partName", a.partName).put("hash", a.hash).put("size", a.size)
        .put("pageCount", a.pages).put("scoreNumber", a.number).put("category", a.category).put("independentSource", true)
        .also { row -> a.pieceGeneration?.let { row.put("pieceGeneration",it) } }
        .also { row -> if(session.principal.numberingProtocol==2) {
            val number=com.ensemble.scorelibrary.domain.ScoreNumber.optional(a.number)
            row.put("sequence",number?.sequence ?: JSONObject.NULL).put("namingVersion",number?.namingVersion ?: JSONObject.NULL)
        } }) } })
    .put("programs", JSONArray().apply { programs.forEach { p -> put(JSONObject().put("id", p.id).put("title", p.title)
        .put("pieceKeys", JSONArray(p.pieceKeys)).apply { if(p.freezeId!=null) {
            put("freezeId",p.freezeId);put("frozenArtifactIds",JSONArray(p.frozenArtifacts))
        } }) } })
