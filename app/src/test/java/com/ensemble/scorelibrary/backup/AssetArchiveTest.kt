package com.ensemble.scorelibrary.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.MemberRole
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class AssetArchiveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var source: ScoreLibraryDatabase
    private lateinit var target: ScoreLibraryDatabase
    private lateinit var directory: File
    private val owned = mutableListOf<File>()
    private var barrier: CompletableDeferred<Unit>? = null
    private var entered: CompletableDeferred<Unit>? = null
    private val verifier = object : ArchivePdfVerifier() {
        override suspend fun verify(file: File, pages: Int) { entered?.complete(Unit); barrier?.await() }
    }
    private fun service(db: ScoreLibraryDatabase) = AssetArchiveService(context, db, verifier)
    private suspend fun archive(kind: ArchiveKind = ArchiveKind.LIBRARY, member: String = "a"): File =
        File(directory, "${UUID.randomUUID()}.zip").also { service(source).writeArchive(member, kind, it) }
    private suspend fun restore(file: File, member: String? = null) = service(target).restoreFile(member, file, ArchiveFormat.hash(file))
    private fun root(file: File) = ZipFile(file).use { zip -> JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()) }
    private fun mutate(file: File, change: (MutableMap<String, ByteArray>) -> Unit): File {
        val entries = ZipFile(file).use { zip -> zip.entries().asSequence().associate { it.name to zip.getInputStream(it).readBytes() }.toMutableMap() }
        change(entries)
        return File(directory, "${UUID.randomUUID()}.zip").also { output -> ZipOutputStream(output.outputStream()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        } }
    }
    private fun editManifest(file: File, change: (JSONObject) -> Unit) = mutate(file) {
        val json = JSONObject(requireNotNull(it["manifest.json"]).toString(Charsets.UTF_8)); change(json)
        it["manifest.json"] = json.toString().toByteArray()
    }
    @Test fun organizerMetadataSurvivesNewArchiveRestore() = runBlocking {
        val piece = source.resolutionDao().pieces().first { it.id == "p" }
        source.libraryDao().updatePiece(piece.copy(categoryCode = "01", categoryName = "久石让", organizerSequence = "007"))
        val saved = archive()
        assertEquals(3, root(saved).getInt("formatVersion")); assertEquals(5, root(saved).getInt("databaseVersion"))
        restore(saved)
        val restored = target.resolutionDao().pieces().first { it.id == "p" }
        assertEquals("久石让", restored.categoryName); assertEquals("007", restored.organizerSequence)
    }
    @Test fun legacySchemaThreeArchiveRestoresWithoutInventingCategoriesOrNumbers() = runBlocking {
        val legacy = editManifest(archive()) { manifest ->
            manifest.put("formatVersion", 1).put("databaseVersion", 3)
            ArchiveFormat.rows(manifest.getJSONObject("tables").getJSONArray("concerts")).forEach { row -> row.remove("audienceMembers"); row.remove("audienceParts") }
            ArchiveFormat.rows(manifest.getJSONObject("tables").getJSONArray("pieces")).forEach { row ->
                row.remove("categoryCode"); row.remove("categoryName"); row.remove("organizerSequence")
            }
        }
        restore(legacy)
        assertEquals(source.resolutionDao().pieces().map { it.id }.sorted(), target.resolutionDao().pieces().map { it.id }.sorted())
        assertTrue(target.resolutionDao().pieces().all { it.categoryCode == null && it.organizerSequence == null })
    }
    @Test fun previousSchemaFourArchiveRestoresAsUnrestrictedConcerts() = runBlocking {
        val legacy=editManifest(archive()) { manifest ->
            manifest.put("formatVersion",2).put("databaseVersion",4)
            ArchiveFormat.rows(manifest.getJSONObject("tables").getJSONArray("concerts")).forEach { row -> row.remove("audienceMembers");row.remove("audienceParts") }
        }
        restore(legacy)
        assertTrue(target.resolutionDao().concerts().all { it.audienceMembers==null && it.audienceParts==null })
    }
    @Test fun audienceRestoresAndInvalidAudienceReferenceIsRejected() = runBlocking {
        val concert=source.resolutionDao().concerts().first()
        source.libraryDao().updateConcert(concert.copy(audienceMembers="[\"m\"]",audienceParts="[]"))
        val saved=archive()
        val bad=editManifest(saved) { manifest -> manifest.getJSONObject("tables").getJSONArray("concerts").getJSONObject(0).put("audienceMembers","[\"missing\"]") }
        rejected { restore(bad) }
        assertTrue(target.resolutionDao().members().isEmpty())
        restore(saved)
        assertEquals("[\"m\"]",target.resolutionDao().concerts().first{it.id==concert.id}.audienceMembers)
        assertEquals(ErrorCode.UNAUTHORIZED,(ScoreResolver(target.resolutionDao().snapshot()).resolveConcert("other",concert.id) as DomainResult.Failure).error.code)
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Unsafe operation must fail") } catch (e: Exception) { if (e is CancellationException) throw e }
    }
    @Before fun setup() = runBlocking {
        directory = File(context.cacheDir, "archive-unit-${UUID.randomUUID()}").apply { mkdirs() }
        source = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        val dao = source.libraryDao()
        dao.insertPartDefinition(PartDefinitionEntity(id = "d", name = "Violin I"))
        dao.insertPiece(PieceEntity(id = "p", title = "Library score"))
        dao.insertPiece(PieceEntity(id = "old", title = "Archived", isArchived = true))
        dao.insertPiecePart(PiecePartEntity(id = "part", pieceId = "p", partDefinitionId = "d"))
        for (id in listOf("a", "m", "other")) dao.insertMember(MemberEntity(id = id, displayName = id,
            role = if (id == "a") MemberRole.ADMIN else MemberRole.MEMBER, defaultPartDefinitionId = "d"))
        dao.insertPermission(MemberPartPermissionEntity(id = "grant", memberId = "m", partDefinitionId = "d"))
        dao.insertScorePdf(ScorePdfEntity(id = "pdf", title = "History"))
        for (v in 1..2) {
            val file = File(context.filesDir, "unit-${UUID.randomUUID()}.pdf").also { owned += it; it.writeText("PDF unit metadata version $v") }
            dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "v$v", scorePdfId = "pdf", versionNumber = v,
                managedFilePath = file.path, originalUri = "content://unit/$v", fileHash = ArchiveFormat.hash(file), fileSizeBytes = file.length(), pageCount = 4))
        }
        dao.updateScorePdf(ScorePdfEntity(id = "pdf", title = "History", activeVersionId = "v2"))
        dao.insertPartScoreBinding(PartScoreBindingEntity(id = "binding", piecePartId = "part", scorePdfId = "pdf", startPage = 2, endPage = 3))
        dao.insertConcert(ConcertEntity(id = "concert", title = "Ordered"))
        dao.insertConcertItem(ConcertItemEntity(id = "item", concertId = "concert", pieceId = "p", sortOrder = 0))
        val annotations = source.annotationDao()
        fun ink(id: String) = InkCodec.encode(listOf(InkMark(id = id, kind = MarkKind.PEN, points = listOf(InkPoint(.2f, .3f)))))
        annotations.insertPage(AnnotationPageEntity(id = "shared-page", versionId = "v2", pageIndex = 1, ownerKey = "shared",
            payload = ink("shared-mark"), generation = 1, publishedRevisionId = "revision"))
        annotations.insertRevision(AnnotationRevisionEntity(id = "revision", documentId = "shared-page", payload = ink("shared-mark"), createdBy = "a"))
        annotations.insertPage(AnnotationPageEntity(id = "my-page", versionId = "v2", pageIndex = 1, ownerKey = "m", payload = ink("private-member"), generation = 2))
        annotations.insertPage(AnnotationPageEntity(id = "other-page", versionId = "v2", pageIndex = 1, ownerKey = "other", payload = ink("secret-other"), generation = 3))
        annotations.insertSnapshot(AnnotationConcertSnapshotEntity(id = "snapshot", concertId = "concert", createdBy = "a",
            manifest = AnnotationRepository.manifest(source.resolutionDao().snapshot(), "concert"), payload = JSONObject().put("v2:1", ink("shared-mark")).toString()))
    }
    @After fun cleanup() = runBlocking<Unit> {
        target.resolutionDao().versions().forEach { File(it.managedFilePath).takeIf { file -> file !in owned }?.delete() }
        source.close(); target.close(); owned.forEach { it.delete() }; directory.deleteRecursively()
    }
    @Test fun motherAndPersonalRoundTripKeepHistoryArchiveOrderSnapshotAndOwnerIsolation() = runBlocking {
        val mother = archive(); val personal = archive(ArchiveKind.PERSONAL, "m")
        val text = root(mother).toString()
        assertFalse(text.contains("private-member")); assertFalse(text.contains("secret-other"))
        val mine = root(personal).toString()
        assertTrue(mine.contains("private-member")); assertFalse(mine.contains("secret-other"))
        assertEquals(0, root(personal).getJSONArray("files").length())
        restore(mother)
        assertNull(target.annotationDao().page("v2", 1, "m")); assertNull(target.annotationDao().page("v2", 1, "other"))
        restore(personal, "m"); restore(personal, "m")
        val data = target.resolutionDao().snapshot()
        assertEquals(2, data.versions.size); assertTrue(data.pieces.single { it.id == "old" }.isArchived)
        for (version in data.versions) assertEquals(version.fileHash, ArchiveFormat.hash(File(version.managedFilePath)))
        val result = ScoreResolver(data).resolveConcert("m", "concert") as DomainResult.Success
        assertEquals("v2", result.value.items.single().segments.single().versionId)
        val ink = AnnotationRepository(target).load(AnnotationScope("m", "p", "part", "v2", 1, "item"))
        assertEquals("private-member", ink.personal.single().id); assertEquals("shared-mark", ink.shared.single().id); assertTrue(ink.locked)
        assertTrue(ScoreResolver(data).resolvePiece("a", "p") is DomainResult.Failure)
        assertTrue(data.caches.isEmpty())
    }
    @Test fun ordinaryMemberCannotExportMotherAndOtherOwnerCannotRestorePersonal() = runBlocking {
        rejected { archive(member = "m") }
        val mother = archive(); val personal = archive(ArchiveKind.PERSONAL, "m")
        restore(mother); rejected { restore(personal, "other") }
        assertNull(target.annotationDao().page("v2", 1, "other"))
    }
    @Test fun nonEmptyLibraryAndRepeatedMotherImportNeverOverwriteRowsOrFiles() = runBlocking {
        val mother = archive(); restore(mother)
        val before = target.resolutionDao().snapshot()
        rejected { restore(mother) }
        assertEquals(before, target.resolutionDao().snapshot())
        for (v in before.versions) assertTrue(File(v.managedFilePath).exists())
    }
    @Test fun missingAndCorruptedPdfUnknownEntriesAndTraversalAreRejectedBeforeCommit() = runBlocking {
        val mother = archive()
        val path = root(mother).getJSONArray("files").getJSONObject(0).getString("entry")
        for (bad in listOf(mutate(mother) { it.remove(path) }, mutate(mother) { it[path] = "corrupt".toByteArray() },
            mutate(mother) { it["../escape.pdf"] = byteArrayOf(1) })) {
            rejected { restore(bad) }; assertTrue(target.resolutionDao().pieces().isEmpty())
        }
    }
    @Test fun unsupportedFormatAndInvalidActiveVersionOrSnapshotAreRejected() = runBlocking {
        val mother = archive()
        val bad = listOf(editManifest(mother) { it.put("formatVersion", 99) },
            editManifest(mother) { it.getJSONObject("tables").getJSONArray("score_pdfs").getJSONObject(0).put("activeVersionId", "missing") },
            editManifest(mother) { it.getJSONObject("tables").getJSONArray("annotation_concert_snapshots").getJSONObject(0).put("manifest", "wrong") },
            editManifest(mother) { it.getJSONObject("tables").getJSONArray("pieces").getJSONObject(0).put("unknown", "field") })
        for (file in bad) { rejected { restore(file) }; assertTrue(target.resolutionDao().pieces().isEmpty()) }
    }
    @Test fun personalConflictAndWrongHashPreserveAllExistingMarks() = runBlocking {
        val mother = archive(); val personal = archive(ArchiveKind.PERSONAL, "m")
        restore(mother); restore(personal, "m")
        val current = target.annotationDao().page("v2", 1, "m")!!.copy(generation = 3)
        target.annotationDao().updatePage(current)
        rejected { restore(personal, "m") }
        rejected { service(target).restoreFile("m", personal, "wrong hash") }
        assertEquals(current, target.annotationDao().page("v2", 1, "m"))
    }
    @Test fun forgedPersonalOwnerAndFractionalIntegerAndDeepJsonCannotImport() = runBlocking {
        val personal = archive(ArchiveKind.PERSONAL, "m")
        for (bad in listOf(editManifest(personal) { it.getJSONObject("tables").getJSONArray("annotation_pages").getJSONObject(0).put("ownerKey", "other") },
            editManifest(personal) { it.getJSONObject("tables").getJSONArray("annotation_pages").getJSONObject(0).put("generation", 1.5) },
            mutate(personal) { it["manifest.json"] = ("[".repeat(40) + "0" + "]".repeat(40)).toByteArray() })) {
            rejected { restore(bad, "m") }; assertTrue(target.resolutionDao().pieces().isEmpty())
        }
    }
    @Test fun cancellationDuringValidationCannotCreateDatabaseRowsOrManagedFiles() = runBlocking {
        val mother = archive()
        entered = CompletableDeferred(); barrier = CompletableDeferred()
        val before = File(context.filesDir, "scores").listFiles()?.toSet().orEmpty()
        val work = launch(Dispatchers.Default) { restore(mother) }
        withTimeout(10_000) { entered!!.await() }; work.cancelAndJoin()
        assertTrue(target.resolutionDao().pieces().isEmpty())
        assertEquals(before, File(context.filesDir, "scores").listFiles()?.toSet().orEmpty())
    }
    @Test fun sourceMissingFileStopsExportInsteadOfProducingIncompleteBackup() = runBlocking {
        owned.first().delete(); rejected { archive() }
        assertEquals(2, source.resolutionDao().versions().size)
    }
    @Test fun restoredMemberCannotImpersonateReservedSharedAnnotationOwner() = runBlocking {
        val bad = editManifest(archive()) { json ->
            val tables = json.getJSONObject("tables")
            ArchiveFormat.rows(tables.getJSONArray("members")).first { it.getString("id") == "m" }.put("id", "shared")
            tables.getJSONArray("member_part_permissions").getJSONObject(0).put("memberId", "shared")
        }
        rejected { restore(bad) }; assertTrue(target.resolutionDao().members().isEmpty())
    }
    @Test fun manifestBitCorruptionCannotSilentlyChangeValidJsonMetadata() = runBlocking {
        val mother = archive()
        val bad = File(directory, "bad-crc.zip")
        ZipFile(mother).use { input -> ZipOutputStream(bad.outputStream()).use { zip ->
            for (entry in input.entries().asSequence()) {
                val bytes = input.getInputStream(entry).readBytes()
                zip.putNextEntry(ZipEntry(entry.name).apply {
                    method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                    crc = java.util.zip.CRC32().apply { update(bytes) }.value
                })
                zip.write(bytes); zip.closeEntry()
            }
        } }
        val bytes = bad.readBytes(); val pattern = "Library score".toByteArray()
        val offset = (0..bytes.size - pattern.size).first { at -> pattern.indices.all { bytes[at + it] == pattern[it] } }
        bytes[offset] = 'K'.code.toByte(); bad.writeBytes(bytes)
        rejected { restore(bad) }; assertTrue(target.resolutionDao().pieces().isEmpty())
    }
}
