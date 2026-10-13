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
    val concert: String?, val locked: Boolean)

interface AnnotationStore {
    suspend fun load(scope: AnnotationScope): AnnotationData
    suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long)
    suspend fun publish(scope: AnnotationScope)
    suspend fun freeze(scope: AnnotationScope)
}

class AnnotationRepository @Inject constructor(private val database: ScoreLibraryDatabase) : AnnotationStore {
    private suspend fun authorized(scope: AnnotationScope): Pair<LibrarySnapshot, String?> {
        val data = database.resolutionDao().snapshot()
        val resolver = ScoreResolver(data)
        val concert = scope.item?.let { item -> data.items.firstOrNull { it.id == item && !it.isArchived && it.isEnabled }?.concertId }
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
            check(it.manifest == manifest(data, concert)) { "演出锁定后曲谱或节目单已变更，请重新安排演出" }
        }
        return data to concert
    }
    private fun isAdmin(data: LibrarySnapshot, member: String) = data.members.any { it.id == member && !it.isArchived && it.role == MemberRole.ADMIN }
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
    override suspend fun load(scope: AnnotationScope): AnnotationData = database.withTransaction {
        val (data, concert) = authorized(scope)
        val dao = database.annotationDao()
        val personal = dao.page(scope.version, scope.page, scope.member)
        val shared = dao.page(scope.version, scope.page, "shared")
        val snapshot = concert?.let { dao.snapshot(it) }
        if (snapshot != null) check(snapshot.manifest == manifest(data, concert)) { "演出锁定后曲谱或节目单已变更，请重新安排演出" }
        val published = if (snapshot != null) "[]" else shared?.publishedRevisionId?.let { id ->
            val revision = dao.revision(id)
            check(revision != null && revision.documentId == shared.id) { "共享批注发布记录无效" }
            revision.payload
        } ?: "[]"
        val visible = if (snapshot == null) published else JSONObject(snapshot.payload).optString(key(scope.version, scope.page), "[]")
        val admin = isAdmin(data, scope.member)
        AnnotationData(InkCodec.decode(personal?.payload ?: "[]"),
            if (admin && concert == null) InkCodec.decode(shared?.payload ?: "[]") else emptyList(),
            InkCodec.decode(visible), personal?.generation ?: 0, shared?.generation ?: 0, admin, concert, snapshot != null)
    }
    override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long): Unit = database.withTransaction {
        val (data, concert) = authorized(scope)
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
    }
    override suspend fun publish(scope: AnnotationScope): Unit = database.withTransaction {
        val (data, concert) = authorized(scope)
        check(isAdmin(data, scope.member) && concert == null) { "共享发布仅管理员可在曲库操作" }
        val dao = database.annotationDao()
        val page = dao.page(scope.version, scope.page, "shared")
            ?: AnnotationPageEntity(versionId = scope.version, pageIndex = scope.page, ownerKey = "shared").also { dao.insertPage(it) }
        InkCodec.decode(page.payload)
        val revision = AnnotationRevisionEntity(documentId = page.id, payload = page.payload, createdBy = scope.member)
        dao.insertRevision(revision)
        dao.updatePage(page.copy(publishedRevisionId = revision.id))
    }
    override suspend fun freeze(scope: AnnotationScope): Unit = database.withTransaction {
        val (data, concert) = authorized(scope)
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
    }
}
