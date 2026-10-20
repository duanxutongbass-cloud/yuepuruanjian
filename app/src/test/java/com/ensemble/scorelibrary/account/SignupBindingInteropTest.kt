package com.ensemble.scorelibrary.account

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SignupBindingInteropTest {
    @Test fun signupAndManage()=runBlocking {
        val fixture=listOf(File("../team-service/multiteam_fixture.py"),File("team-service/multiteam_fixture.py")).first{it.isFile}.canonicalFile
        val root=Files.createTempDirectory("signup-bind-").toFile()
        val process=ProcessBuilder(System.getenv("TEAM_SERVICE_TEST_PYTHON")?:"python",fixture.path,root.path)
            .redirectOutput(File(root,"stdout.log")).redirectError(File(root,"stderr.log")).start()
        fun waitFile(name:String):File {
            val file=File(root,name);val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(120)
            while(!file.exists() && process.isAlive && System.nanoTime()<until)Thread.sleep(30)
            check(file.exists()){"Synthetic signup fixture failed: "+File(root,"stderr.log").readText().take(1500)}
            return file
        }
        try {
            val server="http://127.0.0.1:"+JSONObject(waitFile("ready.json").readText()).getInt("port")
            File(root,"migrate.request").writeText("Synthetic migration")
            val target=JSONObject(waitFile("migrated.json").readText()).getString("second")
            val api=TeamAccountApi();val device=UUID.randomUUID().toString();val password="synthetic rehearsal passphrase"
            val admin=api.login(server,device,"Synthetic shared tablet","director",password)
            val began=System.nanoTime()
            val registered=api.signup(server,device,"Synthetic shared tablet","fresh-manager",password,"个人管理者",false)
            println("SYNTHETIC_SIGNUP_AND_DEVICE_BOUND_MS="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began))
            assertEquals(1,registered.principal.multiTeamProtocol);assertFalse(registered.principal.joined)
            assertNotEquals(admin.principal.accountId,registered.principal.accountId)
            val devices=api.request(server,"/v1/me/devices",device,registered).getJSONArray("devices")
            assertEquals(1,devices.length());assertEquals(device,devices.getJSONObject(0).getString("id"));assertTrue(devices.getJSONObject(0).getBoolean("current"))
            assertEquals("unjoined",api.request(server,"/v1/me",device,registered).getJSONObject("principal").getString("membership"))
            val duplicate=runCatching{api.signup(server,device,"Synthetic shared tablet","fresh-manager",password,"重复",false)}.exceptionOrNull() as AccountRejected
            assertEquals("username_unavailable",duplicate.code)
            val signedIn=api.login(server,device,"Synthetic shared tablet","fresh-manager",password)
            assertEquals(registered.principal.accountId,signedIn.principal.accountId)
            assertEquals(admin.principal.accountId,api.request(server,"/v1/me",device,admin).getJSONObject("principal").getString("accountId"))
            val create=JSONObject().put("requestId",UUID.randomUUID().toString()).put("name","本人合成团队").put("confirmed",true)
            var job=api.request(server,"/v1/me/teams/create",device,signedIn,create)
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(120)
            while(job.optBoolean("pending")) {
                check(System.nanoTime()<until);Thread.sleep(100)
                job=api.request(server,"/v1/me/team-operations/"+create.getString("requestId"),device,signedIn)
            }
            fun switch(session:TeamSession,team:String):TeamSession {
                val result=api.request(server,"/v1/me/teams/switch",device,session,JSONObject().put("teamId",team))
                return TeamSession(server,result.getString("token"),result.getLong("expiresAt"),TeamPrincipal.parse(result.getJSONObject("principal")))
            }
            val owner=switch(signedIn,job.getString("teamId"));assertEquals("admin",owner.principal.role)
            assertEquals(0,api.request(server,"/v2/catalog",device,owner).getJSONArray("artifacts").length())
            val otherAdmin=if(admin.principal.teamId==target)admin else switch(admin,target)
            val code=api.request(server,"/v1/admin/join-code",device,otherAdmin,JSONObject().put("baseRevision",0)).getString("code")
            api.request(server,"/v1/me/teams/apply",device,owner,JSONObject().put("requestId",UUID.randomUUID().toString()).put("code",code))
            val pending=switch(owner,target);assertEquals("pending",pending.principal.membership)
            val row=api.request(server,"/v1/admin/join-requests",device,otherAdmin).getJSONArray("requests").getJSONObject(0)
            api.request(server,"/v1/admin/join-requests/review",device,otherAdmin,JSONObject().put("requestId",UUID.randomUUID().toString())
                .put("baseRevision",row.getLong("revision")).put("accountId",row.getString("accountId")).put("approved",true))
            val verified=api.request(server,"/v1/me",device,pending).getJSONObject("principal")
            assertEquals("member",verified.getString("role"));assertEquals("joined",verified.getString("membership"))
            val member=pending.copy(principal=TeamPrincipal.parse(verified))
            assertEquals(0,api.request(server,"/v2/catalog",device,member).getJSONArray("artifacts").length())
            assertEquals(403,(runCatching{api.request(server,"/v1/admin/members",device,member)}.exceptionOrNull() as AccountRejected).status)
            assertEquals("admin",switch(member,owner.principal.teamId).principal.role)
        } finally {
            process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(10,TimeUnit.SECONDS)}
            check(root.canonicalFile.parentFile==File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile);root.deleteRecursively()
        }
    }
}
