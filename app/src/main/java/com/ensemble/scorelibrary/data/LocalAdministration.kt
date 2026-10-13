package com.ensemble.scorelibrary.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ImportScoreRequest(
    val title: String, val partName: String, val memberId: String,
    val startPage: Int = 1, val endPage: Int? = null, val concertTitle: String = "",
    val grantAdministrator: Boolean = false,
)

class LocalAdministration @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: ScoreLibraryDatabase,
) {
    private suspend fun requireAdmin(id: String) {
        check(database.resolutionDao().members().any {
            it.id == id && !it.isArchived && it.role == MemberRole.ADMIN
        }) { "仅当前管理员可以操作" }
    }

    suspend fun createMember(actorId: String?, name: String): String = database.withTransaction {
        require(name.trim().isNotEmpty() && name.trim().length <= 100) { "请输入有效成员姓名（最多 100 字）" }
        val members = database.resolutionDao().members()
        if (members.isNotEmpty()) requireAdmin(requireNotNull(actorId) { "请先选择管理员" })
        val member = MemberEntity(displayName = name.trim(),
            role = if (members.isEmpty()) MemberRole.ADMIN else MemberRole.MEMBER)
        check(members.none { it.displayName == member.displayName }) { "成员姓名已存在" }
        database.libraryDao().insertMember(member)
        member.id
    }

    suspend fun importScore(actorId: String, uri: Uri, request: ImportScoreRequest): String = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "请通过系统文件选择器选择 PDF" }
        require(request.title.trim().isNotEmpty() && request.title.trim().length <= 200) { "请输入曲目名称（最多 200 字）" }
        require(request.partName.trim().isNotEmpty() && request.partName.trim().length <= 100) { "请输入声部名称（最多 100 字）" }
        require(request.concertTitle.trim().length <= 200) { "节目单名称最多 200 字" }
        requireAdmin(actorId)
        ManagedPdfImporter(context).publish(uri) { staged ->
            val pageCount = staged.pages
            val end = request.endPage ?: pageCount
            require(request.startPage in 1..pageCount && end in request.startPage..pageCount) { "页段超出 PDF 范围（共 $pageCount 页）" }
            database.withTransaction {
                requireAdmin(actorId)
                check(database.resolutionDao().members().any { it.id == request.memberId && !it.isArchived }) { "授权成员不存在或已归档" }
                val dao = database.libraryDao()
                val definition = database.resolutionDao().definitions().firstOrNull { it.name == request.partName.trim() }
                    ?.also { check(!it.isArchived) { "该声部已归档，请使用其他名称" } }
                    ?: PartDefinitionEntity(name = request.partName.trim()).also { dao.insertPartDefinition(it) }
                val piece = PieceEntity(title = request.title.trim())
                dao.insertPiece(piece)
                val part = PiecePartEntity(pieceId = piece.id, partDefinitionId = definition.id)
                dao.insertPiecePart(part)
                val pdf = ScorePdfEntity(title = piece.title)
                dao.insertScorePdf(pdf)
                val version = ScorePdfVersionEntity(scorePdfId = pdf.id, versionNumber = 1,
                    managedFilePath = staged.file.absolutePath, originalUri = staged.uri, fileHash = staged.hash,
                    fileSizeBytes = staged.size, pageCount = pageCount, createdByMemberId = actorId)
                dao.insertScorePdfVersion(version)
                dao.updateScorePdf(pdf.copy(activeVersionId = version.id))
                dao.insertPartScoreBinding(PartScoreBindingEntity(piecePartId = part.id, scorePdfId = pdf.id,
                    startPage = request.startPage, endPage = end))
                val recipients = (listOf(request.memberId) + if (request.grantAdministrator) listOf(actorId) else emptyList()).distinct()
                for (recipient in recipients) {
                    dao.insertExtraPermission(MemberPiecePartPermissionEntity(memberId = recipient, piecePartId = part.id))
                    dao.insertAssignment(PiecePartAssignmentEntity(memberId = recipient, piecePartId = part.id))
                }
                if (request.concertTitle.isNotBlank()) {
                    val concert = database.resolutionDao().concerts().firstOrNull {
                        !it.isArchived && it.title == request.concertTitle.trim()
                    } ?: ConcertEntity(title = request.concertTitle.trim()).also { dao.insertConcert(it) }
                    check(database.annotationDao().snapshot(concert.id) == null) { "节目单批注已锁定，请使用新的节目单名称" }
                    val order = database.resolutionDao().items().filter { it.concertId == concert.id }.maxOfOrNull { it.sortOrder } ?: -1
                    check(order < Int.MAX_VALUE) { "节目单过长" }
                    dao.insertConcertItem(ConcertItemEntity(concertId = concert.id, pieceId = piece.id, sortOrder = order + 1))
                }
                piece.id
            }
        }
    }
}
