package com.ensemble.scorelibrary.data

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogScaleAcceptanceTest {
    @Test fun sevenMembersAnd250PiecesKeepDomainProjectionAndRevocationCorrect() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        try {
            db.withTransaction {
                val dao = db.libraryDao()
                for (d in 1..2) dao.insertPartDefinition(PartDefinitionEntity(id = "d$d", name = "Part $d"))
                dao.insertMember(MemberEntity(id = "admin", displayName = "Admin", role = MemberRole.ADMIN))
                for (m in 1..6) {
                    dao.insertMember(MemberEntity(id = "m$m", displayName = "Member $m", defaultPartDefinitionId = "d1"))
                    dao.insertPermission(MemberPartPermissionEntity(memberId = "m$m", partDefinitionId = "d1"))
                }
                dao.insertScorePdf(ScorePdfEntity(id = "pdf", title = "Metadata fixture"))
                dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "v", scorePdfId = "pdf", versionNumber = 1,
                    managedFilePath = "not-rendered", originalUri = "content://metadata-fixture", fileHash = "not-rendered", fileSizeBytes = 1, pageCount = 4))
                dao.updateScorePdf(ScorePdfEntity(id = "pdf", title = "Metadata fixture", activeVersionId = "v"))
                for (p in 0 until 250) {
                    dao.insertPiece(PieceEntity(id = "p$p", title = "Piece $p"))
                    for (d in 1..2) {
                        dao.insertPiecePart(PiecePartEntity(id = "part-$p-$d", pieceId = "p$p", partDefinitionId = "d$d"))
                        dao.insertPartScoreBinding(PartScoreBindingEntity(piecePartId = "part-$p-$d", scorePdfId = "pdf", startPage = d, endPage = d))
                    }
                }
                dao.insertExtraPermission(MemberPiecePartPermissionEntity(memberId = "m6", piecePartId = "part-0-2"))
                dao.insertAssignment(PiecePartAssignmentEntity(memberId = "m6", piecePartId = "part-0-2"))
            }
            val engine = object : PdfEngine {
                override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> = error("Metadata fixture must not render")
                override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame = error("Metadata fixture must not render")
            }
            val service = LibraryManagement(context, db, engine)
            val timings = JSONArray()
            repeat(5) {
                val started = SystemClock.elapsedRealtimeNanos()
                val catalog = service.catalog("admin")
                timings.put((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                assertEquals(1750, catalog.scores.size)
                assertTrue(catalog.allowedParts.filterKeys { key -> key.member == "admin" }.values.all { parts -> parts.isEmpty() })
                assertEquals("part-0-2", (catalog.scores[MemberPieceKey("m6", "p0")] as DomainResult.Success).value.part.id)
                assertEquals("part-249-1", (catalog.scores[MemberPieceKey("m1", "p249")] as DomainResult.Success).value.part.id)
                assertTrue(catalog.allowedParts[MemberPieceKey("m6", "p1")]!!.none { part -> part.definitionId == "d2" })
            }
            service.edit("admin", ManagementCommand.ScopedGrant("m6", "part-0-2", false))
            val after = service.catalog("admin")
            assertEquals(ErrorCode.UNAUTHORIZED, (after.scores[MemberPieceKey("m6", "p0")] as DomainResult.Failure).error.code)
            assertTrue(after.data.assignments.any { it.memberId == "m6" && !it.isArchived })
            val report = JSONObject().put("scope", "Real Room metadata catalog on emulator, not UI latency or physical/NAS performance")
                .put("members", 7).put("pieces", 250).put("partsAndBindings", 500).put("projectionPairs", 1750)
                .put("catalogMs", timings).put("revocation", "Explicit assignment retained; unauthorized, no default fallback")
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/phase9-catalog-scale.json")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(report.toString(2).toByteArray()) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { db.close() }
    }
}
