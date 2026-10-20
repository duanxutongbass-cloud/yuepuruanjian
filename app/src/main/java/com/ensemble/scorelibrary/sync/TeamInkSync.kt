package com.ensemble.scorelibrary.sync

import androidx.room.withTransaction
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.entity.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class InkCloudStatus(val key: String = "", val busy: Boolean = false, val pending: Int = 0,
    val conflicts: Int = 0, val message: String = "", val completedAt: Long = 0)
data class InkCloudChange(val key: String, val version: String, val page: Int)
data class InkConflict(val local: List<InkMark>, val remote: List<InkMark>, val remoteRevision: Long,
    val localGeneration: Long, val kind: String)
data class InkBackupScore(val artifact: String, val hash: String, val title: String, val pages: Int)
internal data class InkTarget(val artifact: String, val hash: String, val version: String, val page: Int, val kind: String) {
    val id get() = "$version:$page:$kind"
    val path get() = "/v1/annotations/$artifact/$page/$kind"
}

/** Local ink is durable before transport. CAS never silently replaces a dirty local page. */
@Singleton
class TeamInkSync @Inject constructor(private val scope: TeamLibraryScope, private val client: TeamInkTransport) {
    private val mutex = Mutex()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = kotlinx.coroutines.channels.Channel<TeamCollection>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val editing = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    internal var automatic = true
    private val mutable = MutableStateFlow(InkCloudStatus())
    val status = mutable.asStateFlow()
    private val changes = MutableSharedFlow<InkCloudChange>(extraBufferCapacity = 64)
    val events = changes.asSharedFlow()
    val active = scope.active
    init {
        background.launch { for (collection in requests) {
            delay(750)
            try { sync(collection) } catch (_: Exception) { /* persisted outbox is retried on resume/manual sync */ }
        } }
    }
    fun schedule(collection: TeamCollection? = scope.active.value) { if (automatic && collection != null) requests.trySend(collection) }
    fun current() = scope.active.value
    fun hold(page: AnnotationScope, layer: AnnotationLayer): String? {
        val c = current()?.takeIf { it.member == page.member } ?: return null
        return "${c.key}/${page.version}/${page.page}/${if (layer == AnnotationLayer.PERSONAL) "personal" else "draft"}".also { editing[it] = true }
    }
    fun release(key: String?) { if (key != null) { editing.remove(key); schedule() } }
    private fun held(c: TeamCollection, t: InkTarget) = editing.containsKey("${c.key}/${t.version}/${t.page}/${t.kind}")
    private fun session(c: TeamCollection): TeamSession {
        scope.requireCurrent(c)
        return checkNotNull(c.session).also { if (it.offline) throw OfflineUnavailable("离线笔迹已保存在本机，联网核验后再同步") }
    }
    private fun owner(c: TeamCollection, kind: String) = if (kind == "personal") c.member else "shared"
    private fun record(t: InkTarget) = AnnotationCloudEntity(t.id, t.version, t.page, t.kind)
    private fun notify(c: TeamCollection, t: InkTarget) { changes.tryEmit(InkCloudChange(c.key, t.version, t.page)) }
    private suspend fun versions(c: TeamCollection): Map<String, ScorePdfVersionEntity> {
        scope.requireCurrent(c)
        val d = c.database.resolutionDao().snapshot()
        val parts = d.extraPermissions.filter { it.memberId == c.member && !it.isArchived }.map { it.piecePartId }.toSet()
        val pdfs = d.bindings.filter { !it.isArchived && it.piecePartId in parts && d.parts.any { p -> p.id == it.piecePartId && !p.isArchived } }.map { it.scorePdfId }.toSet()
        return d.versions.filter { v -> v.scorePdfId in pdfs && d.pdfs.any { p -> !p.isArchived && p.id == v.scorePdfId && p.activeVersionId == v.id } &&
            v.originalUri.startsWith("team-artifact:") }.associateBy { it.id }
    }
    private fun target(v: ScorePdfVersionEntity, page: Int, kind: String): InkTarget {
        val artifact = v.originalUri.removePrefix("team-artifact:")
        require(UUID.fromString(artifact).toString() == artifact && v.id == teamId("version/$artifact") && page in 0 until v.pageCount)
        require(v.fileHash.matches(Regex("[a-f0-9]{64}")) && kind in setOf("personal", "draft", "published"))
        return InkTarget(artifact, v.fileHash, v.id, page, kind)
    }
    private fun marks(json: JSONObject): String = InkCodec.encode(InkCodec.decode(json.getJSONArray("marks").toString()))
    private fun revision(json: JSONObject): Long = json.getLong("revision").also { require(it in 0 until 9_007_199_254_740_991L) }
    private fun validateHead(c: TeamCollection, t: InkTarget, json: JSONObject) {
        require(json.getString("accountId") == session(c).principal.accountId && json.getString("artifactId") == t.artifact &&
            json.getString("artifactHash") == t.hash && json.getInt("pageIndex") == t.page && json.getString("kind") == t.kind)
    }

