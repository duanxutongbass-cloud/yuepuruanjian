package com.ensemble.scorelibrary.reading

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class ReadingTeamIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val team = TeamLibraryScope(context)
    private val storage get() = ReadingActivityStore(temporary.root) { SecretKeySpec(ByteArray(32) { 19 }, "AES") }
    private val activity by lazy { ReadingActivity(storage, team) }
    private val collections = mutableListOf<TeamCollection>()
    private fun session() = TeamSession(TEAM_SERVICE_URL, "synthetic-session", Long.MAX_VALUE,
        TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "Synthetic team", "synthetic", "Synthetic member", "admin", true))
    private fun activate(s: TeamSession) = team.activate(s).also { collections += it }
    @After fun cleanup() {
        team.deactivate()
        collections.distinctBy { it.database }.forEach { it.database.close() }
        val root = File(context.noBackupFilesDir, "team-libraries").canonicalFile
        collections.map { it.directory.parentFile!!.canonicalFile }.distinct().forEach { dir ->
            check(dir.parentFile == root && dir.name.matches(Regex("[a-f0-9-]{36}")))
            dir.deleteRecursively()
        }
    }
    @Test fun differentTeamAccountsAndLocalFallbackNeverShareFavorites() = runBlocking {
        val a = activate(session()); val identity = activity.identity(a.member)
        activity.favorite(identity, "private-a", true)
        val b = activate(session())
        assertTrue(activity.read(activity.identity(b.member)).favorites.isEmpty())
        try { activity.favorite(identity, "late", true); fail() } catch (_: IllegalStateException) { }
        team.deactivate()
        assertEquals("local", activity.identity(a.member).owner.space)
        assertTrue(activity.read(activity.identity(a.member)).favorites.isEmpty())
        assertEquals(setOf("private-a"), storage.read(identity.owner).favorites)
    }
    @Test fun renewingSameAccountKeepsOwnerButRejectsPreviousCollectionHandle() = runBlocking {
        val session = session(); val first = activate(session); val old = activity.identity(first.member)
        activity.favorite(old, "p", true)
        val renewed = activate(session.copy(token = "renewed-session"))
        val fresh = activity.identity(renewed.member)
        assertEquals(old.owner, fresh.owner)
        assertEquals(setOf("p"), activity.read(fresh).favorites)
        try { activity.read(old); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun expiredTeamLeaseAndAdminRoleCannotReadAnotherMembersLocalRecords() = runBlocking {
        val collection = activate(session()); val identity = activity.identity(collection.member)
        activity.favorite(identity, "p", true)
        try { activity.identity(UUID.randomUUID().toString()); fail() } catch (_: IllegalStateException) { }
        collection.authorizeUntil(System.currentTimeMillis() / 1000 - 1)
        try { activity.read(identity); fail() } catch (_: OfflineUnavailable) { }
        assertEquals(setOf("p"), storage.read(identity.owner).favorites)
    }
}
