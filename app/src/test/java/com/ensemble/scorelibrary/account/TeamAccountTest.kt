package com.ensemble.scorelibrary.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1200dp-mdpi")
class TeamAccountTest {
    @get:Rule val compose = createComposeRule()
    private fun screen(state: TeamAccountState = TeamAccountState(), login: (String, String, String?, String?, Boolean) -> Unit = { _, _, _, _, _ -> }) {
        compose.setContent { MaterialTheme { TeamAccountScreen(state, login, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {}) } }
    }
    @Test fun productionAddressesRejectCleartextCredentialAndQueryUrls() {
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com?token=secret",
            "https://example.com#fragment", "https://example.com/../x", "https://example.com:0", "https:///missing").forEach { value ->
            assertTrue(value, runCatching { accountServer(value, false) }.isFailure)
        }
        assertEquals("https://example.com/api", accountServer(" https://example.com/api/ ", false))
        assertTrue(runCatching { accountServer("http://127.0.0.1:8787", false) }.isFailure)
        assertEquals("http://127.0.0.1:8787", accountServer("http://127.0.0.1:8787", true))
    }
    @Test fun encryptedSessionIsAuthenticatedAndDeletionRemovesCredential() {
        val dir = File(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, UUID.randomUUID().toString())
        assertTrue(dir.mkdir())
        val file = File(dir, "session")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val vault = AccountVault(file) { key }
        val secret = "token-synthetic-session-value"
        vault.write(secret)
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        assertEquals(secret, AccountVault(file) { key }.read())
        val previous = file.readBytes()
        vault.write(secret)
        assertFalse(previous.contentEquals(file.readBytes()))
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        assertTrue(runCatching { vault.read() }.isFailure)
        vault.clear()
        assertNull(vault.read())
        assertTrue(dir.delete())
    }
    @Test fun registrationRequiresMatchingPasswordAndExplicitNoticeConfirmation() {
        var submitted = false
        screen(login = { _, password, name, invite, consent ->
            assertEquals("synthetic password", password)
            assertEquals("Violinist", name)
            assertEquals("synthetic-invitation", invite)
            assertTrue(consent)
            submitted = true
        })
        compose.onNodeWithText("邀请注册").performClick()
        compose.onNodeWithTag("team-server").assertDoesNotExist()
        compose.onNodeWithTag("team-username").performTextInput("violinist")
        compose.onNodeWithTag("team-password").performTextInput("synthetic password")
        compose.onNodeWithTag("team-password-repeat").performTextInput("different password")
        compose.onNodeWithTag("team-name").performTextInput("Violinist")
        compose.onNodeWithTag("team-invitation").performTextInput("synthetic-invitation")
        compose.onNodeWithTag("team-submit").assertIsNotEnabled()
        compose.onNodeWithTag("team-password-repeat").performTextReplacement("synthetic password")
        compose.onNodeWithTag("team-submit").assertIsNotEnabled()
        compose.onNodeWithTag("team-consent").performClick()
        compose.onNodeWithTag("team-submit").assertIsEnabled().performClick()
        assertTrue(submitted)
        compose.onNodeWithTag("team-password").assertTextEquals("密码（12–128 个字符）", "")
        compose.onNodeWithTag("team-invitation").assertTextEquals("管理员提供的邀请码", "")
    }
    @Test fun memberScreenDoesNotExposeAdministratorActions() {
        screen(TeamAccountState(principal = TeamPrincipal("a", "m", "t", "Synthetic team", "violinist", "Violinist", "member", true)))
        compose.onNodeWithTag("team-create-invite").assertDoesNotExist()
        compose.onNodeWithTag("team-logout").assertExists()
    }
    @Test fun loginNeedsOnlyAccountAndPasswordAndClearsPasswordAfterSubmit() {
        var submitted = false
        screen(login = { username, password, name, invite, consent ->
            assertEquals("admin", username)
            assertEquals("synthetic password", password)
            assertNull(name)
            assertNull(invite)
            assertFalse(consent)
            submitted = true
        })
        compose.onNodeWithTag("team-server").assertDoesNotExist()
        compose.onNodeWithTag("team-submit").assertIsNotEnabled()
        compose.onNodeWithTag("team-username").performTextInput("admin")
        compose.onNodeWithTag("team-password").performTextInput("synthetic password")
        compose.onNodeWithTag("team-submit").assertIsEnabled().performClick()
        assertTrue(submitted)
        compose.onNodeWithTag("team-password").assertTextEquals("密码（12–128 个字符）", "")
    }
    @Test fun installedServiceIgnoresLegacyAddressAndRejectsOldLoginDestination() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("team-account-settings", android.content.Context.MODE_PRIVATE)
        try {
            preferences.edit().clear().commit()
            assertEquals("https://124355.xyz:18443", TeamAccountStore(context).server)
            preferences.edit().putString("server", "http://127.0.0.1:8787").commit()
            val store = TeamAccountStore(context)
            assertEquals("https://124355.xyz:18443", store.server)
            assertEquals(store.server, accountServer(store.server, false))
            assertTrue(runCatching { store.signIn("https://old.example", "admin", "synthetic password", null, null, false) }.isFailure)
        } finally { preferences.edit().clear().commit() }
    }
    @Test fun endpointMigrationKeepsMatchingSessionAndDeletesOldOrInvalidSession() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.cacheDir, UUID.randomUUID().toString()).apply { check(mkdir()) }
        val file = File(dir, "session")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val vault = AccountVault(file) { key }
        try {
            val remembered = JSONObject().put("server", TEAM_SERVICE_URL).put("token", "synthetic remembered token")
            vault.write(remembered.toString())
            assertEquals(remembered.getString("token"), vault.readForServer(TEAM_SERVICE_URL)?.getString("token"))
            assertTrue(file.exists())
            listOf("https://old.example", "http://124355.xyz:8787", "https://124355.xyz:8787", "", "not an address").forEach { old ->
                vault.write(remembered.put("server", old).toString())
                assertNull(vault.readForServer(TEAM_SERVICE_URL))
                assertFalse(file.exists())
            }
            vault.write("malformed json")
            assertNull(vault.readForServer(TEAM_SERVICE_URL))
            assertFalse(file.exists())
        } finally { vault.clear(); assertTrue(dir.delete()) }
    }
    @Test fun malformedPrincipalAndSecretsCannotBecomeDisplayState() {
        val json = JSONObject().put("accountId", "not-a-uuid")
        assertTrue(runCatching { TeamPrincipal.parse(json) }.isFailure)
        val session = TeamSession("https://example.com", "should-never-be-logged", 10,
            TeamPrincipal("a", "m", "t", "team", "user", "User", "member", false))
        assertFalse(session.toString().contains(session.token))
    }
}
