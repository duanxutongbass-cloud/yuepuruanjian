package com.ensemble.scorelibrary.data

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.*
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiskCatalogAcceptanceTest {
    @Test fun diskReopenPreservesVersionsMovementsConcertsCachesInkAndRevocation() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "phase9-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, ScoreLibraryDatabase::class.java, name).build()
        var db = open()
        try {
            db.withTransaction {
                val dao = db.libraryDao()
                for (d in 1..2) dao.insertPartDefinition(PartDefinitionEntity(id = "d$d", name = "Part $d"))
                dao.insertMember(MemberEntity(id = "admin", displayName = "Admin", role = MemberRole.ADMIN))
                for (m in 1..6) {
                    dao.insertMember(MemberEntity(id = "m$m", displayName = "Member $m", defaultPartDefinitionId = "d1"))
                    dao.insertPermission(MemberPartPermissionEntity(memberId = "m$m", partDefinitionId = "d1"))
                }
                for (p in 0 until 150) {
                    dao.insertPiece(PieceEntity(id = "p$p", title = "Disk piece $p"))
                    dao.insertScorePdf(ScorePdfEntity(id = "pdf$p", title = "Metadata $p"))
                    for (v in 1..2) dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "v$p-$v", scorePdfId = "pdf$p", versionNumber = v,
                        managedFilePath = "metadata-only-$p-$v", originalUri = "content://metadata/$p/$v", fileHash = "metadata-only", fileSizeBytes = 1, pageCount = 4))
                    dao.updateScorePdf(ScorePdfEntity(id = "pdf$p", title = "Metadata $p", activeVersionId = "v$p-2"))
                    for (d in 1..2) dao.insertPiecePart(PiecePartEntity(id = "part$p-$d", pieceId = "p$p", partDefinitionId = "d$d"))
                    for (movement in 0..1) {
                        dao.insertMovement(MovementEntity(id = "mov$p-$movement", pieceId = "p$p", title = "Movement $movement", sortOrder = movement))
                        dao.insertPartScoreBinding(PartScoreBindingEntity(piecePartId = "part$p-1", movementId = "mov$p-$movement", scorePdfId = "pdf$p", startPage = movement * 2 + 1, endPage = movement * 2 + 2))
                    }
                    dao.insertPartScoreBinding(PartScoreBindingEntity(piecePartId = "part$p-2", scorePdfId = "pdf$p", startPage = 1, endPage = 4))
                    dao.insertCache(ScoreCacheEntryEntity(scorePdfVersionId = "v$p-2", memberId = "m1", status = CacheStatus.CACHED, cachedFilePath = "metadata-only-cache"))
                }
                for (c in 0 until 12) {
                    dao.insertConcert(ConcertEntity(id = "c$c", title = "Concert $c"))
                    for (i in 0 until 10) dao.insertConcertItem(ConcertItemEntity(concertId = "c$c", pieceId = "p${c * 10 + i}", sortOrder = i))
                }
            }
            val scope = AnnotationScope("m1", "p0", "part0-1", "v0-2", 0)
            val ink = listOf(InkMark(kind = MarkKind.PEN, points = listOf(InkPoint(.2f, .3f))))
            AnnotationRepository(db).save(scope, AnnotationLayer.PERSONAL, ink, 0)
            db.close(); db = open()
            val engine = object : PdfEngine {
                override suspend fun inspect(segments: List<ResolvedScoreSegment>): List<PageSize> = error("Metadata fixture has no real PDF")
                override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame = error("Metadata fixture has no real PDF")
            }
            val started = SystemClock.elapsedRealtimeNanos()
            val service = LibraryManagement(context, db, engine)
            val catalog = service.catalog("admin")
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            assertEquals(150, catalog.data.pieces.size); assertEquals(300, catalog.data.versions.size)
            assertEquals(300, catalog.data.movements.size); assertEquals(450, catalog.data.bindings.size)
            assertEquals(150, catalog.data.caches.size); assertEquals(120, catalog.data.items.size)
            assertEquals(ink, AnnotationRepository(db).load(scope).personal)
            val resolver = ScoreResolver(catalog.data)
            val concert = (resolver.resolveConcert("m1", "c0") as DomainResult.Success).value
            assertEquals((0..9).map { "p$it" }, concert.items.map { it.pieceId })
            assertEquals(listOf("mov0-0", "mov0-1"), concert.items.first().segments.map { it.movementId })
            assertTrue(concert.items.flatMap { it.segments }.all { it.versionId.endsWith("-2") && it.cacheStatus == CacheStatus.CACHED })
            service.edit("admin", ManagementCommand.GlobalGrant("m1", "d1", false))
            db.close(); db = open()
            assertEquals(ErrorCode.UNAUTHORIZED, (ScoreResolver(db.resolutionDao().snapshot()).resolvePiece("m1", "p0") as DomainResult.Failure).error.code)
            try { AnnotationRepository(db).load(scope); fail("Revoked ink must not be exposed") } catch (_: IllegalStateException) { }
            val report = JSONObject().put("scope", "Disk Room reopen, metadata-only files; no file rendering/download or process-death simulation")
                .put("pieces", 150).put("versions", 300).put("movements", 300).put("bindings", 450)
                .put("concertItems", 120).put("cacheRows", 150).put("coldCatalogMs", elapsed)
                .put("reopenChecks", "active versions, movement order, personal ink and committed revocation preserved")
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/phase9-disk-catalog.json")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(report.toString(2).toByteArray()) }
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
