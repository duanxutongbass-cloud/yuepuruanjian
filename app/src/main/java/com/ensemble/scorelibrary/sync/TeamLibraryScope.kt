package com.ensemble.scorelibrary.sync

import android.content.Context
import androidx.room.Room
import com.ensemble.scorelibrary.account.TeamSession
import com.ensemble.scorelibrary.account.RuntimeLease
import com.ensemble.scorelibrary.account.CLOCK_SKEW_SECONDS
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.domain.LibrarySnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal fun teamId(value: String) = UUID.nameUUIDFromBytes(("team-library-v1/" + value).toByteArray(Charsets.UTF_8)).toString()

class TeamCollection internal constructor(val key: String, val member: String, val database: ScoreLibraryDatabase,
    val directory: File, expiresAt: Long, var session: TeamSession? = null) {
    var expiresAt: Long = expiresAt
        private set
    private var seenAt = System.currentTimeMillis() / 1000
    private var runtime = RuntimeLease(expiresAt, seenAt, android.os.SystemClock.elapsedRealtime())
    @Synchronized internal fun authorizeUntil(deadline: Long) {
        expiresAt = deadline
        seenAt = System.currentTimeMillis() / 1000
        runtime = RuntimeLease(deadline, seenAt, android.os.SystemClock.elapsedRealtime())
    }
    @Synchronized internal fun valid(): Boolean {
        val now = System.currentTimeMillis() / 1000
        if (now < seenAt - CLOCK_SKEW_SECONDS || !runtime.valid(now, android.os.SystemClock.elapsedRealtime())) return false
        seenAt = maxOf(seenAt, now)
        return true
    }
    var revision: Long = -1
        internal set
    @Volatile internal var authorizationChanging = false
    @Volatile internal var deletionChanging = false
    internal var authorizationGeneration = 0L
}

/** Separate account databases and private files; never import team material into the local mother library. */
@Singleton
class TeamLibraryScope @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val root=context.noBackupFilesDir.canonicalFile
    private val databases = mutableMapOf<String, ScoreLibraryDatabase>()
    private val mutable = MutableStateFlow<TeamCollection?>(null)
    val active = mutable.asStateFlow()
    private val mutableReadEpoch=MutableStateFlow(0L)
    internal val readEpoch=mutableReadEpoch.asStateFlow()

    @Synchronized fun activate(session: TeamSession): TeamCollection = openManaged(session).also { mutable.value=it }
    /** Prepare an identified managed database without making it readable to UI/repositories. */
    @Synchronized internal fun openManaged(session: TeamSession): TeamCollection {
        val p = session.principal
        if (!p.joined) {
            mutable.value = null
            error("未通过入团审核，不能激活团队曲库")
        }
        val key = teamId(session.server + "/" + p.teamId + "/" + p.accountId)
        val db = databases.getOrPut(key) {
            val path = File(root, "team-libraries/$key/library.db")
            check(path.parentFile!!.isDirectory || path.parentFile!!.mkdirs())
            Room.databaseBuilder(context, ScoreLibraryDatabase::class.java, path.absolutePath)
                .addMigrations(ScoreLibraryDatabase.MIGRATION_1_2, ScoreLibraryDatabase.MIGRATION_2_3,
                    ScoreLibraryDatabase.MIGRATION_3_4, ScoreLibraryDatabase.MIGRATION_4_5, ScoreLibraryDatabase.MIGRATION_5_6).build()
        }
        val files = File(root, "team-libraries/$key/scores").apply { check(isDirectory || mkdirs()) }
        return TeamCollection(key, teamId("$key/member/${p.memberId}"), db, files, session.expiresAt, session)
    }
    @Synchronized internal fun publish(collection:TeamCollection) { check(!collection.deletionChanging);mutable.value=collection }
    @Synchronized internal fun beginDeletion(collection:TeamCollection) {
        requireActive(collection);collection.deletionChanging=true;collection.authorizationGeneration++;mutableReadEpoch.value++
    }
    @Synchronized internal fun endDeletion(collection:TeamCollection) {
        requireActive(collection);collection.authorizationGeneration++;collection.deletionChanging=false;mutableReadEpoch.value++
    }
    @Synchronized fun deactivate() { mutable.value = null }
    fun requireActive(collection: TeamCollection) { check(mutable.value === collection) { "团队账号已切换" } }
    @Synchronized internal fun preparationStamp(collection: TeamCollection): Long {
        requireCurrent(collection)
        check(!collection.authorizationChanging) { "团队授权正在更新，请稍后重新准备演出" }
        return collection.authorizationGeneration
    }
    @Synchronized internal fun enterPrepared(collection: TeamCollection, stamp: Long, block: () -> Unit) {
        check(preparationStamp(collection) == stamp) { "准备期间授权已变更，请重新核验演出" }
        block()
    }
    @Synchronized internal fun beginAuthorizationChange(collection: TeamCollection) {
        requireActive(collection); collection.authorizationChanging = true; collection.authorizationGeneration++
    }
    @Synchronized internal fun endAuthorizationChange(collection: TeamCollection) {
        collection.authorizationGeneration++; collection.authorizationChanging = false
    }
    fun requireCurrent(collection: TeamCollection) {
        requireActive(collection)
        check(!collection.deletionChanging) { "正在清理已彻底删除的乐谱，请稍后重新打开" }
        if (!collection.valid()) throw com.ensemble.scorelibrary.account.OfflineUnavailable("离线核验期限已到或设备时间变化，请联网重新核验")
    }
    fun databaseFor(member: String, local: ScoreLibraryDatabase): ScoreLibraryDatabase {
        val collection = active.value ?: return local
        requireCurrent(collection)
        check(member == collection.member) { "不能读取其他成员的团队曲库" }
        return collection.database
    }
    /** Hide old/revoked metadata as well as denying its PDF bindings. */
    fun project(collection: TeamCollection, data: LibrarySnapshot,
        frozen:com.ensemble.scorelibrary.core.database.entity.AnnotationConcertSnapshotEntity?=null): LibrarySnapshot {
        requireCurrent(collection)
        val parts = data.extraPermissions.filter { it.memberId == collection.member && !it.isArchived }.map { it.piecePartId }.toSet()
        val pieces = data.parts.filter { it.id in parts && !it.isArchived }.map { it.pieceId }.toSet()
        val projected=data.copy(pieces = data.pieces.filter { it.id in pieces }, members = data.members.filter { it.id == collection.member })
        return if(frozen!=null && TeamProgramSnapshot.isTeam(frozen)) TeamProgramSnapshot.project(projected,frozen,collection.member) else projected
    }
}
