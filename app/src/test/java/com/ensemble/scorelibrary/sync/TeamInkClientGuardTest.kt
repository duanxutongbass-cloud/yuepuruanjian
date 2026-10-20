package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.annotation.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class TeamInkClientGuardTest {
    private val server=ServerSocket(0,50,InetAddress.getByName("127.0.0.1"))
    private val executor=Executors.newSingleThreadExecutor()
    @Volatile private var serverFailure:Throwable?=null
    private val principal=TeamPrincipal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"Synthetic","player","Player","member",true)
    private val artifact=UUID.randomUUID().toString()
    private val path="/v1/annotations/$artifact/0/personal"
    private val source="a".repeat(64)
    private val mark=InkMark(kind=MarkKind.PEN,points=listOf(InkPoint(.2f,.3f),InkPoint(.8f,.7f)),pressures=listOf(.1f,.9f))
    private val gateway=object:TeamAccountGateway {
        override val server="http://127.0.0.1:${this@TeamInkClientGuardTest.server.localPort}"
        override val device=UUID.randomUUID().toString()
        override fun restore():TeamSession?=null
        override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean):TeamSession=error("No login in transport fixture")
        override fun request(session:TeamSession,path:String,body:JSONObject?):JSONObject=error("No alternate transport")
        override fun clear() {}
    }
    private val session=TeamSession(gateway.server,"synthetic-token",Long.MAX_VALUE,principal)
    private val posts=AtomicInteger();private val gets=AtomicInteger()
    @Volatile private var account=principal.accountId
    @Volatile private var hash=source
    @Volatile private var returnedArtifact=artifact
    @Volatile private var returnedPage=0
    @Volatile private var returnedKind="personal"
    @Volatile private var getStatus=200
    private val body get()=JSONObject().put("requestId",UUID.randomUUID().toString()).put("baseRevision",7).put("artifactHash",source)
        .put("protocolVersion",1).put("marks",JSONArray(InkCodec.encode(listOf(mark.copy(pressures=null)))))
    @Before fun start() {
        executor.submit { try { while(!server.isClosed)server.accept().use { socket ->
            socket.soTimeout=5000
            val input=socket.getInputStream();val first=line(input).split(' ')
            check(first.size==3 && first[1]==path)
            val headers=mutableMapOf<String,String>()
            while(true) { val header=line(input);if(header.isEmpty())break
                headers[header.substringBefore(':').lowercase()]=header.substringAfter(':').trim() }
            check(headers["authorization"]=="Bearer ${session.token}")
            check(headers["x-device-id"]==gateway.device)
            val length=headers["content-length"]?.toInt() ?: 0;check(length in 0..1_000_000)
            var remaining=length;val buffer=ByteArray(8192)
            while(remaining>0) { val read=input.read(buffer,0,minOf(remaining,buffer.size));check(read>0);remaining-=read }
            val post=first[0]=="POST"
            val result=if(post) { posts.incrementAndGet();JSONObject().put("error","pressure_data_required") }
            else { gets.incrementAndGet();if(getStatus!=200)JSONObject().put("error","session_invalid")
                else JSONObject().put("accountId",account).put("artifactId",returnedArtifact).put("pageIndex",returnedPage).put("kind",returnedKind)
                    .put("artifactHash",hash).put("revision",7).put("marks",JSONArray(InkCodec.encode(listOf(mark)))) }
            val bytes=result.toString().toByteArray(Charsets.UTF_8)
            val status=if(post)409 else getStatus
            socket.getOutputStream().use { output ->
                output.write("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.write(bytes);output.flush()
            }
        } } catch(error:Throwable) { if(!server.isClosed)serverFailure=error } }
    }
    private fun line(input:InputStream):String {
        val bytes=ByteArrayOutputStream()
        while(true) { val next=input.read();check(next>=0);if(next==10)break;check(bytes.size()<16384);if(next!=13)bytes.write(next) }
        return bytes.toString("US-ASCII")
    }
    @After fun close() { server.close();executor.shutdownNow();assertNull(serverFailure) }
    @Test fun legacyQueuedWriteBecomesVisibleConflictWithoutAutomaticResubmitOrFlattening()=runBlocking {
        val sent=body;val reply=TeamInkClient(gateway).request(session,path,sent)
        assertEquals(409,reply.status);assertEquals("annotation_conflict",reply.body.getString("error"))
        assertEquals(sent.getString("requestId"),reply.body.getString("requestId"))
        assertEquals(listOf(mark),InkCodec.decode(reply.body.getJSONObject("current").getJSONArray("marks").toString()))
        assertNull(InkCodec.decode(reply.body.getJSONArray("candidate").toString()).single().pressures)
        assertEquals(1,posts.get());assertEquals(1,gets.get())
    }
    @Test fun aChangedOwnerOrSourceCannotBecomeAConflictForTheOldPage()=runBlocking {
        for(wrong in listOf("owner","hash","artifact","page","kind")) {
            account=if(wrong=="owner")UUID.randomUUID().toString() else principal.accountId
            hash=if(wrong=="hash")"b".repeat(64) else source
            returnedArtifact=if(wrong=="artifact")UUID.randomUUID().toString() else artifact
            returnedPage=if(wrong=="page")1 else 0
            returnedKind=if(wrong=="kind")"draft" else "personal"
            try { TeamInkClient(gateway).request(session,path,body);fail("Changed identity accepted") }
            catch(_:IllegalArgumentException) {}
        }
        assertEquals(5,posts.get());assertEquals(5,gets.get())
    }
    @Test fun revokedAuthorizationDuringFollowupReadRemainsARejection()=runBlocking {
        getStatus=401
        try { TeamInkClient(gateway).request(session,path,body);fail("Revoked followup accepted") }
        catch(error:AccountRejected) { assertEquals(401,error.status);assertEquals("session_invalid",error.code) }
        assertEquals(1,posts.get());assertEquals(1,gets.get())
    }
}
