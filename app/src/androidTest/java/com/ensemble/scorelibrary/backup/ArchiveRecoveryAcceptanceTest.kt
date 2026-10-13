package com.ensemble.scorelibrary.backup

import android.content.Context
import android.os.ParcelFileDescriptor
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.MainActivity
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.ui.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArchiveRecoveryAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owned = mutableListOf<File>()
    private val stage = InstrumentationRegistry.getArguments().getString("archiveStage")
    private fun open() = if (stage != null) Room.databaseBuilder(context, ScoreLibraryDatabase::class.java, "ensemble-score-library.db")
        .addMigrations(ScoreLibraryDatabase.MIGRATION_1_2, ScoreLibraryDatabase.MIGRATION_2_3).build()
        else Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
    private fun service(db: ScoreLibraryDatabase) = AssetArchiveService(context, db, ArchivePdfVerifier())
    private fun file(name: String) = File(context.cacheDir, "${UUID.randomUUID()}-$name").also { owned += it }
    private suspend fun seed(db: ScoreLibraryDatabase) {
        val dao = db.libraryDao()
        dao.insertPartDefinition(PartDefinitionEntity(id = "recovery-definition", name = "Recovery I"))
        for (id in listOf("recovery-admin", "recovery-member", "recovery-other")) {
            dao.insertMember(MemberEntity(id = id, displayName = id, defaultPartDefinitionId = "recovery-definition",
                role = if (id == "recovery-admin") MemberRole.ADMIN else MemberRole.MEMBER))
            dao.insertPermission(MemberPartPermissionEntity(memberId = id, partDefinitionId = "recovery-definition"))
        }
        dao.insertPiece(PieceEntity(id = "recovery-piece", title = "Recovery score"))
        dao.insertPiece(PieceEntity(id = "recovery-archived", title = "Archived score", isArchived = true))
        dao.insertPiecePart(PiecePartEntity(id = "recovery-part", pieceId = "recovery-piece", partDefinitionId = "recovery-definition"))
        dao.insertMovement(MovementEntity(id = "recovery-movement", pieceId = "recovery-piece", title = "Movement", sortOrder = 0))
        dao.insertScorePdf(ScorePdfEntity(id = "recovery-pdf", title = "Versions"))
        for (number in 1..2) {
            val pdf = File(context.filesDir, "recovery-${UUID.randomUUID()}.pdf").also { owned += it }
            val hash = PdfFixture.write(pdf)
            dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "recovery-v$number", scorePdfId = "recovery-pdf", versionNumber = number,
                managedFilePath = pdf.path, originalUri = "content://recovery/$number", fileHash = hash, fileSizeBytes = pdf.length(), pageCount = 4))
        }
        dao.updateScorePdf(ScorePdfEntity(id = "recovery-pdf", title = "Versions", activeVersionId = "recovery-v2"))
        dao.insertPartScoreBinding(PartScoreBindingEntity(id = "recovery-binding", piecePartId = "recovery-part", movementId = "recovery-movement", scorePdfId = "recovery-pdf", startPage = 2, endPage = 3))
        dao.insertConcert(ConcertEntity(id = "recovery-concert", title = "Recovery concert"))
        dao.insertConcertItem(ConcertItemEntity(id = "recovery-item", concertId = "recovery-concert", pieceId = "recovery-piece", sortOrder = 0))
        fun marks(id: String) = listOf(InkMark(id = id, kind = MarkKind.PEN, points = listOf(InkPoint(.3f, .4f))))
        val annotations = AnnotationRepository(db)
        val scope = AnnotationScope("recovery-admin", "recovery-piece", "recovery-part", "recovery-v2", 1)
        annotations.save(scope, AnnotationLayer.SHARED, marks("published-mark"), 0); annotations.publish(scope)
        annotations.save(scope.copy(member = "recovery-member"), AnnotationLayer.PERSONAL, marks("owner-mark"), 0)
        annotations.save(scope.copy(member = "recovery-other"), AnnotationLayer.PERSONAL, marks("other-private-mark"), 0)
        annotations.freeze(scope.copy(item = "recovery-item"))
    }
    private fun shellWrite(name: String, file: File) {
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/$name")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output -> file.inputStream().use { it.copyTo(output) } }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
    }
    private fun shellRead(name: String): File = file(name).also { target ->
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat /data/local/tmp/$name"))
            .use { input -> target.outputStream().use { input.copyTo(it) } }
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun contentUriExportPreviewAndRestoreUseTheRealPublicPipeline() = runBlocking<Unit> {
        val source = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val uris = mutableListOf<Uri>()
        fun document() = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "archive-${UUID.randomUUID()}.zip")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            })).also { uris += it }
        try {
            seed(source)
            val mother = document(); val personal = document()
            service(source).export("recovery-admin", ArchiveKind.LIBRARY, mother)
            service(source).export("recovery-member", ArchiveKind.PERSONAL, personal)
            val preview = service(target).preview(mother)
            assertEquals(ArchiveKind.LIBRARY, preview.kind); assertEquals(2, preview.files)
            try { service(source).export("recovery-member", ArchiveKind.LIBRARY, mother); fail("Must reject member mother export") }
            catch (_: IllegalStateException) { }
            assertEquals(preview.hash, service(target).preview(mother).hash)
            service(target).restore(null, mother, preview.hash)
            val mine = service(target).preview(personal)
            assertEquals(ArchiveKind.PERSONAL, mine.kind); assertEquals(0, mine.files)
            service(target).restore("recovery-member", personal, mine.hash)
            assertEquals("owner-mark", InkCodec.decode(target.annotationDao().page("recovery-v2", 1, "recovery-member")!!.payload).single().id)
            assertNull(target.annotationDao().page("recovery-v2", 1, "recovery-other"))
        } finally {
            target.resolutionDao().versions().forEach { File(it.managedFilePath).delete() }
            source.close(); target.close(); owned.forEach { it.delete() }
            uris.forEach { context.contentResolver.delete(it, null, null) }
        }
    }
    @Test fun nativeArchivesKeepVersionsPagesAndOwnerInkAcrossRecovery() = runBlocking<Unit> {
        require(stage == null || stage in listOf("prepare", "restore"))
        val db = open(); var target: ScoreLibraryDatabase? = null
        try {
            if (stage == "prepare") {
                assertTrue(db.resolutionDao().members().isEmpty()); seed(db)
                val mother = file("library.zip"); val personal = file("personal.zip")
                service(db).writeArchive("recovery-admin", ArchiveKind.LIBRARY, mother)
                service(db).writeArchive("recovery-member", ArchiveKind.PERSONAL, personal)
                shellWrite("phase95-library.zip", mother); shellWrite("phase95-personal.zip", personal)
            } else {
                val mother: File; val personal: File
                if (stage == "restore") {
                    assertTrue("Actual new installation must start without members", db.resolutionDao().members().isEmpty())
                    mother = shellRead("phase95-library.zip"); personal = shellRead("phase95-personal.zip"); target = db
                } else {
                    seed(db); mother = file("library.zip"); personal = file("personal.zip")
                    service(db).writeArchive("recovery-admin", ArchiveKind.LIBRARY, mother)
                    service(db).writeArchive("recovery-member", ArchiveKind.PERSONAL, personal)
                    target = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
                }
                val restored = requireNotNull(target)
                service(restored).restoreFile(null, mother, ArchiveFormat.hash(mother))
                assertNull(restored.annotationDao().page("recovery-v2", 1, "recovery-member"))
                assertNull(restored.annotationDao().page("recovery-v2", 1, "recovery-other"))
                service(restored).restoreFile("recovery-member", personal, ArchiveFormat.hash(personal))
                val data = restored.resolutionDao().snapshot()
                assertEquals(2, data.versions.size); assertTrue(data.pieces.single { it.id == "recovery-archived" }.isArchived)
                val score = (ScoreResolver(data).resolvePiece("recovery-member", "recovery-piece") as DomainResult.Success).value
                assertEquals("recovery-v2", score.segments.single().versionId); assertEquals(2, score.segments.single().startPage)
                val engine = NativePdfEngine(context); engine.inspect(score.segments)
                val frame = engine.render(PdfPageRef(score.segments.single(), 1), 1280, 1700, FitMode.PAGE, 0f)
                try { assertEquals(1280, frame.bitmap.width) } finally { frame.bitmap.recycle() }
                val ink = AnnotationRepository(restored).load(AnnotationScope("recovery-member", "recovery-piece", "recovery-part", "recovery-v2", 1, "recovery-item"))
                assertEquals("owner-mark", ink.personal.single().id); assertEquals("published-mark", ink.shared.single().id); assertTrue(ink.locked)
                if (stage == "restore") {
                    compose.runOnIdle { val vm = ViewModelProvider(compose.activity)[LibraryViewModel::class.java]; vm.selectMember("recovery-member"); vm.retry() }
                    compose.onNodeWithTag("nav-profile").performClick()
                    compose.waitUntil(15_000) { compose.onAllNodesWithTag("archive-export-personal").fetchSemanticsNodes().size == 1 }
                    compose.onNodeWithTag("archive-export-library").assertDoesNotExist()
                    compose.onNodeWithTag("nav-concerts").performClick()
                    compose.waitUntil(15_000) { compose.onAllNodesWithTag("concert-recovery-concert").fetchSemanticsNodes().size == 1 }
                    compose.onNodeWithTag("concert-recovery-concert").performClick()
                    compose.waitUntil(15_000) { compose.onAllNodesWithTag("concert-item-recovery-item").fetchSemanticsNodes().size == 1 }
                    compose.onNodeWithTag("concert-item-recovery-item").performClick()
                    compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Recovery score，PDF 第 2 页").fetchSemanticsNodes().size == 1 }
                    compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
                    shellWrite("phase95-reinstall-result.json", file("result.json").apply { writeText("{\"freshInstallation\":true,\"versions\":2,\"activeVersion\":\"recovery-v2\",\"sourcePage\":2,\"ownAndFrozenInk\":true,\"otherPersonalExcluded\":true}") })
                }
            }
        } finally {
            if (stage == null) target?.resolutionDao()?.versions()?.forEach { File(it.managedFilePath).delete() }
            if (target != null && target !== db) target.close()
            db.close()
            if (stage != "prepare") owned.forEach { it.delete() }
        }
    }
}
