package com.ensemble.scorelibrary.annotation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnnotationMigrationTest {
    @Test fun v5InkAndFreezeSurvive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "ink-v5-v6.db"; context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase/5.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val mark = InkMark(kind=MarkKind.SYMBOL, points=listOf(InkPoint(.3f,.4f)),symbol="ff")
        val payload = InkCodec.encode(listOf(mark))
        SQLiteDatabase.openOrCreateDatabase(path,null).use { sql ->
            val entities=schema.getJSONArray("entities")
            repeat(entities.length()) { i -> val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices")?:JSONArray()
                repeat(indices.length()) {j->sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}",table))}
            }
            val setup=schema.getJSONArray("setupQueries");repeat(setup.length()){sql.execSQL(setup.getString(it))}
            sql.execSQL("INSERT INTO score_pdfs(id,title,isArchived,createdAtEpochMillis) VALUES('pdf','Old',0,0)")
            sql.execSQL("INSERT INTO score_pdf_versions(id,scorePdfId,versionNumber,managedFilePath,originalUri,fileHash,fileSizeBytes,pageCount,createdAtEpochMillis) VALUES('v','pdf',1,'old.pdf','content://original','hash',100,2,0)")
            sql.execSQL("INSERT INTO concerts(id,title,status,isArchived) VALUES('c','Frozen','DRAFT',0)")
            sql.execSQL("INSERT INTO annotation_pages VALUES('ink','v',0,'member',?,9,NULL)",arrayOf(payload))
            sql.execSQL("INSERT INTO annotation_pages VALUES('shared','v',0,'shared',?,2,'rev')",arrayOf(payload))
            sql.execSQL("INSERT INTO annotation_revisions VALUES('rev','shared',?,'admin',42)",arrayOf(payload))
            sql.execSQL("INSERT INTO annotation_concert_snapshots VALUES('freeze','c','{}','keep-manifest','admin',42)")
            sql.version=5
        }
        val db=Room.databaseBuilder(context,ScoreLibraryDatabase::class.java,name).addMigrations(ScoreLibraryDatabase.MIGRATION_5_6).build()
        try {
            assertEquals(payload,db.annotationDao().page("v",0,"member")!!.payload)
            assertEquals(9L,db.annotationDao().page("v",0,"member")!!.generation)
            assertEquals("rev",db.annotationDao().page("v",0,"shared")!!.publishedRevisionId)
            assertEquals(payload,db.annotationDao().revision("rev")!!.payload)
            assertEquals("keep-manifest",db.annotationDao().snapshot("c")!!.manifest)
            assertTrue(db.annotationDao().cloudPages().isEmpty())
            assertEquals("old.pdf",db.resolutionDao().versions().single().managedFilePath)
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun versionTwoImportRowsSurviveVersionThreeMigrationAndInkCanBeSaved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "annotation-migration.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase/2.json")!!
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sql ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                repeat(indices.length()) { index -> sql.execSQL(indices.getJSONObject(index).getString("createSql").replace("\u0024{TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { sql.execSQL(setup.getString(it)) }
            sql.execSQL("INSERT INTO pieces(id,title,isArchived,createdAtEpochMillis) VALUES('p','Imported',0,0)")
            sql.execSQL("INSERT INTO part_definitions(id,name,sortOrder,isArchived) VALUES('d','Violin',0,0)")
            sql.execSQL("INSERT INTO piece_parts(id,pieceId,partDefinitionId,isArchived) VALUES('part','p','d',0)")
            sql.execSQL("INSERT INTO members(id,displayName,role,isArchived) VALUES('m','Member','MEMBER',0)")
            sql.execSQL("INSERT INTO score_pdfs(id,title,isArchived,createdAtEpochMillis) VALUES('pdf','Score',0,0)")
            sql.execSQL("INSERT INTO score_pdf_versions(id,scorePdfId,versionNumber,managedFilePath,originalUri,fileHash,fileSizeBytes,pageCount,createdAtEpochMillis) VALUES('v','pdf',1,'managed.pdf','content://fixture','hash',100,4,0)")
            sql.execSQL("UPDATE score_pdfs SET activeVersionId='v' WHERE id='pdf'")
            sql.execSQL("INSERT INTO part_score_bindings(id,piecePartId,scorePdfId,startPage,endPage,isArchived) VALUES('b','part','pdf',2,3,0)")
            sql.execSQL("INSERT INTO member_piece_part_permissions(id,memberId,piecePartId,isArchived) VALUES('grant','m','part',0)")
            sql.execSQL("INSERT INTO piece_part_assignments(id,memberId,piecePartId,isArchived) VALUES('assignment','m','part',0)")
            sql.execSQL("INSERT INTO concerts(id,title,status,isArchived) VALUES('c','Concert','DRAFT',0)")
            sql.execSQL("INSERT INTO concert_items(id,concertId,pieceId,sortOrder,isEnabled,isArchived) VALUES('i','c','p',0,1,0)")
            sql.version = 2
        }
        val db = Room.databaseBuilder(context, ScoreLibraryDatabase::class.java, name).addMigrations(ScoreLibraryDatabase.MIGRATION_2_3, ScoreLibraryDatabase.MIGRATION_3_4, ScoreLibraryDatabase.MIGRATION_4_5, ScoreLibraryDatabase.MIGRATION_5_6).build()
        try {
            val snapshot = db.resolutionDao().snapshot()
            assertEquals("Imported", snapshot.pieces.single().title)
            assertEquals("v", snapshot.pdfs.single().activeVersionId)
            assertEquals("managed.pdf", snapshot.versions.single().managedFilePath)
            assertEquals("grant", snapshot.extraPermissions.single().id)
            assertEquals("assignment", snapshot.assignments.single().id)
            assertEquals("i", snapshot.items.single().id)
            val repository = AnnotationRepository(db)
            val scope = AnnotationScope("m", "p", "part", "v", 1)
            val mark = InkMark(kind = MarkKind.PEN, points = listOf(InkPoint(.4f, .5f)))
            repository.save(scope, AnnotationLayer.PERSONAL, listOf(mark), 0)
            assertEquals(listOf(mark), repository.load(scope).personal)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
