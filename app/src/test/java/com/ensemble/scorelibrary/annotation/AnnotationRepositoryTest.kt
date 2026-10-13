package com.ensemble.scorelibrary.annotation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnnotationRepositoryTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ScoreLibraryDatabase::class.java).build()
    private suspend fun setup(db: ScoreLibraryDatabase) {
        val dao = db.libraryDao()
        dao.insertPiece(PieceEntity(id = "p", title = "Piece"))
        dao.insertPartDefinition(PartDefinitionEntity(id = "d", name = "I"))
        dao.insertPiecePart(PiecePartEntity(id = "part", pieceId = "p", partDefinitionId = "d"))
        for ((id, role) in listOf("a" to MemberRole.ADMIN, "m" to MemberRole.MEMBER, "hidden" to MemberRole.MEMBER)) {
            dao.insertMember(MemberEntity(id = id, displayName = id, role = role, defaultPartDefinitionId = "d"))
            if (id != "hidden") dao.insertPermission(MemberPartPermissionEntity(memberId = id, partDefinitionId = "d"))
        }
        dao.insertScorePdf(ScorePdfEntity(id = "pdf", title = "Score"))
        dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "v", scorePdfId = "pdf", versionNumber = 1,
            managedFilePath = "file", originalUri = "content://fixture", fileHash = "hash", fileSizeBytes = 1, pageCount = 4))
        dao.updateScorePdf(ScorePdfEntity(id = "pdf", title = "Score", activeVersionId = "v"))
        dao.insertPartScoreBinding(PartScoreBindingEntity(id = "b", piecePartId = "part", scorePdfId = "pdf", startPage = 2, endPage = 3))
        dao.insertConcert(ConcertEntity(id = "c", title = "Concert"))
        dao.insertConcertItem(ConcertItemEntity(id = "i", concertId = "c", pieceId = "p", sortOrder = 0))
    }
    private fun scope(member: String = "a", page: Int = 1, item: String? = null) = AnnotationScope(member, "p", "part", "v", page, item)
    private fun mark() = InkMark(kind = MarkKind.PEN, points = listOf(InkPoint(.2f, .3f), InkPoint(.8f, .3f)))
    @Test fun reopeningDiskDatabaseKeepsInkAndNewPdfVersionHasSeparateLayer() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ink-reopen.db"; context.deleteDatabase(name)
        var db = Room.databaseBuilder(context, ScoreLibraryDatabase::class.java, name).build()
        try {
            setup(db); val mark = mark()
            AnnotationRepository(db).save(scope(), AnnotationLayer.PERSONAL, listOf(mark), 0)
            db.close(); db = Room.databaseBuilder(context, ScoreLibraryDatabase::class.java, name).build()
            assertEquals(listOf(mark), AnnotationRepository(db).load(scope()).personal)
            val old = db.resolutionDao().versions().single()
            db.libraryDao().insertScorePdfVersion(old.copy(id = "v2", versionNumber = 2))
            db.libraryDao().updateScorePdf(ScorePdfEntity(id = "pdf", title = "Score", activeVersionId = "v2"))
            assertTrue(AnnotationRepository(db).load(scope().copy(version = "v2")).personal.isEmpty())
            try { AnnotationRepository(db).load(scope()); fail("Old version must be rejected") } catch (_: IllegalStateException) { }
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun personalPersistsAndRemainsPrivate() = runBlocking {
        val db = database()
        try { setup(db); val repo = AnnotationRepository(db)
            val mark = mark(); repo.save(scope(), AnnotationLayer.PERSONAL, listOf(mark), 0)
            assertEquals(listOf(mark), AnnotationRepository(db).load(scope()).personal)
            assertTrue(repo.load(scope("m")).personal.isEmpty())
            assertTrue(repo.load(scope(page = 2)).personal.isEmpty())
        } finally { db.close() }
    }
    @Test fun unauthorizedMemberPageAndStaleVersionAreRejected() = runBlocking {
        val db = database()
        try { setup(db); val repo = AnnotationRepository(db)
            for (scope in listOf(scope("hidden"), scope(page = 0), scope().copy(version = "other"))) {
                try { repo.load(scope); fail("Must reject") } catch (_: IllegalStateException) { }
                try { repo.save(scope, AnnotationLayer.PERSONAL, listOf(mark()), 0); fail("Must reject") } catch (_: IllegalStateException) { }
            }
        } finally { db.close() }
    }
    @Test fun publicationIsImmutableAndDraftIsNotLeaked() = runBlocking {
        val db = database()
        try { setup(db); val repo = AnnotationRepository(db); val mark = mark()
            repo.save(scope(), AnnotationLayer.SHARED, listOf(mark), 0)
            assertTrue(repo.load(scope("m")).draft.isEmpty()); assertTrue(repo.load(scope("m")).shared.isEmpty())
            repo.publish(scope())
            val revisionId = db.annotationDao().page("v", 1, "shared")!!.publishedRevisionId!!
            repo.save(scope(), AnnotationLayer.SHARED, emptyList(), 1)
            assertEquals(listOf(mark), repo.load(scope("m")).shared)
            assertEquals(listOf(mark), InkCodec.decode(db.annotationDao().revision(revisionId)!!.payload))
            try { repo.publish(scope("m")); fail("Must reject") } catch (_: IllegalStateException) { }
            try { repo.save(scope("m"), AnnotationLayer.SHARED, emptyList(), 1); fail("Must reject") } catch (_: IllegalStateException) { }
            repo.save(scope(), AnnotationLayer.SHARED, listOf(mark.copy(color = 0xff1565c0)), 2)
            val admin = db.resolutionDao().members().first { it.id == "a" }
            db.libraryDao().updateMember(admin.copy(role = MemberRole.MEMBER))
            assertTrue(repo.load(scope()).draft.isEmpty())
            assertFalse(repo.load(scope()).admin)
        } finally { db.close() }
    }
    @Test fun snapshotFreezesPublishedAndAbsentPagesAndRejectsChanges() = runBlocking {
        val db = database()
        try { setup(db); val repo = AnnotationRepository(db); val mark = mark()
            repo.save(scope(), AnnotationLayer.SHARED, listOf(mark), 0); repo.publish(scope())
            repo.freeze(scope(item = "i"))
            repo.save(scope(), AnnotationLayer.SHARED, emptyList(), 1); repo.publish(scope())
            repo.save(scope(page = 2), AnnotationLayer.SHARED, listOf(mark), 0); repo.publish(scope(page = 2))
            assertEquals(listOf(mark), repo.load(scope("m", item = "i")).shared)
            assertTrue(repo.load(scope("m", page = 2, item = "i")).shared.isEmpty())
            assertTrue(repo.load(scope("m", item = "i")).locked)
            try { repo.freeze(scope(item = "i")); fail("Must not overwrite") } catch (_: IllegalStateException) { }
            db.libraryDao().updateScorePdf(ScorePdfEntity(id = "pdf", title = "Score", activeVersionId = null))
            try { repo.load(scope(item = "i")); fail("Must reject changed version") } catch (_: IllegalStateException) { }
        } finally { db.close() }
    }
    @Test fun optimisticRevisionRejectsLostUpdatesAndRevocationBlocksWrite() = runBlocking {
        val db = database()
        try { setup(db); val repo = AnnotationRepository(db)
            repo.save(scope(), AnnotationLayer.PERSONAL, listOf(mark()), 0)
            try { repo.save(scope(), AnnotationLayer.PERSONAL, emptyList(), 0); fail("Must reject stale generation") } catch (_: IllegalStateException) { }
            val permission = db.resolutionDao().permissions().first { it.memberId == "a" }
            db.libraryDao().updatePermission(permission.copy(isArchived = true))
            try { repo.save(scope(), AnnotationLayer.PERSONAL, emptyList(), 1); fail("Must reject revoked") } catch (_: IllegalStateException) { }
        } finally { db.close() }
    }
    @Test fun frozenConcertDoesNotDependOnLaterMutablePublicationPointer() = runBlocking {
        val db = database()
        try { setup(db); val repo = AnnotationRepository(db); val mark = mark()
            repo.save(scope(), AnnotationLayer.SHARED, listOf(mark), 0); repo.publish(scope())
            repo.freeze(scope(item = "i"))
            repo.publish(scope(page = 2))
            val first = db.annotationDao().page("v",1,"shared")!!
            val other = db.annotationDao().page("v",2,"shared")!!
            db.annotationDao().updatePage(first.copy(publishedRevisionId = other.publishedRevisionId))
            assertEquals(listOf(mark),repo.load(scope("m",item="i")).shared)
            try { repo.load(scope("m")); fail("Unfrozen reading must reject the invalid live pointer") }
            catch (_: IllegalStateException) { }
        } finally { db.close() }
    }
    @Test fun concurrentDifferentPayloadsOnSameBaselineHaveExactlyOneWinner() = runBlocking {
        val db = database()
        try { setup(db); val repo = AnnotationRepository(db)
            val first = listOf(mark()); val second = listOf(mark())
            val outcomes = listOf(first, second).map { marks -> async {
                runCatching { repo.save(scope(), AnnotationLayer.PERSONAL, marks, 0) }
            } }.awaitAll()
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1, outcomes.count { it.isFailure })
            val stored = repo.load(scope())
            assertEquals(1L, stored.personalGeneration)
            assertTrue(stored.personal == first || stored.personal == second)
        } finally { db.close() }
    }
    @Test fun exactReplayIsIdempotentButDifferentPayloadAndLaterGenerationConflict() = runBlocking {
        val db = database()
        try { setup(db); val repo = AnnotationRepository(db); val marks = listOf(mark())
            repo.save(scope(), AnnotationLayer.PERSONAL, marks, 0)
            repeat(3) { repo.save(scope(), AnnotationLayer.PERSONAL, marks, 0) }
            assertEquals(1L, repo.load(scope()).personalGeneration)
            try { repo.save(scope(), AnnotationLayer.PERSONAL, emptyList(), 0); fail("Different payload") }
            catch (_: IllegalStateException) { }
            repo.save(scope(), AnnotationLayer.PERSONAL, emptyList(), 1)
            try { repo.save(scope(), AnnotationLayer.PERSONAL, marks, 0); fail("Later generation") }
            catch (_: IllegalStateException) { }
            assertTrue(repo.load(scope()).personal.isEmpty())
        } finally { db.close() }
    }
    @Test fun replayStillRequiresCurrentPermissionAndSharedRole() = runBlocking {
        val db = database()
        try { setup(db); val repo = AnnotationRepository(db); val marks = listOf(mark())
            repo.save(scope(), AnnotationLayer.PERSONAL, marks, 0)
            repo.save(scope(), AnnotationLayer.SHARED, marks, 0)
            val admin = db.resolutionDao().members().first { it.id == "a" }
            db.libraryDao().updateMember(admin.copy(role = MemberRole.MEMBER))
            try { repo.save(scope(), AnnotationLayer.SHARED, marks, 0); fail("Demoted replay") }
            catch (_: IllegalStateException) { }
            val permission = db.resolutionDao().permissions().first { it.memberId == "a" }
            db.libraryDao().updatePermission(permission.copy(isArchived = true))
            try { repo.save(scope(), AnnotationLayer.PERSONAL, marks, 0); fail("Revoked replay") }
            catch (_: IllegalStateException) { }
            assertEquals(1L, db.annotationDao().page("v", 1, "a")!!.generation)
        } finally { db.close() }
    }
    @Test fun geometrySelectsMovesClampsAndErasesSegmentInteriors() {
        val mark = mark()
        val polygon = listOf(InkPoint(.1f, .1f), InkPoint(.9f, .1f), InkPoint(.9f, .9f), InkPoint(.1f, .9f))
        assertEquals(setOf(mark.id), InkGeometry.selected(listOf(mark), polygon))
        assertTrue(InkGeometry.erase(listOf(mark), listOf(InkPoint(.5f, .3f)), .01f).isEmpty())
        val moved = InkGeometry.move(listOf(mark), setOf(mark.id), 1f, -1f).single()
        assertEquals(1f, moved.points.last().x, .00001f); assertEquals(0f, moved.points.first().y, .00001f)
        assertEquals(listOf(mark), InkCodec.decode(InkCodec.encode(listOf(mark))))
    }
}
