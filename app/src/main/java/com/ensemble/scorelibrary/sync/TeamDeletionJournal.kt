package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.AccountVault
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKey

internal data class TeamDeletionCheckpoint(val storage: String? = null, val cursor: Long = 0,
    val retired: Set<String> = emptySet(), val pending: TeamDeletionPage? = null, val lastAck: String? = null,
    val pieces: Set<String> = emptySet(), val programs: Set<String> = emptySet(), val freezes: Set<String> = emptySet())

/** Persist verified authority before erasing; advance only after all managed copies are clean.
 * Shared by accounts of the same server/team; TeamDeletionCoordinator owns cleanup and acknowledgement.
 */
internal class TeamDeletionJournal(root: File, val server: String, val team: String,
    private val key: () -> SecretKey) {
    private val root=root.canonicalFile
    companion object {
        private val diskLock = Any()
        private const val MAXIMUM = 32 * 1024 * 1024
    }
    init {
        require(server.isNotBlank() && server.length <= 2048 && server.none(Char::isISOControl))
        uuid(team)
    }
    private fun uuid(value: String): String = value.also { require(UUID.fromString(it).toString() == it) }
    private fun fields(row: JSONObject, expected: Set<String>) = require(row.keys().asSequence().toSet() == expected)
    private fun vault(): AccountVault {
        val directory = File(root, "team-deletions/${teamId("$server/$team")}")
        check(root.canonicalFile == root.absoluteFile && directory.canonicalFile == directory.absoluteFile)
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "retirement.enc")
        check(file.canonicalFile == file.absoluteFile)
        return AccountVault(file, MAXIMUM, key)
    }
    private fun json(page: TeamDeletionPage): JSONObject = JSONObject().put("protocolVersion", 1)
        .put("storageId", page.storage).put("teamId", page.team).put("accountId", page.account)
        .put("after", page.after.toString()).put("through", page.through.toString()).put("nextAfter", page.next.toString())
        .put("more", page.more).put("deletions", JSONArray().apply { page.deletions.forEach { d ->
            put(JSONObject().put("sequence", d.sequence.toString()).put("artifactId", d.artifact).put("requestId", d.request).put("deletedAt", d.deletedAt))
        } }).put("jobs", JSONArray().apply { page.impacts.forEach { j ->
            put(JSONObject().put("requestId", j.request).put("programIds", JSONArray(j.programs.sorted())).put("freezeIds", JSONArray(j.freezes.sorted()))
                .put("pieceReferences", JSONArray(j.pieces.sorted())))
        } })
    private fun digest(page: TeamDeletionPage) = MessageDigest.getInstance("SHA-256").digest(json(page).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun readNow(): TeamDeletionCheckpoint {
        val raw = vault().read() ?: return TeamDeletionCheckpoint()
        val row = JSONObject(raw)
        fields(row, setOf("format", "server", "team", "storage", "cursor", "retired", "pending", "lastAck", "pieces", "programs", "freezes"))
        require(row.get("format") == 2 && row.get("server") == server && row.get("team") == team)
        val storage = if (row.isNull("storage")) null else uuid(row.get("storage") as String)
        val value = row.get("cursor");require(value is String && value.matches(Regex("0|[1-9][0-9]{0,18}")))
        val cursor = value.toLong().also { require(it >= 0 && (storage != null || it == 0L)) }
        val ids = row.getJSONArray("retired");require(ids.length() <= 500_000)
        val retired = (0 until ids.length()).map { uuid(ids.get(it) as String) }.also { require(it.distinct().size == it.size) }.toSet()
        val pending = if (row.isNull("pending")) null else row.getJSONObject("pending").let {
            val account = uuid(it.get("accountId") as String)
            TeamDeletionPage.parse(it, server, team, account, storage, cursor)
        }
        require(pending == null || pending.deletions.all { it.artifact in retired })
        val ack = if (row.isNull("lastAck")) null else (row.get("lastAck") as String).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
        fun ids(name: String): Set<String> {
            val array = row.getJSONArray(name);require(array.length() <= 500_000)
            return (0 until array.length()).map { uuid(array.get(it) as String) }
                .also { require(it.distinct().size == it.size) }.toSet()
        }
        val pieces=ids("pieces");val programs=ids("programs");val freezes=ids("freezes")
        require(pending == null || pending.impacts.all { pieces.containsAll(it.pieces) && programs.containsAll(it.programs) && freezes.containsAll(it.freezes) })
        return TeamDeletionCheckpoint(storage, cursor, retired, pending, ack, pieces, programs, freezes)
    }
    private fun save(state: TeamDeletionCheckpoint) {
        require(listOf(state.retired,state.pieces,state.programs,state.freezes).all { it.size <= 500_000 })
        vault().write(JSONObject().put("format", 2).put("server", server).put("team", team)
            .put("storage", state.storage ?: JSONObject.NULL).put("cursor", state.cursor.toString())
            .put("retired", JSONArray(state.retired.sorted())).put("pending", state.pending?.let(::json) ?: JSONObject.NULL)
            .put("pieces", JSONArray(state.pieces.sorted())).put("programs", JSONArray(state.programs.sorted())).put("freezes", JSONArray(state.freezes.sorted()))
            .put("lastAck", state.lastAck ?: JSONObject.NULL).toString())
    }
    fun read(): TeamDeletionCheckpoint = synchronized(diskLock) { readNow() }
    fun stage(page: TeamDeletionPage, account: String, allowRebind: Boolean = false): TeamDeletionCheckpoint = synchronized(diskLock) {
        require(page.server == server) { "删除通知来源服务器不匹配" }
        val old = readNow()
        if (old.pending != null) {
            require(old.pending == page && page.account == account && page.team == team) { "上一批删除尚未清理完成" }
            return@synchronized old
        }
        val rebound = old.storage != null && old.storage != page.storage
        require(!rebound || allowRebind && page.after == 0L) { "删除存储身份变化，需重新联网核验" }
        val cursor = if (rebound) 0 else old.cursor
        val verified = TeamDeletionPage.parse(json(page), server, team, uuid(account), if (rebound) null else old.storage, cursor)
        val next = old.copy(storage = verified.storage, cursor = cursor, retired = old.retired + verified.deletions.map { it.artifact },
            pending = verified, lastAck = if (rebound) null else old.lastAck,
            pieces = old.pieces + verified.impacts.flatMap { it.pieces }, programs = old.programs + verified.impacts.flatMap { it.programs },
            freezes = old.freezes + verified.impacts.flatMap { it.freezes })
        save(next);next
    }
    /** Call only after Room, managed PDFs, drafts, frozen scenes and offline vaults finish cleanup. */
    fun acknowledge(page: TeamDeletionPage): TeamDeletionCheckpoint = synchronized(diskLock) {
        val old = readNow()
        require(page.server == server && page.team == team && page.storage == old.storage)
        if (old.pending == null) {
            require(old.cursor == page.next && old.lastAck == digest(page)) { "删除确认与已保存批次不一致" }
            return@synchronized old
        }
        require(old.pending == page && page.after == old.cursor) { "删除确认与待清理批次不一致" }
        old.copy(cursor = page.next, pending = null, lastAck = digest(page)).also(::save)
    }
}