    suspend fun sync(c: TeamCollection = checkNotNull(current())): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        scope.requireCurrent(c)
        mutable.value = InkCloudStatus(c.key, busy = true, message = "正在同步批注")
        try {
            val s = session(c)
            val allowed = versions(c)
            val manifest = client.request(s, "/v1/annotations").body
            scope.requireCurrent(c)
            require(manifest.getInt("protocolVersion") == 1 && manifest.getString("accountId") == s.principal.accountId &&
                manifest.getString("teamId") == s.principal.teamId) { "批注身份校验失败" }
            val tasks = linkedMapOf<String, InkTarget>()
            val remote = manifest.getJSONArray("pages"); require(remote.length() <= 10000)
            repeat(remote.length()) { i ->
                val r = remote.getJSONObject(i); val artifact = r.getString("artifactId")
                val v = allowed[teamId("version/$artifact")] ?: return@repeat
                require(v.fileHash == r.getString("artifactHash")); revision(r)
                val t = target(v, r.getInt("pageIndex"), r.getString("kind"))
                require(t.kind != "personal" || s.principal.backupConsent)
                require(t.kind != "draft" || s.principal.role == "admin")
                tasks[t.id] = t
            }
            val dao = c.database.annotationDao()
            dao.pages().forEach { p ->
                val v = allowed[p.versionId] ?: return@forEach
                val kind = when { p.ownerKey == c.member && s.principal.backupConsent -> "personal"
                    p.ownerKey == "shared" && s.principal.role == "admin" -> "draft"; else -> return@forEach }
                target(v, p.pageIndex, kind).let { tasks[it.id] = it }
            }
            dao.cloudPages().forEach { p ->
                val v = allowed[p.versionId] ?: return@forEach
                if (p.kind == "personal" && !s.principal.backupConsent || p.kind != "personal" && p.kind != "published" && s.principal.role != "admin") return@forEach
                if (p.kind == "published" && p.requestId != null && s.principal.role != "admin") return@forEach
                target(v, p.pageIndex, p.kind).let { tasks[it.id] = it }
            }
            for (t in tasks.values) { ensureActive(); scope.requireCurrent(c); synchronize(c, t) }
            updateStatus(c, if (s.principal.backupConsent) "批注同步完成" else "共享批注已刷新；个人备份需先确认说明", System.currentTimeMillis())
        } catch (error: Exception) {
            if (current() === c) updateStatus(c, if (error is AccountRejected && error.status == 401) "批注登录已失效，请重新登录"
                else if(error is AccountRejected && error.code=="pressure_data_required")accountError(error)
                else "批注同步未完成，已保存笔迹保留在本机；可稍后重试")
            throw error
        }
    } }
    private suspend fun updateStatus(c: TeamCollection, message: String, at: Long = 0) {
        val dao = c.database.annotationDao(); val records = dao.cloudPages().associateBy { it.id }
        val pending = dao.pages().count { p ->
            val kind = if (p.ownerKey == c.member) "personal" else "draft"
            val state = records["${p.versionId}:${p.pageIndex}:$kind"]
            p.generation != (state?.syncedGeneration ?: 0L)
        } + records.values.count { it.kind == "published" && it.requestId != null }
        scope.requireCurrent(c)
        val conflicts = records.values.count { it.conflictPayload != null }
        mutable.value = InkCloudStatus(c.key, pending = pending, conflicts = conflicts,
            message = if (conflicts > 0) "有 $conflicts 页批注冲突，双方已保留，请打开对应谱页处理" else message, completedAt = at)
    }
    private suspend fun synchronize(c: TeamCollection, t: InkTarget, forceWrite: Boolean = false) {
        val dao = c.database.annotationDao()
        var force = forceWrite
        repeat(3) {
            scope.requireCurrent(c)
            var state = dao.cloud(t.id) ?: record(t)
            if (state.conflictPayload != null) {
                val latest = client.request(session(c), t.path).body
                validateHead(c, t, latest)
                val payload = marks(latest); val rev = revision(latest)
                require(rev >= state.conflictRevision)
                if (rev != state.conflictRevision || payload != state.conflictPayload) {
                    c.database.withTransaction { scope.requireCurrent(c); dao.putCloud(state.copy(conflictPayload = payload, conflictRevision = rev)) }
                    notify(c, t)
                }
                return
            }
            if (t.kind != "published" && state.requestId == null) c.database.withTransaction {
                scope.requireCurrent(c)
                state = dao.cloud(t.id) ?: record(t)
                val page = dao.page(t.version, t.page, owner(c, t.kind)) ?: if (force)
                    AnnotationPageEntity(versionId = t.version, pageIndex = t.page, ownerKey = owner(c, t.kind)).also { dao.insertPage(it) } else null
                if (page != null && (force || page.generation != state.syncedGeneration)) {
                    // A generation also distinguishes an intentionally emptied first local page.
                    val id = UUID.randomUUID().toString()
                    val body = JSONObject().put("protocolVersion", 1).put("requestId", id).put("baseRevision", state.remoteRevision)
                        .put("artifactHash", t.hash).put("marks", JSONArray(page.payload))
                    state = state.copy(requestId = id, requestBody = body.toString(), requestGeneration = page.generation)
                    dao.putCloud(state)
                    force = false
                }
            }
            if (state.requestId != null) {
                val reply = client.request(session(c), t.path, JSONObject(checkNotNull(state.requestBody)))
                scope.requireCurrent(c)
                require(reply.body.getString("requestId") == state.requestId)
                if (reply.status == 409) {
                    val remote = reply.body.getJSONObject("current")
                    val payload = marks(remote); val rev = revision(remote)
                    require(rev >= state.remoteRevision)
                    c.database.withTransaction {
                        scope.requireCurrent(c)
                        check(dao.cloud(t.id)?.requestId == state.requestId)
                        dao.putCloud(state.copy(conflictPayload = payload, conflictRevision = rev))
                    }
                    notify(c, t); return
                }
                val payload = marks(reply.body); val rev = revision(reply.body)
                require(reply.status == 200 && rev == state.remoteRevision + 1)
                require(InkCodec.decode(payload) == InkCodec.decode(JSONObject(checkNotNull(state.requestBody)).getJSONArray("marks").toString()))
                c.database.withTransaction {
                    scope.requireCurrent(c)
                    check(dao.cloud(t.id)?.requestId == state.requestId)
                    val next = state.copy(remoteRevision = rev, baseline = payload, syncedGeneration = state.requestGeneration,
                        requestId = null, requestBody = null)
                    dao.putCloud(next)
                    if (t.kind == "published") applyPublished(c, t, payload, rev)
                }
                notify(c, t)
                return@repeat
            }
            if (t.kind != "published" && held(c, t)) return
            val reply = client.request(session(c), t.path).body
            scope.requireCurrent(c)
            validateHead(c, t, reply)
            val payload = marks(reply); val rev = revision(reply)
            require(rev >= state.remoteRevision && (rev != state.remoteRevision || payload == state.baseline ||
                InkCodec.pressureEnrichment(state.baseline,payload))) { "批注服务修订不一致，双方数据已保留" }
            var changed = false
            c.database.withTransaction {
                scope.requireCurrent(c)
                val latest = dao.cloud(t.id) ?: record(t)
                val page = dao.page(t.version, t.page, owner(c, t.kind))
                if (latest.requestId != null || latest.conflictPayload != null) return@withTransaction
                if (t.kind == "published") { changed = applyPublished(c, t, payload, rev); dao.putCloud(latest.copy(remoteRevision = rev, baseline = payload)) }
                else if (!held(c, t) && (page?.generation ?: 0L) == latest.syncedGeneration) {
                    var generation = page?.generation ?: 0L
                    if ((page?.payload ?: "[]") != payload) {
                        check(generation < Long.MAX_VALUE); generation++
                        if (page == null) dao.insertPage(AnnotationPageEntity(versionId = t.version, pageIndex = t.page, ownerKey = owner(c, t.kind), payload = payload, generation = generation))
                        else dao.updatePage(page.copy(payload = payload, generation = generation))
                        changed = true
                    }
                    dao.putCloud(latest.copy(remoteRevision = rev, baseline = payload, syncedGeneration = generation))
                }
            }
            if (changed) notify(c, t)
            return
        }
    }
    private suspend fun applyPublished(c: TeamCollection, t: InkTarget, payload: String, rev: Long): Boolean {
        if (rev == 0L) return false
        val dao = c.database.annotationDao()
        val page = dao.page(t.version, t.page, "shared") ?: AnnotationPageEntity(versionId = t.version, pageIndex = t.page, ownerKey = "shared").also { dao.insertPage(it) }
        val id = teamId("${c.key}/published/${t.artifact}/${t.page}/$rev")
        if (dao.revision(id) == null) dao.insertRevision(AnnotationRevisionEntity(id = id, documentId = page.id, payload = payload, createdBy = "team-server"))
        else check(dao.revision(id)?.payload == payload)
        if (page.publishedRevisionId == id) return false
        dao.updatePage(page.copy(publishedRevisionId = id)); return true
    }
    suspend fun conflict(c: TeamCollection, version: String, page: Int, kind: String): InkConflict? {
        scope.requireCurrent(c)
        val dao = c.database.annotationDao(); val state = dao.cloud("$version:$page:$kind") ?: return null
        val remote = state.conflictPayload ?: return null
        val local = dao.page(version, page, owner(c, kind))
        return InkConflict(InkCodec.decode(local?.payload ?: "[]"), InkCodec.decode(remote), state.conflictRevision, local?.generation ?: 0, kind)
    }
    suspend fun resolve(c: TeamCollection, version: String, page: Int, conflict: InkConflict, keepLocal: Boolean): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val v = versions(c)[version] ?: error("此谱的授权已失效，批注仍保留")
        val t = target(v, page, conflict.kind); val dao = c.database.annotationDao()
        if (!keepLocal) {
            val latest = client.request(session(c), t.path).body
            validateHead(c, t, latest)
            require(revision(latest) == conflict.remoteRevision && InkCodec.decode(marks(latest)) == conflict.remote) { "云端批注已再次变化，请重新同步并比较" }
        }
        c.database.withTransaction {
            scope.requireCurrent(c)
            val state = checkNotNull(dao.cloud(t.id)); val local = dao.page(version, page, owner(c, t.kind))
            check(state.conflictRevision == conflict.remoteRevision && state.conflictPayload != null && (local?.generation ?: 0L) == conflict.localGeneration) { "本页已变化，请重新比较" }
            local?.let { dao.insertRevision(AnnotationRevisionEntity(documentId = it.id, payload = it.payload, createdBy = c.member)) }
            val remote = InkCodec.encode(conflict.remote)
            if (t.kind == "published") {
                if (!keepLocal) applyPublished(c, t, remote, conflict.remoteRevision)
                dao.putCloud(state.copy(remoteRevision = conflict.remoteRevision, baseline = remote, requestId = null, requestBody = null, conflictPayload = null))
            } else if (keepLocal) {
                dao.putCloud(state.copy(remoteRevision = conflict.remoteRevision, baseline = remote, requestId = null, requestBody = null, conflictPayload = null,
                    syncedGeneration = -1))
            } else {
                check(conflict.localGeneration < Long.MAX_VALUE)
                val generation = conflict.localGeneration + 1
                if (local == null) dao.insertPage(AnnotationPageEntity(versionId = version, pageIndex = page, ownerKey = owner(c, t.kind), payload = remote, generation = generation))
                else dao.updatePage(local.copy(payload = remote, generation = generation))
                dao.putCloud(state.copy(remoteRevision = conflict.remoteRevision, baseline = remote, syncedGeneration = generation,
                    requestId = null, requestBody = null, conflictPayload = null))
            }
        }
        notify(c, t); updateStatus(c, "已记录选择，另一份笔迹仍在历史记录中")
    }; if (conflict.kind == "published" && keepLocal) publish(c, version, page) else schedule(c) }
    suspend fun publish(c: TeamCollection, version: String, page: Int): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        check(session(c).principal.role == "admin")
        val v = versions(c)[version] ?: error("没有此乐谱的权限")
        val draft = target(v, page, "draft"); synchronize(c, draft, forceWrite = true)
        val dao = c.database.annotationDao(); val state = dao.cloud(draft.id) ?: record(draft)
        check(state.requestId == null && state.conflictPayload == null) { "共享草稿尚未同步或存在冲突，请先处理" }
        val local = dao.page(version, page, "shared")
        check((local?.generation ?: 0L) == state.syncedGeneration) { "共享草稿仍在保存，请稍后发布" }
        val t = target(v, page, "published")
        var published = dao.cloud(t.id) ?: record(t)
        if (published.requestId == null) {
            synchronize(c, t); published = dao.cloud(t.id) ?: record(t)
            val id = UUID.randomUUID().toString()
            val body = JSONObject().put("protocolVersion", 1).put("requestId", id).put("artifactHash", t.hash)
                .put("baseRevision", published.remoteRevision).put("draftRevision", state.remoteRevision)
                .put("marks", JSONArray(local?.payload ?: "[]"))
            c.database.withTransaction { scope.requireCurrent(c); dao.putCloud(published.copy(requestId = id, requestBody = body.toString())) }
        }
        synchronize(c, t)
        check(dao.cloud(t.id)?.conflictPayload == null) { "其他管理员已发布新版本，请刷新后确认" }
        updateStatus(c, "共享批注已发布")
    } }
    suspend fun requestForCurrent(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val c = checkNotNull(current()); val result = client.request(session(c), path, body).body
        scope.requireCurrent(c); result
    }
    suspend fun readableScores(c: TeamCollection): List<InkBackupScore> = withContext(Dispatchers.IO) {
        val d = c.database.resolutionDao().snapshot()
        versions(c).values.map { v ->
            val binding = d.bindings.firstOrNull { it.scorePdfId == v.scorePdfId && !it.isArchived }
            val part = d.parts.find { it.id == binding?.piecePartId }
            val piece = d.pieces.find { it.id == part?.pieceId }
            InkBackupScore(v.originalUri.removePrefix("team-artifact:"), v.fileHash,
                "${piece?.organizerSequence.orEmpty()} ${piece?.title.orEmpty()} · ${part?.displayName.orEmpty()}", v.pageCount)
        }.sortedBy { it.title }
    }
    suspend fun readMemberBackup(c: TeamCollection, account: String, score: InkBackupScore, page: Int): List<InkMark> = withContext(Dispatchers.IO) {
        check(session(c).principal.role == "admin"); require(page in 0 until score.pages)
        val result = client.request(session(c), "/v1/admin/annotation-backups", JSONObject()
            .put("accountId", account).put("artifactId", score.artifact).put("pageIndex", page)).body
        scope.requireCurrent(c)
        require(result.getString("accountId") == account && result.getString("artifactId") == score.artifact &&
            result.getString("artifactHash") == score.hash && result.getInt("pageIndex") == page)
        revision(result)
        InkCodec.decode(marks(result))
    }
    suspend fun backupSegment(c: TeamCollection, score: InkBackupScore): com.ensemble.scorelibrary.domain.ResolvedScoreSegment {
        val version = versions(c).values.singleOrNull { it.originalUri == "team-artifact:${score.artifact}" && it.fileHash == score.hash }
            ?: error("乐谱授权已变更，请重新同步")
        return com.ensemble.scorelibrary.domain.ResolvedScoreSegment("team-backup:${score.artifact}", null, version.scorePdfId,
            version.id, version.managedFilePath, version.fileHash, 1, version.pageCount, com.ensemble.scorelibrary.core.model.CacheStatus.CACHED)
    }
    suspend fun personalBackup(c: TeamCollection): String = withContext(Dispatchers.IO) {
        val s = session(c)
        val manifest = client.request(s, "/v1/annotation-backups").body
        val pages = manifest.getJSONArray("pages"); require(pages.length() <= 10000)
        val result = JSONObject().put("format", "team-personal-ink").put("formatVersion", 1)
            .put("accountId", s.principal.accountId).put("teamId", s.principal.teamId)
        val output = JSONArray(); var size = 0L
        repeat(pages.length()) { i ->
            scope.requireCurrent(c); ensureActive()
            val row = pages.getJSONObject(i); val artifact = row.getString("artifactId")
            require(UUID.fromString(artifact).toString() == artifact && row.getString("artifactHash").matches(Regex("[a-f0-9]{64}")))
            val page = row.getInt("pageIndex"); require(page in 0..9999)
            val data = client.request(s, "/v1/annotation-backups/$artifact/$page").body
            require(revision(data) == revision(row)) { "备份期间批注发生变化，请重试" }
            val payload = marks(data); size += payload.toByteArray(Charsets.UTF_8).size
            require(size <= 32 * 1024 * 1024) { "个人批注备份超过 32 MB，请联系维护人员分批取回" }
            output.put(JSONObject().put("artifactId", artifact).put("artifactHash", row.getString("artifactHash"))
                .put("pageIndex", page).put("revision", revision(data)).put("title", row.getString("title"))
                .put("partName", row.getString("partName")).put("marks", JSONArray(payload)))
        }
        require(client.request(s, "/v1/annotation-backups").body.toString() == manifest.toString()) { "备份期间批注发生变化，请重试" }
        scope.requireCurrent(c)
        val saved = JSONArray()
        c.database.withTransaction {
            scope.requireCurrent(c)
            val versions = c.database.resolutionDao().versions().associateBy { it.id }
            for (p in c.database.annotationDao().pages().filter { it.ownerKey == c.member }) {
                val v = versions[p.versionId] ?: continue
                val payload = InkCodec.encode(InkCodec.decode(p.payload)); size += payload.toByteArray(Charsets.UTF_8).size
                require(size <= 32 * 1024 * 1024) { "个人批注备份超过 32 MB" }
                saved.put(JSONObject().put("versionId", p.versionId).put("artifactId", v.originalUri.removePrefix("team-artifact:"))
                    .put("artifactHash", v.fileHash).put("pageIndex", p.pageIndex).put("generation", p.generation)
                    .put("marks", JSONArray(payload)))
            }
        }
        result.put("pages", output).put("localSavedPages", saved).toString()
    }
}
