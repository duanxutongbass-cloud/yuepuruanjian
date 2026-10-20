package com.ensemble.scorelibrary.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Ciphertext and the device ID live in noBackupFilesDir, never in Android/cloud backup. */
internal class AccountVault(private val file: File, private val maximum: Int = 8192, private val key: () -> SecretKey) {
    fun read(): String? {
        if (!file.exists()) return null
        require(file.length() in 30..(maximum.toLong() + 29))
        val bytes = file.readBytes()
        require(bytes.size in 30..(maximum + 29) && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
    }
    fun write(value: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= maximum)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val temporary = File.createTempFile(".session-", ".pending", file.parentFile)
        try {
            java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    /** Never send a remembered token to an old endpoint or move it to a new origin. */
    fun readForServer(server: String): JSONObject? {
        val json = try { read()?.let(::JSONObject) } catch (_: Exception) { clear(); null } ?: return null
        if (runCatching { accountServer(json.getString("server")) }.getOrNull() != server) {
            clear()
            return null
        }
        return json
    }
    fun clear() { check(!file.exists() || file.delete()) { "无法清除本机登录凭据" } }
}

interface TeamAccountGateway {
    val server: String
    val device: String
    fun restore(): TeamSession?
    fun verify(session: TeamSession): TeamSession = session
    fun signIn(server: String, username: String, password: String, name: String? = null,
        invitation: String? = null, consent: Boolean = false): TeamSession
    fun signUp(username: String, password: String, name: String, consent: Boolean): TeamSession =
        throw AccountRejected(404, "registration_unavailable")
    fun request(session: TeamSession, path: String, body: JSONObject? = null): JSONObject
    fun switchTeam(session:TeamSession,teamId:String):TeamSession = throw AccountRejected(404,"multi_team_unavailable")
    fun clear()
}

@Singleton
class TeamAccountStore @Inject constructor(@ApplicationContext context: Context) : TeamAccountGateway {
    override val server: String = TEAM_SERVICE_URL
    private val root = File(context.noBackupFilesDir, "team-account").apply { check(isDirectory || mkdirs()) }
    override val device: String by lazy {
        val file = AtomicFile(File(root, "device-id"))
        if (file.baseFile.exists()) file.readFully().toString(Charsets.UTF_8).also { UUID.fromString(it) }
        else UUID.randomUUID().toString().also { value ->
            val out = file.startWrite()
            try { out.write(value.toByteArray()); file.finishWrite(out) }
            catch (error: Exception) { file.failWrite(out); throw error }
        }
    }
    private val api = TeamAccountApi()
    private val vault = AccountVault(File(root, "session.enc")) { teamSecretKey("team-session-v1") }
    private val memory by lazy { VerifiedSessionMemory(vault, server, device) }
    override fun restore(): TeamSession? {
        val saved = memory.read() ?: return null
        return verify(saved)
    }
    override fun verify(session: TeamSession) = memory.verify(session) { api.request(it.server, "/v1/me", device, it) }
    override fun signIn(server: String, username: String, password: String, name: String?, invitation: String?, consent: Boolean): TeamSession {
        require(accountServer(server) == this.server) { "团队服务地址已更新，请重新登录" }
        return remember(api.login(server, device, deviceName(), username, password, name, invitation, consent))
    }
    override fun signUp(username: String, password: String, name: String, consent: Boolean): TeamSession =
        remember(api.signup(server, device, deviceName(), username, password, name, consent))
    private fun deviceName() = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).take(80)
    private fun remember(value: TeamSession): TeamSession {
        val session = memory.confirm(value)
        try {
            memory.save(session)
        } catch (error: Exception) {
            runCatching { request(session, "/v1/auth/logout", JSONObject()) }
            clear()
            throw error
        }
        return session
    }
    override fun request(session: TeamSession, path: String, body: JSONObject?): JSONObject {
        if (session.offline) throw OfflineUnavailable("当前离线，恢复联网核验后再进行团队操作")
        return api.request(session.server, path, device, session, body)
    }
    override fun switchTeam(session:TeamSession,teamId:String):TeamSession {
        require(session.principal.multiTeamProtocol==1 && !session.offline)
        val result=request(session,"/v1/me/teams/switch",JSONObject().put("teamId",teamId))
        val principal=TeamPrincipal.parse(result.getJSONObject("principal"))
        require(principal.accountId==session.principal.accountId && principal.memberId==session.principal.memberId &&
            principal.teamId==teamId && principal.multiTeamProtocol==1) { "切换后的团队身份不匹配" }
        val token=result.getString("token").also { require(it.matches(Regex("[A-Za-z0-9_-]{32,128}")) && it!=session.token) }
        return remember(TeamSession(server,token,result.getLong("expiresAt"),principal))
    }
    override fun clear() { vault.clear() }
}

internal fun teamSecretKey(alias: String): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generateKey()
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class TeamAccountModule {
    @dagger.Binds abstract fun gateway(store: TeamAccountStore): TeamAccountGateway
}
