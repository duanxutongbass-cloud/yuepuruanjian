package com.ensemble.scorelibrary.sync

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.ui.LibraryFolder
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeamFolderProtocolTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val root=File(context.cacheDir,"folder-sync-${UUID.randomUUID()}").apply { mkdirs() }
    private val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val session=TeamSession(TEAM_SERVICE_URL,"synthetic-token",Long.MAX_VALUE,TeamPrincipal(
        UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Team","tester","Player","member",false))
    private fun owner(row:JSONObject)=row.put("protocolVersion",1).put("accountId",session.principal.accountId).put("teamId",session.principal.teamId)
    private fun head(payload:String="[]",revision:Long=0)=owner(JSONObject().put("revision",revision).put("payload",payload).put("sha256",FolderPayload.hash(payload)))
    private fun rejected(block:()->Unit) { try { block();fail("Expected rejection") } catch(e:AssertionError) { throw e } catch(_:Exception) {} }
    @After fun clean() { val target=root.canonicalFile;check(target.parentFile==context.cacheDir.canonicalFile);target.deleteRecursively() }
    @Test fun canonicalUnicode() {
        val payload=FolderPayload.encode(listOf(LibraryFolder(id="folder",name="🎻</谱\"\\\n\u0001",pieces=linkedSetOf("p/1","p2"))))
        assertEquals("[{\"coverPiece\":null,\"id\":\"folder\",\"image\":null,\"name\":\"🎻</谱\\\"\\\\\\n\\u0001\",\"pieces\":[\"p/1\",\"p2\"]}]",payload)
        assertEquals(payload,FolderPayload.encode(FolderPayload.decode(payload)))
        assertEquals(payload,TeamFolderProtocol.head(head(payload,1),session).payload)
        rejected { FolderPayload.encode(listOf(LibraryFolder(name="\uD800"))) }
    }
    @Test fun rejectsUnknownAndMalformedFolders() {
        val folder=LibraryFolder(id="f",name="Test",pieces=setOf("p"))
        val payload=FolderPayload.encode(listOf(folder))
        for(value in listOf(payload.replace("\"name\":", "\"extra\":1,\"name\":"),
            payload.replace("[\"p\"]","[\"p\",\"p\"]"),payload.replace("\"p\"","1")))rejected { FolderPayload.decode(value) }
        rejected { FolderPayload.encode(listOf(folder,folder)) }
        rejected { FolderPayload.encode(listOf(folder.copy(id="x\u007f"))) }
        rejected { FolderPayload.encode(listOf(folder.copy(name="🎻".repeat(51)))) }
    }
    @Test fun nativePngRoundtrip() {
        val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val output=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,output);bitmap.recycle()
        val image=Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP)
        val folder=LibraryFolder(id="f",name="封面",image=image)
        assertEquals(folder,FolderPayload.decode(FolderPayload.encode(listOf(folder))).single())
        rejected { FolderPayload.encode(listOf(folder.copy(coverPiece="p"))) }
        rejected { FolderPayload.encode(listOf(folder.copy(image=image+"\n"))) }
        rejected { FolderPayload.encode(listOf(folder.copy(image=Base64.encodeToString("not a png".toByteArray(),Base64.NO_WRAP)))) }
    }
    @Test fun responseIdentityHashAndRevision() {
        rejected { TeamFolderProtocol.head(head().put("accountId",UUID.randomUUID().toString()),session) }
        rejected { TeamFolderProtocol.head(head().put("teamId",UUID.randomUUID().toString()),session) }
        rejected { TeamFolderProtocol.head(head().put("sha256","a".repeat(64)),session) }
        rejected { TeamFolderProtocol.head(head().put("revision",true),session) }
        rejected { TeamFolderProtocol.head(head().put("revision",0.0),session) }
        rejected { TeamFolderProtocol.head(head().put("revision",9_007_199_254_740_992L),session) }
        rejected { TeamFolderProtocol.head(head().put("protocolVersion",2),session) }
        rejected { TeamFolderProtocol.head(head().put("grants","all"),session) }
        rejected { TeamFolderProtocol.head(head("[ ]"),session) }
    }
    @Test fun receiptBindsRequestCandidateAndBase() {
        val pending=TeamFolderProtocol.pending(2,FolderPayload.encode(listOf(LibraryFolder(id="f",name="排练"))))
        val row=owner(JSONObject().put("requestId",pending.requestId).put("revision",3).put("sha256",FolderPayload.hash(pending.payload)))
        assertEquals(3L,TeamFolderProtocol.receipt(row,session,pending).revision)
        rejected { TeamFolderProtocol.receipt(JSONObject(row.toString()).put("requestId",UUID.randomUUID().toString()),session,pending) }
        rejected { TeamFolderProtocol.receipt(JSONObject(row.toString()).put("revision",4),session,pending) }
        rejected { TeamFolderProtocol.receipt(JSONObject(row.toString()).put("sha256",FolderPayload.hash("[]")),session,pending) }
        val conflict=owner(JSONObject().put("requestId",pending.requestId).put("error","folder_conflict").put("currentRevision",4).put("currentSha256",FolderPayload.hash("[]")))
        assertEquals(4L,TeamFolderProtocol.conflict(conflict,session,pending).revision)
        rejected { TeamFolderProtocol.conflict(conflict.put("currentRevision",2),session,pending) }
    }
    @Test fun encryptedPendingSurvivesRenewal() {
        val journal=TeamFolderJournal(root,{key});assertEquals(FolderCheckpoint(),journal.read(session))
        val payload=FolderPayload.encode(listOf(LibraryFolder(id="f",name="本人离线排练")))
        val checkpoint=FolderCheckpoint(1,FolderPayload.hash("[]"),TeamFolderProtocol.pending(1,payload),FolderHead(2,"[]",FolderPayload.hash("[]")))
        journal.write(session,checkpoint)
        val file=root.walkTopDown().single { it.name=="folders-sync.enc" }
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("本人离线排练"))
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("synthetic-token"))
        val renewed=session.copy(token="new-synthetic-token",expiresAt=Long.MAX_VALUE-1)
        assertEquals(checkpoint,TeamFolderJournal(root,{key}).read(renewed))
        assertEquals(FolderCheckpoint(),journal.read(session.copy(principal=session.principal.copy(accountId=UUID.randomUUID().toString()))))
        rejected { journal.read(session.copy(principal=session.principal.copy(memberId=UUID.randomUUID().toString()))) }
        assertEquals(checkpoint,journal.read(session))
    }
    @Test fun corruptJournalPreservesBothLocalFiles() {
        val journal=TeamFolderJournal(root,{key});journal.write(session,FolderCheckpoint())
        val file=root.walkTopDown().single { it.name=="folders-sync.enc" }
        val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
        rejected { journal.read(session) };assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun interruptedApplyRoundtrip() {
        val journal=TeamFolderJournal(root,{key})
        val head=FolderHead(1,"[]",FolderPayload.hash("[]"))
        val state=FolderCheckpoint(0,FolderPayload.hash("[]"),applying=FolderApply("a".repeat(64),head))
        journal.write(session,state);assertEquals(state,TeamFolderJournal(root,{key}).read(session))
    }
}
