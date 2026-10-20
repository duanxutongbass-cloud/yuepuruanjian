package com.ensemble.scorelibrary.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.data.RoomScoreRepository
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepositoryIntegrationTest {
    @Test fun repositoryReadsPermissionsBindingsAndActiveVersionFromRoom() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ScoreLibraryDatabase::class.java).build()
        try {
            runBlocking {
                val dao = db.libraryDao()
                dao.insertPiece(PieceEntity(id = "p", title = "Piece"))
                dao.insertPartDefinition(PartDefinitionEntity(id = "d", name = "I"))
                dao.insertPiecePart(PiecePartEntity(id = "part", pieceId = "p", partDefinitionId = "d"))
                val member = MemberEntity(id = "m", displayName = "Member", defaultPartDefinitionId = "d")
                dao.insertMember(member)
                dao.insertScorePdf(ScorePdfEntity(id = "pdf", title = "Score"))
                dao.insertScorePdfVersion(ScorePdfVersionEntity(id = "v", scorePdfId = "pdf", versionNumber = 1,
                    managedFilePath = "file", originalUri = "content://file", fileHash = "hash", fileSizeBytes = 1, pageCount = 8))
                dao.updateScorePdf(ScorePdfEntity(id = "pdf", title = "Score", activeVersionId = "v"))
                dao.insertPartScoreBinding(PartScoreBindingEntity(piecePartId = "part", scorePdfId = "pdf", startPage = 2, endPage = 8))
                dao.insertConcert(ConcertEntity(id = "c", title = "Concert"))
                dao.insertConcertItem(ConcertItemEntity(concertId = "c", pieceId = "p", sortOrder = 1))
                dao.insertConcertItem(ConcertItemEntity(concertId = "c", pieceId = "p", sortOrder = 0, isEnabled = false))
                val repository = RoomScoreRepository(db)
                assertTrue((repository.visibleParts("m", "p") as DomainResult.Success).value.isEmpty())
                assertEquals(ErrorCode.UNAUTHORIZED, (repository.resolveConcert("m", "c") as DomainResult.Failure).error.code)
                dao.insertPermission(MemberPartPermissionEntity(memberId = "m", partDefinitionId = "d"))
                val concert = (repository.resolveConcert("m", "c") as DomainResult.Success).value
                assertEquals(1, concert.items.size)
                assertEquals("v", concert.items.single().segments.single().versionId)
                assertEquals(2, concert.items.single().segments.single().startPage)
                dao.updateMember(member.copy(isArchived = true))
                assertEquals(ErrorCode.MEMBER_MISSING, (repository.resolveConcert("m", "c") as DomainResult.Failure).error.code)
            }
        } finally { db.close() }
    }

    @Test fun migrationPreservesVersionOneRowsAndEnablesExistingItems() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val schema = javaClass.classLoader!!.getResourceAsStream(
            "com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase/1.json")!!.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\u0024{TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
            sqlite.execSQL("INSERT INTO pieces(id,title,isArchived,createdAtEpochMillis) VALUES('p','Piece',0,0)")
            sqlite.execSQL("INSERT INTO concerts(id,title,status,isArchived) VALUES('c','Concert','DRAFT',0)")
            sqlite.execSQL("INSERT INTO concert_items(id,concertId,pieceId,sortOrder,isArchived) VALUES('i','c','p',0,0)")
            sqlite.version = 1
        }
        val db = Room.databaseBuilder(context, ScoreLibraryDatabase::class.java, name)
            .addMigrations(ScoreLibraryDatabase.MIGRATION_1_2, ScoreLibraryDatabase.MIGRATION_2_3, ScoreLibraryDatabase.MIGRATION_3_4, ScoreLibraryDatabase.MIGRATION_4_5, ScoreLibraryDatabase.MIGRATION_5_6).build()
        try {
            runBlocking {
                val snapshot = db.resolutionDao().snapshot()
                assertEquals("Piece", snapshot.pieces.single().title)
                assertTrue(snapshot.items.single().isEnabled)
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
