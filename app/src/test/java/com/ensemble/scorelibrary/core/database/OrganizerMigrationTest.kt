package com.ensemble.scorelibrary.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.database.entity.PieceEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class OrganizerMigrationTest {
    @Test fun schemaThreeRowsAndNotesSurviveAndNewMetadataPersistsOnReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "organizer-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase/3.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sql ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                val indexes = entity.optJSONArray("indices") ?: org.json.JSONArray()
                repeat(indexes.length()) { j -> sql.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { sql.execSQL(setup.getString(it)) }
            sql.execSQL("INSERT INTO pieces(id,title,notes,isArchived,createdAtEpochMillis) VALUES('p','Original','keep notes',0,123)")
            sql.execSQL("INSERT INTO concerts(id,title,status,isArchived) VALUES('c','Old concert','DRAFT',0)")
            sql.version = 3
        }
        fun open() = Room.databaseBuilder(context, ScoreLibraryDatabase::class.java, name).addMigrations(ScoreLibraryDatabase.MIGRATION_3_4, ScoreLibraryDatabase.MIGRATION_4_5, ScoreLibraryDatabase.MIGRATION_5_6).build()
        try {
            val db = open()
            try {
                val row = db.resolutionDao().pieces().single()
                assertEquals("Original", row.title); assertEquals("keep notes", row.notes); assertEquals(123L, row.createdAtEpochMillis)
                assertNull(row.categoryCode); assertNull(row.organizerSequence)
                val concert=db.resolutionDao().concerts().single()
                assertNull(concert.audienceMembers);assertNull(concert.audienceParts)
                db.libraryDao().updateConcert(concert.copy(audienceMembers="[]",audienceParts="[]"))
                db.libraryDao().updatePiece(row.copy(categoryCode = "04", categoryName = "迪士尼", organizerSequence = "007"))
                assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                    runBlocking { db.libraryDao().insertPiece(PieceEntity(title = "Other", organizerSequence = "007")) }
                }
            } finally { db.close() }
            val reopened = open()
            try { val row = reopened.resolutionDao().pieces().single(); assertEquals("迪士尼", row.categoryName); assertEquals("007", row.organizerSequence)
                assertEquals("[]",reopened.resolutionDao().concerts().single().audienceMembers) }
            finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
