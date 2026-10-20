package com.ensemble.scorelibrary.account

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.net.ssl.SSLHandshakeException

@RunWith(RobolectricTestRunner::class)
class VerifiedSessionMemoryTest {
    private val principal = TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        "Synthetic ensemble", "violinist", "Violinist", "member", true)
    private val device = UUID.randomUUID().toString()
    private var now = 1_800_000_000L
    private fun fixture(block: (VerifiedSessionMemory, AccountVault, File) -> Unit) {
        val root = java.nio.file.Files.createTempDirectory("verified-session-").toFile()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val vault = AccountVault(File(root, "session")) { key }
        try { block(VerifiedSessionMemory(vault, TEAM_SERVICE_URL, device) { now }, vault, root) }
        finally { root.deleteRecursively() }
    }
    private fun session() = TeamSession(TEAM_SERVICE_URL, "a".repeat(43), now + OFFLINE_SECONDS, principal)
    private fun response(session: TeamSession) = JSONObject().put("principal", session.principal.json()).put("expiresAt", now + OFFLINE_SECONDS)
    @Test fun encryptedVerifiedSessionRestoresOfflineWithoutExtendingDeadlineAndReconnectPersistsRenewal() = fixture { memory, vault, root ->
        val original = memory.confirm(session()).also(memory::save)
        now += 2 * 86400
        val offline = memory.verify(checkNotNull(memory.read())) { throw IOException("no connection") }
        assertTrue(offline.offline)
        assertEquals(original.verifiedAt, offline.verifiedAt)
        assertEquals(original.expiresAt, offline.expiresAt)
        assertEquals(now, memory.read()!!.lastSeenAt)
        assertFalse(File(root, "session").readText(Charsets.ISO_8859_1).contains(original.token))
        val updated = memory.verify(offline) { response(it.copy(principal = principal.copy(name = "Updated"))) }
        assertFalse(updated.offline)
        assertEquals(now + OFFLINE_SECONDS, updated.expiresAt)
        assertEquals(updated, memory.read())
        assertEquals("Updated", memory.read()!!.principal.name)
        assertNotNull(vault.read())
    }
    @Test fun expiredOrRevokedCredentialCannotFallBackToCachedIdentity() = fixture { memory, vault, _ ->
        val original = memory.confirm(session()).also(memory::save)
        assertTrue(runCatching { memory.verify(original) { throw AccountRejected(401, "session_invalid") } }.isFailure)
        assertNull(vault.read())
        memory.save(original)
        now = original.expiresAt
        var called = false
        assertTrue(runCatching { memory.verify(original) { called = true; response(it) } }.isFailure)
        assertFalse(called)
        assertNull(vault.read())
    }
    @Test fun legacyRememberedTokenNeedsOnlineVerificationAndDeviceBinding() = fixture { memory, vault, _ ->
        val original = session()
        vault.write(JSONObject().put("server", TEAM_SERVICE_URL).put("token", original.token).put("expiresAt", original.expiresAt)
            .put("principal", principal.json()).toString())
        val old = memory.read()!!
        assertTrue(runCatching { memory.verify(old) { throw IOException("offline") } }.exceptionOrNull() is OfflineUnavailable)
        val verified = memory.verify(old) { response(it) }
        assertEquals(device, verified.deviceId)
        val wrongDevice = VerifiedSessionMemory(vault, TEAM_SERVICE_URL, UUID.randomUUID().toString()) { now }
        assertTrue(runCatching { wrongDevice.read() }.isFailure)
        assertNull(vault.read())
    }
    @Test fun rollbackTlsFailureAndIdentityMismatchNeverCreateNewOfflineLease() = fixture { memory, _, _ ->
        val original = memory.confirm(session()).also(memory::save)
        now += 86400
        val offline = memory.verify(original) { throw IOException("offline") }
        now -= 3600
        assertTrue(runCatching { memory.verify(offline) { throw IOException("offline") } }.exceptionOrNull() is OfflineUnavailable)
        now += 3600
        assertTrue(runCatching { memory.verify(offline) { throw SSLHandshakeException("untrusted certificate") } }.exceptionOrNull() is SSLHandshakeException)
        assertTrue(runCatching { memory.verify(offline) { response(it.copy(principal = principal.copy(accountId = UUID.randomUUID().toString()))) } }.isFailure)
        assertEquals(original.verifiedAt, memory.read()!!.verifiedAt)
    }
}
