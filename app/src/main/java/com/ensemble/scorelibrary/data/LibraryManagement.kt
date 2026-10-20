package com.ensemble.scorelibrary.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.PdfEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class MemberPieceKey(val member: String, val piece: String)
data class ManagementCatalog(val data: LibrarySnapshot, val lockedConcerts: Set<String>,
    val allowedParts: Map<MemberPieceKey, List<VisiblePart>>, val scores: Map<MemberPieceKey, DomainResult<ResolvedConcertItem>>)

sealed interface ManagementCommand {
    data class Piece(val value: PieceEntity) : ManagementCommand
    data class RemovePiece(val id: String, val removed: Boolean) : ManagementCommand
    data class Movement(val value: MovementEntity) : ManagementCommand
    data class Definition(val value: PartDefinitionEntity) : ManagementCommand
    data class Part(val value: PiecePartEntity) : ManagementCommand
    data class Binding(val value: PartScoreBindingEntity) : ManagementCommand
    data class Member(val value: MemberEntity) : ManagementCommand
    data class Concert(val value: ConcertEntity) : ManagementCommand
    data class Item(val value: ConcertItemEntity) : ManagementCommand
    data class Pdf(val value: ScorePdfEntity) : ManagementCommand
    data class GlobalGrant(val member: String, val definition: String, val granted: Boolean) : ManagementCommand
    data class ScopedGrant(val member: String, val part: String, val granted: Boolean) : ManagementCommand
    data class Assignment(val member: String, val piece: String, val part: String?) : ManagementCommand
    data class Reorder(val concert: String, val ids: List<String>) : ManagementCommand
}

