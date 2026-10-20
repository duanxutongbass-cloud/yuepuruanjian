package com.ensemble.scorelibrary.sync

import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TeamDeletionJournalTest {
    @get:Rule val tmp = TemporaryFolder()
    private val team = "11111111-1111-4111-8111-111111111111"
    private val account = "22222222-2222-4222-8222-222222222222"
    private val storage = "33333333-3333-4333-8333-333333333333"
    private val request = "44444444-4444-4444-8444-444444444444"
    private val artifact = "55555555-5555-4555-8555-555555555555"
    private val secret = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private fun journal(server: String = "https://scores.example", teamId: String = team) = TeamDeletionJournal(tmp.root, server, teamId) { secret }
    private fun page(storageId: String = storage, id: String = artifact): TeamDeletionPage {
        val row = JSONObject("""{"protocolVersion":1,"storageId":"$storageId","teamId":"$team","accountId":"$account",
            "after":"0","through":"1","nextAfter":"1","more":false,
            "deletions":[{"sequence":"1","artifactId":"$id","requestId":"$request","deletedAt":1792350000}],
            "jobs":[{"requestId":"$request","programIds":[],"freezeIds":[],"pieceReferences":[]}]}""")
        return TeamDeletionPage.parse(row, "https://scores.example", team, account, null, 0)
    }
    @Test fun persistedInboxRetiresImmediatelyButCursorWaitsForPhysicalCleanupAcknowledgement() {
        val a = journal();val p = page();val staged = a.stage(p, account)
        assertEquals(0L, staged.cursor);assertEquals(setOf(artifact), staged.retired)
        val restarted = journal().read();assertEquals(staged, restarted);assertEquals(p, restarted.pending)
        assertEquals(staged, journal().stage(p, account))
        val done = journal().acknowledge(p);assertEquals(1L, done.cursor);assertNull(done.pending)
        assertEquals(setOf(artifact), done.retired);assertEquals(done, journal().acknowledge(p))
        assertThrows(Exception::class.java) { journal().stage(p, account) }
    }
    @Test fun namespacesAndVerifiedPageIdentityPreventCrossServerTeamAndAccountDeletion() {
        journal().stage(page(), account)
        assertTrue(journal("https://another.example").read().retired.isEmpty())
        assertThrows(Exception::class.java) { journal("https://another.example").stage(page(), account) }
        assertTrue(journal(teamId = account).read().retired.isEmpty())
        assertThrows(Exception::class.java) { journal("https://another.example", account).stage(page(), account) }
        assertThrows(Exception::class.java) { journal().stage(page(), team) }
        assertEquals(0L, journal().read().cursor)
    }
    @Test fun changedStorageCannotDiscardPendingCleanupAndRebindingKeepsPreviouslyRetiredIds() {
        val first = page();journal().stage(first, account)
        val next = page(account, request)
        assertThrows(Exception::class.java) { journal().stage(next, account, allowRebind = true) }
        journal().acknowledge(first)
        assertThrows(Exception::class.java) { journal().stage(next, account) }
        val rebound = journal().stage(next, account, allowRebind = true)
        assertEquals(0L, rebound.cursor);assertEquals(setOf(artifact, request), rebound.retired)
        assertEquals(next, journal().read().pending)
    }
    @Test fun mismatchedAcknowledgementCannotAdvanceOrForgetSavedDeletion() {
        val p = page();journal().stage(p, account)
        assertThrows(Exception::class.java) { journal().acknowledge(page(id = request)) }
        assertEquals(p, journal().read().pending);assertEquals(0L, journal().read().cursor)
    }
    @Test fun corruptOrWrongKeyVaultFailsClosedAndOriginalCheckpointIsPreserved() {
        val p = page();journal().stage(p, account)
        val vault = tmp.root.walkTopDown().single { it.name == "retirement.enc" };val original = vault.readBytes()
        val wrong = TeamDeletionJournal(tmp.root, "https://scores.example", team) { SecretKeySpec(ByteArray(32) { 3 }, "AES") }
        assertThrows(Exception::class.java) { wrong.read() };assertArrayEquals(original, vault.readBytes())
        val changed = original.copyOf();changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte();vault.writeBytes(changed)
        assertThrows(Exception::class.java) { journal().read() };assertArrayEquals(changed, vault.readBytes())
        vault.writeBytes(original);assertEquals(p, journal().read().pending)
        assertFalse(String(original, Charsets.ISO_8859_1).contains(artifact))
    }
    @Test fun lostKeyDuringWriteNeverAcknowledgesOrClearsExistingInbox() {
        val p = page();journal().stage(p, account)
        var calls = 0
        val broken = TeamDeletionJournal(tmp.root, "https://scores.example", team) {
            if (++calls == 2) throw IllegalStateException("Injected unavailable key during persistence")
            secret
        }
        assertThrows(Exception::class.java) { broken.acknowledge(p) }
        assertEquals(0L, journal().read().cursor);assertEquals(p, journal().read().pending)
    }
}
