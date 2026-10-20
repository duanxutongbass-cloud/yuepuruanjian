package com.ensemble.scorelibrary.backup

import com.ensemble.scorelibrary.domain.ScoreNumber

import com.ensemble.scorelibrary.annotation.AnnotationRepository
import com.ensemble.scorelibrary.annotation.InkCodec
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.model.MemberRole
import org.json.JSONObject

internal suspend fun validateLibrary(db: ScoreLibraryDatabase) {
    val data = db.resolutionDao().snapshot()
    for (concert in data.concerts) {
        require(com.ensemble.scorelibrary.domain.audienceIds(concert.audienceMembers).all { id -> data.members.any { it.id == id } }) { "节目单可见成员无效" }
        require(com.ensemble.scorelibrary.domain.audienceIds(concert.audienceParts).all { id -> data.definitions.any { it.id == id } }) { "节目单可见声部无效" }
    }
    for (piece in data.pieces) {
        require((piece.categoryCode == null && piece.categoryName == null) ||
            (piece.categoryCode?.matches(Regex("[0-9]{2}")) == true && !piece.categoryName.isNullOrBlank() && piece.categoryName.length <= 100)) { "曲目分类无效" }
        require(piece.organizerSequence == null || runCatching { ScoreNumber.sequence(piece.organizerSequence) }.isSuccess) { "整理器曲目编号无效" }
    }
    require(data.members.none { it.id == "shared" }) { "成员身份不能占用共享批注保留标识" }
    require(data.members.any { !it.isArchived && it.role == MemberRole.ADMIN }) { "母库没有有效管理员" }
    for (pdf in data.pdfs) if (pdf.activeVersionId != null)
        require(data.versions.any { it.id == pdf.activeVersionId && it.scorePdfId == pdf.id }) { "当前版本归属错误" }
    for (version in data.versions) {
        require(version.versionNumber > 0 && version.pageCount in 1..10_000)
        require(version.fileSizeBytes in 1..ArchiveFormat.PDF_LIMIT)
        require(version.fileHash.matches(Regex("[a-fA-F0-9]{64}")))
    }
    val bindings = data.bindings.filterNot { it.isArchived }
    require(bindings.groupBy { it.piecePartId to it.movementId }.values.all { it.size == 1 }) { "谱段绑定重复" }
    require(bindings.groupBy { it.piecePartId }.values.none { group -> group.size > 1 && group.any { it.movementId == null } })
    for (binding in data.bindings) {
        val part = data.parts.single { it.id == binding.piecePartId }
        binding.movementId?.let { id -> require(data.movements.single { it.id == id }.pieceId == part.pieceId) }
        require(binding.startPage > 0 && binding.endPage >= binding.startPage)
        if (!binding.isArchived) {
            val pdf = data.pdfs.single { it.id == binding.scorePdfId }
            val version = data.versions.singleOrNull { it.id == pdf.activeVersionId }
            require(version != null && binding.endPage <= version.pageCount) { "谱段页码无效" }
        }
    }
    require(data.assignments.filterNot { it.isArchived }.groupBy { assignment ->
        assignment.memberId to data.parts.single { it.id == assignment.piecePartId }.pieceId
    }.values.all { it.size == 1 })
    val sql = db.openHelper.writableDatabase
    for (page in ArchiveFormat.rows(ArchiveFormat.read(sql, "annotation_pages"))) {
        val version = data.versions.single { it.id == page.getString("versionId") }
        require(page.getInt("pageIndex") in 0 until version.pageCount && page.getLong("generation") >= 0)
        val owner = page.getString("ownerKey")
        require(owner == "shared" || data.members.any { it.id == owner })
        ArchiveFormat.depthCheck(page.getString("payload")); InkCodec.decode(page.getString("payload"))
        if (owner != "shared") require(page.isNull("publishedRevisionId"))
        if (!page.isNull("publishedRevisionId")) {
            val revision = db.annotationDao().revision(page.getString("publishedRevisionId"))
            require(revision != null && revision.documentId == page.getString("id")) { "共享发布引用无效" }
        }
    }
    for (revision in ArchiveFormat.rows(ArchiveFormat.read(sql, "annotation_revisions"))) {
        require(data.members.any { it.id == revision.getString("createdBy") })
        val document = ArchiveFormat.rows(ArchiveFormat.read(sql, "annotation_pages", "WHERE id = ?",
            arrayOf(revision.getString("documentId")))).single()
        require(document.getString("ownerKey") == "shared")
        ArchiveFormat.depthCheck(revision.getString("payload")); InkCodec.decode(revision.getString("payload"))
    }
    for (snapshot in ArchiveFormat.rows(ArchiveFormat.read(sql, "annotation_concert_snapshots"))) {
        require(data.members.any { it.id == snapshot.getString("createdBy") })
        require(snapshot.getString("manifest") == AnnotationRepository.manifest(data, snapshot.getString("concertId"))) {
            "演出快照与谱库不一致"
        }
        ArchiveFormat.depthCheck(snapshot.getString("payload"))
        val payload = JSONObject(snapshot.getString("payload"))
        for (key in ArchiveFormat.keys(payload)) {
            val page = key.substringAfterLast(':').toInt()
            val version = data.versions.single { it.id == key.substringBeforeLast(':') }
            require(page in 0 until version.pageCount)
            require(payload.get(key) is String)
            ArchiveFormat.depthCheck(payload.getString(key)); InkCodec.decode(payload.getString(key))
        }
    }
}
