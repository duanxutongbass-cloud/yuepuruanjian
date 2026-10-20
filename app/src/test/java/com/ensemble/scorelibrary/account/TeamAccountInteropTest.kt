package com.ensemble.scorelibrary.account

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Kotlin HTTP client against the real Python service, using only generated identities. */
@RunWith(RobolectricTestRunner::class)
class TeamAccountInteropTest {
    @Test fun loginInviteRegisterDeviceBindingAndLogoutUseTheSameProtocol() {
        val source = listOf(File("../team-service/interop_fixture.py"), File("team-service/interop_fixture.py"))
            .first { it.isFile }.canonicalFile
        val directory = Files.createTempDirectory("team-account-interop-").toFile()
        val python = System.getenv("TEAM_SERVICE_TEST_PYTHON") ?: if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "python" else "python3"
        val process = ProcessBuilder(python, source.path, directory.path)
            .redirectOutput(File(directory, "stdout.log")).redirectError(File(directory, "stderr.log"))
            .apply { environment()["PYTHONDONTWRITEBYTECODE"] = "1" }.start()
        try {
            val ready = File(directory, "ready-port")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!ready.exists() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            check(ready.exists()) { "Python account fixture did not start; check TEAM_SERVICE_TEST_PYTHON and fixture stderr" }
            val server = "http://127.0.0.1:" + ready.readText().trim()
            val api = TeamAccountApi()
            val device = UUID.randomUUID().toString()
            val admin = api.login(server, device, "JVM test device", "director", "synthetic rehearsal passphrase")
            assertEquals("admin", admin.principal.role)
            val invitation = api.request(server, "/v1/admin/invitations", device, admin, JSONObject()).getString("invitation")
            val member = api.login(server, device, "Shared JVM device", "violinist", "synthetic rehearsal passphrase", "Violinist", invitation, true)
            assertEquals("member", member.principal.role)
            assertNotEquals(admin.principal.accountId, member.principal.accountId)
            val me = api.request(server, "/v1/me", device, member).getJSONObject("principal")
            assertEquals(member.principal.accountId, me.getString("accountId"))
            val wrongDevice = runCatching { api.request(server, "/v1/me", UUID.randomUUID().toString(), member) }.exceptionOrNull()
            assertEquals(401, (wrongDevice as AccountRejected).status)
            val forbidden = runCatching { api.request(server, "/v1/admin/members", device, member) }.exceptionOrNull()
            assertEquals(403, (forbidden as AccountRejected).status)
            api.request(server, "/v1/auth/logout", device, member, JSONObject())
            val expired = runCatching { api.request(server, "/v1/me", device, member) }.exceptionOrNull()
            assertEquals(401, (expired as AccountRejected).status)
            assertEquals(admin.principal.accountId, api.request(server, "/v1/me", device, admin).getJSONObject("principal").getString("accountId"))
        } finally {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS) }
            // Only the exact freshly created fixture directory, never app/user data.
            directory.deleteRecursively()
        }
    }
}