class LibraryManagement @Inject constructor(@param:ApplicationContext private val context: Context,
    private val database: ScoreLibraryDatabase, private val engine: PdfEngine) {
    private fun admin(data: LibrarySnapshot, actor: String) {
        check(data.members.any { it.id == actor && !it.isArchived && it.role == MemberRole.ADMIN }) { "仅当前管理员可以操作" }
    }
    private fun text(value: String, max: Int, label: String): String {
        require(value.trim().isNotEmpty() && value.trim().length <= max) { "$label 必填且最多 $max 字" }
        return value.trim()
    }
    private suspend fun locks(data: LibrarySnapshot) = data.concerts.mapNotNull {
        it.id.takeIf { id -> database.annotationDao().snapshot(id) != null }
    }.toSet()
    private fun lockedPieces(data: LibrarySnapshot, locks: Set<String>) = data.items.filter {
        it.concertId in locks && !it.isArchived && it.isEnabled
    }.map { it.pieceId }.toSet()
    private fun guardPiece(piece: String, protected: Set<String>) {
        check(piece !in protected) { "曲目已被锁定演出引用，请使用新曲目和新节目单" }
    }
    private fun guardPdf(id: String, data: LibrarySnapshot, protected: Set<String>) {
        val parts = data.parts.filter { it.pieceId in protected && !it.isArchived }.map { it.id }.toSet()
        check(data.bindings.none { it.scorePdfId == id && !it.isArchived && it.piecePartId in parts }) { "PDF 已被锁定演出引用，不能替换或切换版本" }
    }
    suspend fun catalog(actor: String): ManagementCatalog = database.withTransaction {
        val data = database.resolutionDao().snapshot(); admin(data,actor)
        val resolver = ScoreResolver(data)
        val allowed = mutableMapOf<MemberPieceKey,List<VisiblePart>>()
        val scores = mutableMapOf<MemberPieceKey,DomainResult<ResolvedConcertItem>>()
        for (member in data.members.filterNot { it.isArchived }) for (piece in data.pieces.filterNot { it.isArchived }) {
            val key=MemberPieceKey(member.id,piece.id)
            allowed[key]=(resolver.visibleParts(member.id,piece.id) as? DomainResult.Success)?.value.orEmpty()
            scores[key]=resolver.resolvePiece(member.id,piece.id)
        }
        ManagementCatalog(data,locks(data),allowed,scores)
    }
    fun observe(actor: String): Flow<ManagementCatalog> = database.invalidationTracker.createFlow(
        "pieces","movements","part_definitions","piece_parts","score_pdfs","score_pdf_versions","part_score_bindings",
        "members","member_part_permissions","member_piece_part_permissions","piece_part_assignments","concerts","concert_items",
        "score_cache_entries","annotation_concert_snapshots"
    ).map { catalog(actor) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    suspend fun edit(actor: String, command: ManagementCommand, expected: LibrarySnapshot? = null): String = database.withTransaction {
        val data=database.resolutionDao().snapshot(); admin(data,actor)
        check(expected == null || data == expected) { "数据已更新，请重新打开编辑" }
        val lockIds=locks(data); val protected=lockedPieces(data,lockIds); val dao=database.libraryDao()
        fun member(id: String) = requireNotNull(data.members.firstOrNull { it.id==id }) { "成员不存在" }
        fun definition(id: String) = requireNotNull(data.definitions.firstOrNull { it.id==id && !it.isArchived }) { "声部定义不存在或已归档" }
        fun piece(id: String) = requireNotNull(data.pieces.firstOrNull { it.id==id && !it.isArchived }) { "曲目不存在或已归档" }
        fun part(id: String): PiecePartEntity {
            val value=requireNotNull(data.parts.firstOrNull { it.id==id && !it.isArchived }) { "曲目声部不存在或已归档" }
            piece(value.pieceId); definition(value.partDefinitionId); return value
        }
        when(command) {
            is ManagementCommand.RemovePiece -> {
                val row = requireNotNull(data.pieces.firstOrNull { it.id == command.id }) { "曲目不存在" }
                if (command.removed) {
                    guardPiece(row.id, protected)
                    data.items.filter { it.pieceId == row.id && !it.isArchived }.forEach { dao.updateConcertItem(it.copy(isArchived = true)) }
                }
                dao.updatePiece(row.copy(isArchived = command.removed))
                row.id
            }
            is ManagementCommand.Piece -> {
                val v=command.value.copy(title=text(command.value.title,200,"曲目名称"), composer=command.value.composer?.trim()?.ifEmpty{null},
                    arranger=command.value.arranger?.trim()?.ifEmpty{null},notes=command.value.notes?.trim()?.ifEmpty{null})
                require((v.composer?.length ?: 0)<=200 && (v.arranger?.length ?: 0)<=200 && (v.notes?.length ?: 0)<=4000) { "曲目信息过长" }
                val old=data.pieces.firstOrNull{it.id==v.id}
                if(old!=v) guardPiece(v.id,protected)
                if(v.isArchived) check(data.items.none{it.pieceId==v.id && !it.isArchived && it.isEnabled}) { "先停用或移除节目项，再归档曲目" }
                if(old==null) dao.insertPiece(v) else dao.updatePiece(v); v.id
            }
            is ManagementCommand.Movement -> {
                val v=command.value.copy(title=text(command.value.title,200,"乐章名称"))
                piece(v.pieceId); guardPiece(v.pieceId,protected); require(v.sortOrder>=0) { "排序不能为负" }
                val old=data.movements.firstOrNull{it.id==v.id}; check(old==null || old.pieceId==v.pieceId) { "乐章不能转移到其他曲目" }
                check(data.movements.none{it.id!=v.id && it.pieceId==v.pieceId && it.sortOrder==v.sortOrder}) { "乐章排序重复（含归档记录）" }
                if(v.isArchived) check(data.bindings.none{it.movementId==v.id && !it.isArchived}) { "先归档相关谱段，再归档乐章" }
                if(old==null) dao.insertMovement(v) else dao.updateMovement(v); v.id
            }
            is ManagementCommand.Definition -> {
                val v=command.value.copy(name=text(command.value.name,100,"标准声部"),family=command.value.family?.trim()?.ifEmpty{null})
                require(v.sortOrder>=0 && (v.family?.length ?: 0)<=100) { "声部信息无效" }
                check(data.definitions.none{it.id!=v.id && it.name==v.name}) { "声部名称已存在（含归档记录）" }
                data.parts.filter{it.partDefinitionId==v.id && !it.isArchived}.forEach{guardPiece(it.pieceId,protected)}
                if(v.isArchived) check(data.parts.none{it.partDefinitionId==v.id && !it.isArchived} &&
                    data.members.none{it.defaultPartDefinitionId==v.id && !it.isArchived}) { "声部仍被曲目或成员默认声部使用" }
                if(data.definitions.none{it.id==v.id}) dao.insertPartDefinition(v) else dao.updatePartDefinition(v); v.id
            }
            is ManagementCommand.Part -> {
                val v=command.value.copy(displayName=command.value.displayName?.trim()?.ifEmpty{null})
                piece(v.pieceId); definition(v.partDefinitionId); guardPiece(v.pieceId,protected)
                require((v.displayName?.length ?: 0)<=100) { "声部别名最多 100 字" }
                val old=data.parts.firstOrNull{it.id==v.id}
                check(old==null || old.pieceId==v.pieceId && old.partDefinitionId==v.partDefinitionId) { "已有曲目声部的曲目和标准定义不可更换" }
                check(data.parts.none{it.id!=v.id && it.pieceId==v.pieceId && it.partDefinitionId==v.partDefinitionId}) { "该曲目声部已存在，请编辑或恢复原记录" }
                if(v.isArchived) check(data.bindings.none{it.piecePartId==v.id && !it.isArchived} && data.assignments.none {
                    !it.isArchived && it.piecePartId==v.id && data.members.any{m->m.id==it.memberId && !m.isArchived}
                }) { "先处理启用谱段和实际分配，再归档声部" }
                if(old==null) dao.insertPiecePart(v) else dao.updatePiecePart(v); v.id
            }
            is ManagementCommand.Binding -> {
                val v=command.value; val p=if(v.isArchived) requireNotNull(data.parts.firstOrNull{it.id==v.piecePartId}) { "曲目声部不存在" } else part(v.piecePartId)
                guardPiece(p.pieceId,protected)
                val old=data.bindings.firstOrNull{it.id==v.id}; check(old==null || old.piecePartId==v.piecePartId) { "谱段不能转移到其他声部" }
                if(!v.isArchived) {
                    if(v.movementId!=null) check(data.movements.any{it.id==v.movementId && it.pieceId==p.pieceId && !it.isArchived}) { "乐章不属于当前曲目或已归档" }
                    val pdf=requireNotNull(data.pdfs.firstOrNull{it.id==v.scorePdfId && !it.isArchived}) { "PDF 不存在或已归档" }
                    val version=requireNotNull(data.versions.firstOrNull{it.id==pdf.activeVersionId && it.scorePdfId==pdf.id}) { "当前版本无效" }
                    require(v.startPage>=1 && v.endPage in v.startPage..version.pageCount) { "谱段超出当前版本页数" }
                    val other=data.bindings.filter{it.id!=v.id && it.piecePartId==v.piecePartId && !it.isArchived}
                    check(other.none{it.movementId==v.movementId || it.movementId==null || v.movementId==null}) { "整曲/乐章谱段不能混用或重复" }
                }
                if(!v.isArchived || old==null) check(data.bindings.none{it.id!=v.id && it.piecePartId==v.piecePartId && it.movementId==v.movementId}) { "该谱段已有记录，请编辑或恢复原记录" }
                if(old==null) dao.insertPartScoreBinding(v) else dao.updatePartScoreBinding(v); v.id
            }
            is ManagementCommand.Member -> {
                val v=command.value.copy(displayName=text(command.value.displayName,100,"成员姓名"))
                check(data.members.none{it.id!=v.id && it.displayName==v.displayName}) { "成员姓名已存在（含归档记录）" }
                v.defaultPartDefinitionId?.let{definition(it)}
                val old=data.members.firstOrNull{it.id==v.id}
                if(old?.role==MemberRole.ADMIN && !old.isArchived && (v.isArchived || v.role!=MemberRole.ADMIN))
                    check(data.members.any{it.id!=v.id && !it.isArchived && it.role==MemberRole.ADMIN}) { "必须保留至少一位启用管理员" }
                if(old==null) dao.insertMember(v) else dao.updateMember(v); v.id
            }
            is ManagementCommand.Concert -> {
                command.value.audienceMembers?.let { ids -> require(audienceIds(ids).all { id -> data.members.any { it.id == id && !it.isArchived } }) { "可见成员不存在或已归档" } }
                command.value.audienceParts?.let { ids -> require(audienceIds(ids).all { id -> data.definitions.any { it.id == id && !it.isArchived } }) { "可见声部不存在或已归档" } }
                val v=command.value.copy(title=text(command.value.title,200,"节目单名称"))
                if(!v.isArchived) check(data.items.filter{it.concertId==v.id && !it.isArchived && it.isEnabled}.all { item ->
                    data.pieces.any{it.id==item.pieceId && !it.isArchived}
                }) { "节目单含已归档曲目，请先处理节目项" }
                if(data.concerts.none{it.id==v.id}) dao.insertConcert(v) else dao.updateConcert(v); v.id
            }
            is ManagementCommand.Item -> {
                val v=command.value; check(data.concerts.any{it.id==v.concertId && !it.isArchived}) { "节目单不存在或已归档" }
                check(v.concertId !in lockIds) { "锁定节目单不能更改节目项" }
                piece(v.pieceId); val old=data.items.firstOrNull{it.id==v.id}
                check(old==null || old.concertId==v.concertId && old.pieceId==v.pieceId) { "已有节目项不能转移曲目或节目单" }
                require(v.sortOrder>=0) { "节目排序不能为负" }
                check(data.items.none{it.id!=v.id && it.concertId==v.concertId && it.sortOrder==v.sortOrder}) { "节目排序重复（含归档记录）" }
                if(old==null) dao.insertConcertItem(v) else dao.updateConcertItem(v); v.id
            }
            is ManagementCommand.Pdf -> {
                val old=requireNotNull(data.pdfs.firstOrNull{it.id==command.value.id}) { "PDF 不存在" }
                val v=command.value.copy(title=text(command.value.title,200,"PDF 名称"))
                check(v.activeVersionId==old.activeVersionId) { "切换版本必须使用版本校验入口" }
                guardPdf(v.id,data,protected)
                if(v.isArchived) check(data.bindings.none{it.scorePdfId==v.id && !it.isArchived}) { "PDF 仍被启用谱段使用" }
                dao.updateScorePdf(v); v.id
            }
            is ManagementCommand.GlobalGrant -> {
                val m=member(command.member); definition(command.definition)
                check(!command.granted || !m.isArchived) { "不能授权已归档成员" }
                val old=data.permissions.firstOrNull{it.memberId==m.id && it.partDefinitionId==command.definition}
                if(old==null) { if(command.granted) dao.insertPermission(MemberPartPermissionEntity(memberId=m.id,partDefinitionId=command.definition)) }
                else dao.updatePermission(old.copy(isArchived=!command.granted)); m.id
            }
            is ManagementCommand.ScopedGrant -> {
                val m=member(command.member); val p=part(command.part)
                check(!command.granted || !m.isArchived) { "不能授权已归档成员" }
                val old=data.extraPermissions.firstOrNull{it.memberId==m.id && it.piecePartId==p.id}
                if(old==null) { if(command.granted) dao.insertExtraPermission(MemberPiecePartPermissionEntity(memberId=m.id,piecePartId=p.id)) }
                else dao.updateExtraPermission(old.copy(isArchived=!command.granted)); m.id
            }
            is ManagementCommand.Assignment -> {
                val m=member(command.member); check(!m.isArchived) { "成员已归档" }; piece(command.piece)
                if(command.part!=null) {
                    val p=part(command.part); check(p.pieceId==command.piece) { "声部不属于当前曲目" }
                    check(ScoreResolver(data).resolvePart(m.id,command.piece,p.id) is DomainResult.Success) { "声部未获授权或谱段未完整，请先修正授权和谱段" }
                }
                val parts=data.parts.filter{it.pieceId==command.piece}.map{it.id}.toSet()
                for(old in data.assignments.filter{it.memberId==m.id && it.piecePartId in parts && !it.isArchived}) dao.updateAssignment(old.copy(isArchived=true))
                command.part?.let { id ->
                    val old=data.assignments.firstOrNull{it.memberId==m.id && it.piecePartId==id}
                    if(old==null) dao.insertAssignment(PiecePartAssignmentEntity(memberId=m.id,piecePartId=id)) else dao.updateAssignment(old.copy(isArchived=false))
                }
                check(ScoreResolver(database.resolutionDao().snapshot()).resolvePiece(m.id,command.piece) is DomainResult.Success) { "默认声部未获授权或谱段未完整，原分配已保留" }
                m.id
            }
            is ManagementCommand.Reorder -> {
                check(data.concerts.any{it.id==command.concert && !it.isArchived}) { "节目单不存在或已归档" }
                check(command.concert !in lockIds) { "锁定节目单不能重排" }
                val all=data.items.filter{it.concertId==command.concert}; require(all.size<=10_000) { "节目项过多" }
                val active=all.filterNot{it.isArchived}
                require(command.ids.size==active.size && command.ids.toSet().size==active.size && command.ids.toSet()==active.map{it.id}.toSet()) { "排序清单过期或包含其他节目项" }
                val order=command.ids+all.filter{it.isArchived}.sortedBy{it.sortOrder}.map{it.id}
                val used=all.map{it.sortOrder}.toMutableSet(); var temp=Int.MIN_VALUE
                // Unique indexes are immediate; move every row into disjoint slots inside this transaction first.
                for(id in order) { while(temp in used) temp++; dao.updateConcertItem(all.first{it.id==id}.copy(sortOrder=temp)); used+=temp; temp++ }
                order.forEachIndexed{index,id->dao.updateConcertItem(all.first{it.id==id}.copy(sortOrder=index))}; command.concert
            }
        }
    }

    suspend fun importPdf(actor: String, uri: Uri, title: String, pdfId: String? = null, expected: LibrarySnapshot? = null): String {
        catalog(actor); val name=text(title,200,"PDF 名称")
        return ManagedPdfImporter(context).publish(uri) { staged -> database.withTransaction {
            val data=database.resolutionDao().snapshot(); admin(data,actor)
            check(expected==null || expected==data) { "数据已更新，请重新选择 PDF" }
            val old=pdfId?.let { id->requireNotNull(data.pdfs.firstOrNull{it.id==id && !it.isArchived}) { "PDF 不存在或已归档" } }
            if(old!=null) guardPdf(old.id,data,lockedPieces(data,locks(data)))
            val pdf=old ?: ScorePdfEntity(title=name).also{database.libraryDao().insertScorePdf(it)}
            check(data.bindings.filter{it.scorePdfId==pdf.id && !it.isArchived}.all{it.startPage>=1 && it.endPage in it.startPage..staged.pages}) { "新版本不能覆盖现有启用页段" }
            val max=data.versions.filter{it.scorePdfId==pdf.id}.maxOfOrNull{it.versionNumber} ?: 0
            check(max in 0 until Int.MAX_VALUE) { "版本号已达上限" }
            val version=ScorePdfVersionEntity(scorePdfId=pdf.id,versionNumber=max+1,managedFilePath=staged.file.absolutePath,
                originalUri=staged.uri,fileHash=staged.hash,fileSizeBytes=staged.size,pageCount=staged.pages,createdByMemberId=actor)
            database.libraryDao().insertScorePdfVersion(version)
            database.libraryDao().updateScorePdf(pdf.copy(activeVersionId=version.id)); pdf.id
        } }
    }
    suspend fun activateVersion(actor: String, pdfId: String, versionId: String, expected: LibrarySnapshot? = null): String {
        val before=catalog(actor).data
        val pdf=requireNotNull(before.pdfs.firstOrNull{it.id==pdfId && !it.isArchived}) { "PDF 不存在或已归档" }
        val version=requireNotNull(before.versions.firstOrNull{it.id==versionId && it.scorePdfId==pdf.id}) { "版本不属于当前 PDF" }
        guardPdf(pdf.id,before,lockedPieces(before,locks(before)))
        engine.inspect(listOf(ResolvedScoreSegment("version-check",null,pdf.id,version.id,version.managedFilePath,version.fileHash,1,version.pageCount,CacheStatus.NOT_CACHED)))
        return database.withTransaction {
            val data=database.resolutionDao().snapshot(); admin(data,actor)
            check(expected==null || expected==data) { "数据已更新，请重新选择版本" }
            check(data.pdfs.firstOrNull{it.id==pdfId}==pdf && data.versions.firstOrNull{it.id==versionId}==version) { "PDF 或版本已更新" }
            guardPdf(pdf.id,data,lockedPieces(data,locks(data)))
            check(data.bindings.filter{it.scorePdfId==pdf.id && !it.isArchived}.all{it.startPage>=1 && it.endPage in it.startPage..version.pageCount}) { "目标版本不能覆盖现有启用页段" }
            database.libraryDao().updateScorePdf(pdf.copy(activeVersionId=version.id)); pdf.id
        }
    }
}
