package com.ensemble.scorelibrary.annotation

import androidx.room.withTransaction
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.domain.*
import org.json.JSONObject
import javax.inject.Inject

data class AnnotationScope(val member: String, val piece: String, val part: String,
    val version: String, val page: Int, val item: String? = null)
data class AnnotationData(val personal: List<InkMark>, val draft: List<InkMark>, val shared: List<InkMark>,
    val personalGeneration: Long, val sharedGeneration: Long, val admin: Boolean,
    val concert: String?, val locked: Boolean,
    val personalConflict: com.ensemble.scorelibrary.sync.InkConflict? = null,
    val sharedConflict: com.ensemble.scorelibrary.sync.InkConflict? = null,
    val recoverySource: RecoverySource? = null)

interface AnnotationStore {
    suspend fun load(scope: AnnotationScope): AnnotationData
    suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long)
    suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long, source:RecoverySource?) =
        save(scope,layer,marks,generation)
    suspend fun publish(scope: AnnotationScope)
    suspend fun freeze(scope: AnnotationScope)
}

class AnnotationRepository @Inject constructor(private val localDatabase: ScoreLibraryDatabase,
    private val team: com.ensemble.scorelibrary.sync.TeamLibraryScope? = null,
    private val ink: com.ensemble.scorelibrary.sync.TeamInkSync? = null) : AnnotationStore {
    private fun databaseFor(scope: AnnotationScope) = team?.databaseFor(scope.member, localDatabase) ?: localDatabase
    private val teamDatabases=mutableSetOf<ScoreLibraryDatabase>()
    @Synchronized private fun space(scope:AnnotationScope,database:ScoreLibraryDatabase):String {
        val collection=team?.active?.value
        // Some callers deliberately supply the selected team DB as the constructor fallback.
        // The active collection defines its identity, regardless of that alias.
        if(collection!=null) {
            team!!.requireCurrent(collection)
            check(collection.database===database && collection.member==scope.member) { "团队账号已切换" }
            teamDatabases.add(database)
            return "team:${collection.key}"
        }
        check(database===localDatabase && database !in teamDatabases) { "团队账号已退出，请重新登录后打开曲谱" }
        return "local"
    }
    private fun recoverySource(scope:AnnotationScope,database:ScoreLibraryDatabase,data:LibrarySnapshot)=
        RecoverySource(space(scope,database),checkNotNull(data.versions.firstOrNull { it.id==scope.version }).fileHash)
    private suspend fun authorized(scope: AnnotationScope, database: ScoreLibraryDatabase): Pair<LibrarySnapshot, String?> {
        space(scope,database)
        val original = database.resolutionDao().snapshot()
        val concert = scope.item?.let { item -> original.items.firstOrNull { it.id == item && !it.isArchived && it.isEnabled }?.concertId }
        val collection=team?.active?.value
        val frozen=concert?.let { database.annotationDao().snapshot(it) }
        val data=if(collection!=null) team!!.project(collection,original,frozen) else original
        val resolver = ScoreResolver(data)
        check(scope.item == null || concert != null) { "演出曲目已失效" }
        val result = if (concert == null) resolver.resolvePart(scope.member, scope.piece, scope.part)
        else when (val resolved = resolver.resolveConcert(scope.member, concert)) {
            is DomainResult.Failure -> resolved
            is DomainResult.Success -> resolved.value.items.firstOrNull { it.itemId == scope.item && it.part.id == scope.part && it.pieceId == scope.piece }
                ?.let { DomainResult.Success(it) } ?: DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED, ""))
        }
        check(result is DomainResult.Success) { "当前成员没有此曲谱的批注权限，或曲谱配置已失效" }
        check(result.value.segments.any { it.versionId == scope.version && scope.page + 1 in it.startPage..it.endPage }) { "批注版本或页段已失效，请重新打开曲谱" }
        if (concert != null) database.annotationDao().snapshot(concert)?.let {
            if(collection!=null) {
                if(com.ensemble.scorelibrary.sync.TeamProgramSnapshot.isTeam(it))
                    com.ensemble.scorelibrary.sync.TeamProgramSnapshot.validate(it,data,collection.member)
            } else check(it.manifest == manifest(data, concert)) { "演出锁定后曲谱或节目单已变更，请重新安排演出" }
        }
        return data to concert
    }
    private fun isAdmin(data: LibrarySnapshot, member: String): Boolean {
        val c = team?.active?.value
        if (c != null) { team.requireCurrent(c); return member == c.member && c.session?.principal?.role == "admin" }
        return data.members.any { it.id == member && !it.isArchived && it.role == MemberRole.ADMIN }
    }
    private fun key(version: String, page: Int) = "$version:$page"
    companion object {
    internal fun manifest(data: LibrarySnapshot, concert: String): String {
        val pieces = data.items.filter { it.concertId == concert && !it.isArchived && it.isEnabled }.map { it.pieceId }.toSet()
        val parts = data.parts.filter { it.pieceId in pieces && !it.isArchived }.map { it.id }.toSet()
        return data.bindings.filter { it.piecePartId in parts && !it.isArchived }.sortedBy { it.id }.joinToString("|") { binding ->
            val pdf = data.pdfs.firstOrNull { it.id == binding.scorePdfId && !it.isArchived }
            val version = data.versions.firstOrNull { it.id == pdf?.activeVersionId && it.scorePdfId == pdf?.id }
            check(version != null && binding.startPage >= 1 && binding.endPage in binding.startPage..version.pageCount) { "演出曲谱版本或页段无效" }
            "${binding.id}:${binding.piecePartId}:${binding.movementId}:${version.id}:${version.fileHash}:${version.pageCount}:${binding.startPage}:${binding.endPage}"
        } + "#" + data.items.filter { it.concertId == concert && !it.isArchived && it.isEnabled }.sortedBy { it.sortOrder }
            .joinToString("|") { "${it.id}:${it.pieceId}:${it.sortOrder}" }
    }
    }
    override suspend fun load(scope: AnnotationScope): AnnotationData = databaseFor(scope).let { database -> database.withTransaction {
        val (data, concert) = authorized(scope, database)
        val dao = database.annotationDao()
        val personal = dao.page(scope.version, scope.page, scope.member)
        val shared = dao.page(scope.version, scope.page, "shared")
        val snapshot = concert?.let { dao.snapshot(it) }?.takeIf {
            team?.active?.value==null || com.ensemble.scorelibrary.sync.TeamProgramSnapshot.isTeam(it)
        }
        if (snapshot != null && team?.active?.value==null) check(snapshot.manifest == manifest(data, concert)) { "演出锁定后曲谱或节目单已变更，请重新安排演出" }
        val published = if (snapshot != null) "[]" else shared?.publishedRevisionId?.let { id ->
            val revision = dao.revision(id)
            check(revision != null && revision.documentId == shared.id) { "共享批注发布记录无效" }
            revision.payload
        } ?: "[]"
        val visible = if (snapshot == null) published else JSONObject(snapshot.payload).optString(key(scope.version, scope.page), "[]")
        val admin = isAdmin(data, scope.member)
        val collection = team?.active?.value
        if (collection != null) ink?.schedule(collection)
        AnnotationData(InkCodec.decode(personal?.payload ?: "[]"),
            if (admin && concert == null) InkCodec.decode(shared?.payload ?: "[]") else emptyList(),
            InkCodec.decode(visible), personal?.generation ?: 0, shared?.generation ?: 0, admin, concert, snapshot != null,
            collection?.let { ink?.conflict(it, scope.version, scope.page, "personal") },
            if(admin && concert==null)collection?.let { ink?.conflict(it, scope.version, scope.page, "draft") ?: ink?.conflict(it, scope.version, scope.page, "published") } else null,
            recoverySource(scope,database,data))
    } }
    override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long) = save(scope,layer,marks,generation,null)
    override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long, source:RecoverySource?): Unit = databaseFor(scope).let { database -> database.withTransaction {
        val (data, concert) = authorized(scope, database)
        if(source!=null)check(source==recoverySource(scope,database,data)) { "原乐谱或账号已变化，未保存笔迹保持隔离；请重新打开" }
        if (layer == AnnotationLayer.SHARED) check(isAdmin(data, scope.member) && concert == null) { "共享批注仅管理员可在曲库编辑" }
        val payload = InkCodec.encode(marks)
        check(payload.length <= 8_000_000) { "本页批注过多，请精简后重试" }
        val dao = database.annotationDao()
        val owner = if (layer == AnnotationLayer.PERSONAL) scope.member else "shared"
        val page = dao.page(scope.version, scope.page, owner)
        // A committed write may lose its acknowledgement on cancellation. Only the exact
        // next-generation payload is idempotent; all other stale baselines remain conflicts.
        if (generation < Long.MAX_VALUE && page?.generation == generation + 1 && page.payload == payload) return@withTransaction
        check((page?.generation ?: 0) == generation) { "批注已被其他操作修改，请刷新后再编辑" }
        check(generation < Long.MAX_VALUE)
        if (page == null) dao.insertPage(AnnotationPageEntity(versionId = scope.version, pageIndex = scope.page, ownerKey = owner,
            payload = payload, generation = generation + 1))
        else dao.updatePage(page.copy(payload = payload, generation = generation + 1))
        team?.active?.value?.let { ink?.schedule(it) }
    } }
    override suspend fun publish(scope: AnnotationScope) {
        val collection = team?.active?.value
        if (collection != null) {
            val db = databaseFor(scope)
            val (data, concert) = authorized(scope, db)
            check(isAdmin(data, scope.member) && concert == null)
            checkNotNull(ink).publish(collection, scope.version, scope.page)
            return
        }
        databaseFor(scope).let { database -> database.withTransaction {
        val (data, concert) = authorized(scope, database)
        check(isAdmin(data, scope.member) && concert == null) { "共享发布仅管理员可在曲库操作" }
        val dao = database.annotationDao()
        val page = dao.page(scope.version, scope.page, "shared")
            ?: AnnotationPageEntity(versionId = scope.version, pageIndex = scope.page, ownerKey = "shared").also { dao.insertPage(it) }
        InkCodec.decode(page.payload)
        val revision = AnnotationRevisionEntity(documentId = page.id, payload = page.payload, createdBy = scope.member)
        dao.insertRevision(revision)
        dao.updatePage(page.copy(publishedRevisionId = revision.id))
    } } }
    override suspend fun freeze(scope: AnnotationScope): Unit = databaseFor(scope).let { database -> database.withTransaction {
        check(team?.active?.value==null) { "团队演出请在「管理节目单」冻结到 NAS，再同步本场版本" }
        val (data, concert) = authorized(scope, database)
        check(isAdmin(data, scope.member) && concert != null) { "仅管理员可锁定演出批注" }
        val dao = database.annotationDao()
        check(dao.snapshot(concert) == null) { "演出批注已锁定" }
        val payload = JSONObject()
        val pieceIds = data.items.filter { it.concertId == concert && !it.isArchived && it.isEnabled }.map { it.pieceId }.toSet()
        val partIds = data.parts.filter { it.pieceId in pieceIds && !it.isArchived }.map { it.id }.toSet()
        val pdfIds = data.bindings.filter { it.piecePartId in partIds && !it.isArchived }.map { it.scorePdfId }.toSet()
        val versionIds = data.pdfs.filter { it.id in pdfIds && !it.isArchived }.mapNotNull { it.activeVersionId }.toSet()
        var size = 0L
        for (chunk in versionIds.toList().chunked(500)) for (page in dao.sharedPages(chunk)) {
            page.publishedRevisionId?.let { id ->
                val revision = dao.revision(id)
                check(revision != null && revision.documentId == page.id)
                size += revision.payload.length
                check(size <= 8_000_000) { "演出共享批注过多，无法安全锁定" }
                InkCodec.decode(revision.payload)
                payload.put(key(page.versionId, page.pageIndex), revision.payload)
            }
        }
        check(payload.toString().length <= 16_000_000) { "演出共享批注过多，无法安全锁定" }
        dao.insertSnapshot(AnnotationConcertSnapshotEntity(concertId = concert, payload = payload.toString(),
            manifest = manifest(data, concert), createdBy = scope.member))
    } }
}
