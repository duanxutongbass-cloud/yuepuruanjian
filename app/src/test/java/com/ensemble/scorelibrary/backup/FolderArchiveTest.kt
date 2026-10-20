package com.ensemble.scorelibrary.backup

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.sync.*
import com.ensemble.scorelibrary.ui.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FolderArchiveTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database:ScoreLibraryDatabase
    private lateinit var scope:TeamLibraryScope
    private lateinit var store:LibraryFolderStore
    private lateinit var service:FolderArchiveService
    private val collections=mutableListOf<TeamCollection>()
    private fun image():String {
        val bitmap=Bitmap.createBitmap(16,24,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        return try { val output=ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output))
            Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)
        } finally { bitmap.recycle() }
    }
    @Before fun setup()=runBlocking {
        context.getSharedPreferences("personal-library-folders",0).edit().clear().commit()
        database=Room.inMemoryDatabaseBuilder(context,ScoreLibraryDatabase::class.java).build()
        scope=TeamLibraryScope(context);store=LibraryFolderStore(context);service=FolderArchiveService(context,store,FolderAccess(database,scope))
        database.libraryDao().insertMember(MemberEntity(id="m",displayName="Test"))
        database.libraryDao().insertMember(MemberEntity(id="n",displayName="Other"))
        database.libraryDao().insertPiece(PieceEntity(id="p",title="合成乐曲",categoryCode="01",organizerSequence="007"))
    }
    @After fun cleanup() {
        scope.deactivate();database.close()
        collections.forEach { it.database.close();val target=it.directory.parentFile!!.canonicalFile
            check(target.parentFile==File(context.noBackupFilesDir,"team-libraries").canonicalFile && target.name.matches(Regex("[a-f0-9-]{36}")))
            target.deleteRecursively()
        }
    }
    private suspend fun saved():ByteArray {
        store.edit("m",LibraryFolder(id="score",name="节目",pieces=setOf("p"),coverPiece="p"))
        store.edit("m",LibraryFolder(id="image",name="排练",pieces=setOf("p","missing"),image=image()))
        return service.exportBytes("m")
    }
    private fun root(bytes:ByteArray)=ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        assertEquals("manifest.json",zip.nextEntry.name);JSONObject(zip.readBytes().toString(Charsets.UTF_8))
    }
    private fun changed(bytes:ByteArray,edit:(JSONObject)->Unit):ByteArray {
        val json=root(bytes).apply(edit)
        return zipped(mapOf("manifest.json" to json.toString().toByteArray(),"README.txt" to "Synthetic".toByteArray()))
    }
    private fun zipped(entries:Map<String,ByteArray>):ByteArray {
        val output=ByteArrayOutputStream();ZipOutputStream(output).use { zip -> entries.forEach { (name,bytes) ->
            zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()
        } };return output.toByteArray()
    }
    private suspend fun rejected(block:suspend ()->Unit) {
        try { block();fail("Expected rejection") } catch(e:AssertionError) { throw e } catch(_:Exception) {}
    }
    @Test fun independentZipRestoresBothCoversAndMissingReferencesWithoutChangingOriginals()=runBlocking {
        val bytes=saved();val original=store.read("m")
        store.edit("m",null,"score");store.edit("m",null,"image")
        val preview=service.previewBytes("m",bytes)
        assertEquals(2,preview.added);assertEquals(1,preview.missingReferences);assertTrue(preview.conflicts.isEmpty())
        service.restoreBytes("m",bytes,preview)
        assertEquals(original,LibraryFolderStore(context).read("m"))
        assertEquals("007",database.resolutionDao().pieces().single().organizerSequence)
        assertTrue(database.resolutionDao().versions().isEmpty());assertTrue(database.resolutionDao().snapshot().extraPermissions.isEmpty())
        assertEquals(setOf("manifest.json","README.txt"),ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> buildSet {
            while(true) { val entry=zip.nextEntry ?: break;add(entry.name);zip.readBytes() }
        } })
        assertFalse(root(bytes).toString().contains("token"))
    }
    @Test fun identicalRetryIsIdempotentAndConflictRejectsWholeMerge()=runBlocking {
        val bytes=saved();val before=store.read("m");val equal=service.previewBytes("m",bytes)
        assertEquals(2,equal.identical);service.restoreBytes("m",bytes,equal);assertEquals(before,store.read("m"))
        store.edit("m",before.first().copy(name="本机新名称"));store.edit("m",null,"image")
        val conflict=service.previewBytes("m",bytes);assertEquals(listOf("节目"),conflict.conflicts)
        val state=store.read("m");rejected { service.restoreBytes("m",bytes,conflict) };assertEquals(state,store.read("m"))
    }
    @Test fun previewBindsLocalRevisionAndSourceBytes()=runBlocking {
        val bytes=saved();store.edit("m",null,"score");store.edit("m",null,"image")
        val preview=service.previewBytes("m",bytes)
        store.edit("m",LibraryFolder(id="new",name="新建"));val current=store.read("m")
        rejected { service.restoreBytes("m",bytes,preview) };assertEquals(current,store.read("m"))
        val fresh=service.previewBytes("m",bytes)
        rejected { service.restoreBytes("m",changed(bytes) { it.put("createdAtEpochMillis",1) },fresh) }
        assertEquals(current,store.read("m"))
    }
    @Test fun foreignOwnerAndLibraryNamespaceAreRejected()=runBlocking {
        val bytes=saved();val n=store.fingerprint("n")
        rejected { service.previewBytes("n",bytes) };assertEquals(n,store.fingerprint("n"))
        rejected { service.previewBytes("m",changed(bytes) { it.put("space","team:foreign") }) }
    }
    @Test fun malformedImagesFieldsAndReferencesAreRejectedWithoutReplacingData()=runBlocking {
        val bytes=saved();val before=store.fingerprint("m")
        val edits=listOf<(JSONObject)->Unit>(
            { it.getJSONArray("folders").getJSONObject(1).put("image","bad") },
            { it.getJSONArray("folders").getJSONObject(1).put("image",Base64.encodeToString(ByteArray(524289),Base64.NO_WRAP)) },
            { it.getJSONArray("folders").getJSONObject(0).put("image",image()) },
            { it.getJSONArray("folders").getJSONObject(0).put("extra",1) },
            { it.getJSONArray("folders").getJSONObject(0).getJSONArray("pieces").put("p") },
            { it.getJSONArray("folders").getJSONObject(0).getJSONArray("pieces").put(7) },
            { root -> val refs=root.getJSONArray("pieceReferences")
                (0 until refs.length()).map { refs.getJSONObject(it) }.single { it.getString("id")=="p" }.put("sequence","999") },
            { it.put("pieceReferences",org.json.JSONArray()) },
            { it.put("formatVersion",2) },
            { it.put("password","should-never-be-a-supported-field") }
        )
        for(edit in edits) { rejected { service.previewBytes("m",changed(bytes,edit)) };assertEquals(before,store.fingerprint("m")) }
    }
    @Test fun badZipCrcTruncationExtraPathsAndOversizeAreRejected()=runBlocking {
        val bytes=saved();val before=store.read("m")
        rejected { service.previewBytes("m",bytes.copyOf(bytes.size-30)) }
        rejected { service.previewBytes("m",zipped(mapOf("../manifest.json" to byteArrayOf(1)))) }
        rejected { service.previewBytes("m",zipped(mapOf("manifest.json" to byteArrayOf(1),"README.txt" to byteArrayOf(1),"extra" to byteArrayOf(1)))) }
        rejected { service.previewBytes("m",ByteArray(FolderArchiveService.ZIP_LIMIT+1)) }
        // Alter the central directory CRC, preserving otherwise well-formed compressed JSON.
        val corrupt=bytes.copyOf();val central=(0 until corrupt.size-4).first { i ->
            corrupt[i]==80.toByte() && corrupt[i+1]==75.toByte() && corrupt[i+2]==1.toByte() && corrupt[i+3]==2.toByte()
        };corrupt[central+16]=(corrupt[central+16].toInt() xor 1).toByte()
        rejected { service.previewBytes("m",corrupt) };assertEquals(before,store.read("m"))
    }
    @Test fun corruptLocalPreferencesAndCancelledRestoreNeverOverwrite()=runBlocking {
        val bytes=saved();val preview=service.previewBytes("m",bytes);val before=store.read("m")
        val job=launch(start=CoroutineStart.LAZY) { service.restoreBytes("m",bytes,preview) };job.cancel();job.join()
        assertEquals(before,store.read("m"))
        context.getSharedPreferences("personal-library-folders",0).edit().putString("folders-m","broken").commit()
        rejected { service.restoreBytes("m",bytes,preview) }
        assertEquals("broken",context.getSharedPreferences("personal-library-folders",0).getString("folders-m",null))
    }
    @Test fun realTeamScopeRequiresSameAccountAndValidLease()=runBlocking {
        fun id()=UUID.randomUUID().toString()
        val session=TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,TeamPrincipal(id(),id(),id(),"T","user","Player","member",true))
        val c=scope.activate(session).also { collections+=it }
        c.database.libraryDao().insertMember(MemberEntity(id=c.member,displayName="Player"))
        store.edit(c.member,LibraryFolder(id="team-folder",name="本人",image=image()))
        val bytes=service.exportBytes(c.member);assertEquals(1,service.previewBytes(c.member,bytes).identical)
        rejected { service.previewBytes("m",bytes) }
        c.authorizeUntil(0);rejected { service.previewBytes(c.member,bytes) }
        scope.deactivate();rejected { service.previewBytes("m",bytes) }
        assertEquals(1,store.read(c.member).size)
    }
    @Test fun systemDocumentStreamsAreWrittenRereadAndBoundToConfirmation()=runBlocking {
        saved()
        val uri=android.net.Uri.parse("content://synthetic-folder-backup/archive.zip")
        val output=ByteArrayOutputStream()
        val resolver=org.robolectric.Shadows.shadowOf(context.contentResolver)
        resolver.registerOutputStream(uri,output)
        resolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(output.toByteArray()) }
        service.export("m",uri)
        val original=store.read("m");store.edit("m",null,"score");store.edit("m",null,"image")
        val preview=service.preview("m",uri)
        service.restore("m",uri,preview);assertEquals(original,store.read("m"))
        val another=service.preview("m",uri);val before=store.fingerprint("m")
        resolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(changed(output.toByteArray()) { it.put("createdAtEpochMillis",1) }) }
        rejected { service.restore("m",uri,another) };assertEquals(before,store.fingerprint("m"))
        resolver.registerInputStreamSupplier(uri) { ByteArrayInputStream(byteArrayOf(1,2)) }
        rejected { service.export("m",uri) }
    }
    @Test fun impossibleMergeCapacityIsRejectedDuringPreview()=runBlocking {
        val bytes=saved()
        val current=(0 until 100).map { LibraryFolder(id="existing-$it",name="本机 $it") }
        context.getSharedPreferences("personal-library-folders",0).edit().putString("folders-m",FolderCodec.encode(current)).commit()
        rejected { service.previewBytes("m",bytes) };assertEquals(current,store.read("m"))
    }
    @Test fun authorizationGuardRunsBeforeAtomicPreferenceWrite()=runBlocking {
        fun id()=UUID.randomUUID().toString()
        val session=TeamSession(TEAM_SERVICE_URL,"s".repeat(43),Long.MAX_VALUE,TeamPrincipal(id(),id(),id(),"T","user","Player","member",true))
        val c=scope.activate(session).also { collections+=it }
        c.database.libraryDao().insertMember(MemberEntity(id=c.member,displayName="Player"))
        val owner=FolderAccess(database,scope).owner(c.member);val before=store.fingerprint(c.member)
        scope.deactivate()
        rejected { store.merge(c.member,listOf(LibraryFolder(id="late",name="不能写入")),before,owner.guard) }
        assertEquals(before,store.fingerprint(c.member));assertTrue(store.read(c.member).isEmpty())
    }
}
