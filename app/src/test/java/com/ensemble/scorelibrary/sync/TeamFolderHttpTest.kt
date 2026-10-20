package com.ensemble.scorelibrary.sync

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeamFolderHttpTest {
    @Test fun live()=runBlocking {
        val source=listOf(File("../team-service/sync_interop_fixture.py"),File("team-service/sync_interop_fixture.py")).first { it.isFile }.canonicalFile
        val directory=Files.createTempDirectory("folders-http-").toFile()
        val process=ProcessBuilder(System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: "python",source.path,directory.path)
            .redirectOutput(File(directory,"stdout.log")).redirectError(File(directory,"stderr.log"))
            .apply { environment()["PYTHONDONTWRITEBYTECODE"]="1" }.start()
        val app=ApplicationProvider.getApplicationContext<Context>()
        val scopes=mutableListOf<TeamLibraryScope>();val collections=mutableListOf<TeamCollection>()
        try {
            val ready=File(directory,"ready-port");val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!ready.exists() && process.isAlive && System.nanoTime()<deadline)Thread.sleep(50)
            check(ready.exists() && process.isAlive) { "Fixture failed: "+File(directory,"stderr.log").readText().take(1000) }
            val server="http://127.0.0.1:${ready.readText().trim().toInt()}";val api=TeamAccountApi()
            val devices=listOf(UUID.randomUUID().toString(),UUID.randomUUID().toString())
            val sessions=devices.map { api.login(server,it,"Synthetic folder tablet","director","synthetic rehearsal passphrase") }
            val originals=File(directory,"artifacts").listFiles()!!.associate { it.name to FolderPayload.hash(Base64.encodeToString(it.readBytes(),Base64.NO_WRAP)) }
            data class Device(val c:TeamCollection,val store:LibraryFolderStore,val cloud:TeamFolderSync,val client:TeamFolderClient)
            fun device(index:Int):Device {
                val root=File(directory,"d$index").apply { mkdirs() }
                val context=object:ContextWrapper(app) {
                    override fun getNoBackupFilesDir()=root
                    override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences(directory.name+"-$index-"+name,mode)
                }
                val s=sessions[index]
                val gateway=object:TeamAccountGateway {
                    override val server=server;override val device=devices[index]
                    override fun restore()=s
                    override fun clear() {}
                    override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=error("Unused")
                    override fun request(session:TeamSession,path:String,body:JSONObject?)=api.request(server,path,device,session,body)
                }
                val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                val scope=TeamLibraryScope(context).also { scopes+=it };val c=scope.activate(s).also { collections+=it }
                val store=LibraryFolderStore(context);val client=TeamFolderClient(gateway)
                val cloud=TeamFolderSync(scope,store,TeamFolderJournal(root,{key}),client).also { it.automatic=false }
                return Device(c,store,cloud,client)
            }
            val a=device(0);val b=device(1)
            a.cloud.sync(a.c);b.cloud.sync(b.c)
            val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            val output=ByteArrayOutputStream();assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));bitmap.recycle()
            val folder=LibraryFolder(id="shared-stable-folder",name="🎻</谱\"\\\n",pieces=(0..2999).map { "opaque-piece-$it" }.toSet(),image=Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP))
            assertTrue(FolderPayload.encode(listOf(folder)).toByteArray().size>16384)
            a.store.edit(a.c.member,folder);a.cloud.sync(a.c);b.cloud.sync(b.c)
            assertEquals(listOf(folder),b.store.read(b.c.member));assertFalse(b.cloud.state.value.pending)
            b.c.session=sessions[1].copy(offline=true);b.store.edit(b.c.member,folder.copy(name="B 离线修改"));b.cloud.sync(b.c)
            assertTrue(b.cloud.state.value.pending)
            a.store.edit(a.c.member,folder.copy(name="A 在线修改"));a.cloud.sync(a.c)
            api.request(server,"/v1/me",devices[1],sessions[1]);b.c.session=sessions[1];b.cloud.sync(b.c)
            assertTrue(b.cloud.state.value.conflict)
            val preview=b.cloud.preview(b.c);assertEquals("B 离线修改",preview.local.single().name);assertEquals("A 在线修改",preview.remote.single().name)
            b.cloud.resolve(b.c,preview,true);a.cloud.sync(a.c)
            assertEquals("B 离线修改",a.store.read(a.c.member).single().name)
            assertEquals(a.store.read(a.c.member),b.store.read(b.c.member))
            api.request(server,"/v1/auth/logout",devices[1],sessions[1],JSONObject())
            b.store.edit(b.c.member,folder.copy(name="撤销后保留"))
            try { b.cloud.sync(b.c);fail("Revoked session must fail") } catch(error:AccountRejected) { assertEquals(401,error.status) }
            assertEquals("撤销后保留",b.store.read(b.c.member).single().name)
            assertEquals("B 离线修改",TeamFolderProtocol.head(a.client.request(sessions[0]).body,sessions[0]).folders().single().name)
            originals.forEach { (name,hash) -> assertEquals(hash,FolderPayload.hash(Base64.encodeToString(File(directory,"artifacts/$name").readBytes(),Base64.NO_WRAP))) }
            assertTrue(a.c.database.resolutionDao().snapshot().extraPermissions.isEmpty())
            assertTrue(b.c.database.resolutionDao().snapshot().extraPermissions.isEmpty())
        } finally {
            scopes.forEach { it.deactivate() };collections.forEach { it.database.close() }
            process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)) { process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS) }
            check(directory.canonicalFile.parentFile==File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile)
            directory.deleteRecursively()
        }
    }
}
