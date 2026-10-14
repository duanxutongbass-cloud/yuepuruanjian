package com.ensemble.scorelibrary.sync

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.withTransaction
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.sync.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Only the isolated test APK uses this adapter. It never imports a full-library archive. */
@Singleton
class HttpSyncStore @Inject constructor(@ApplicationContext private val context: Context, private val db: ScoreLibraryDatabase) {
    private val mutex = Mutex()
    private var core = SyncStateCore()
    private var session: SyncSession? = null
    @Volatile var login: TestHttpClient.Login? = null
        private set
    val device: String by lazy {
        val prefs = context.getSharedPreferences("http-test-device", Context.MODE_PRIVATE)
        prefs.getString("device", null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("device", it).commit()) }
    }
    private val client by lazy { TestHttpClient() }
    // In-memory session only: a new process or new device must log in online. No offline credential TTL is invented.
    suspend fun login(account: String, code: String): RemoteManifest = withContext(Dispatchers.IO) { mutex.withLock {
        login = null; session = null; core = SyncStateCore()
        val identity = client.login(account, code, device)
        val manifest = client.manifest(identity, device)
        ensureActive()
        val current = core.switchSession(SyncIdentity(identity.team + "/" + identity.account, device), identity.token)
        require(core.receive(current, manifest.core) == SyncDecision.ACCEPTED)
        reconcile(identity, manifest)
        login = identity; session = current
        manifest
    } }
    suspend fun logout() = mutex.withLock { login = null; session = null; core = SyncStateCore() }

    /** Apply authoritative revocations BEFORE resource IO. Do not roll them back on download failure. */
    private suspend fun reconcile(identity: TestHttpClient.Login, manifest: RemoteManifest) = db.withTransaction {
        val dao = db.libraryDao()
        val snapshot = db.resolutionDao().snapshot()
        val member = snapshot.members.find { it.id == identity.member }
        if (member == null) dao.insertMember(MemberEntity(id = identity.member, displayName = identity.name + " · " + identity.account.take(8)))
        val allowed = manifest.scores.map { it.part }.toSet()
        snapshot.extraPermissions.filter { it.memberId == identity.member }.forEach {
            dao.updateExtraPermission(it.copy(isArchived = it.piecePartId !in allowed))
        }
    }

    suspend fun refresh(): RemoteManifest = withContext(Dispatchers.IO) { mutex.withLock {
        val identity = checkNotNull(login)
        try {
            val manifest = client.manifest(identity, device)
            val decision = core.receive(checkNotNull(session), manifest.core)
            // Pending confirmation must never mask a newly received revocation.
            if (decision in setOf(SyncDecision.ACCEPTED, SyncDecision.DUPLICATE, SyncDecision.BLOCKED)) reconcile(identity, manifest)
            require(decision in setOf(SyncDecision.ACCEPTED, SyncDecision.DUPLICATE)) { "请先重试未完成的同步确认" }
            manifest
        } catch (rejected: TestHttpClient.Rejected) {
            if (rejected.status == 401 || rejected.status == 403) revoke(identity)
            throw rejected
        }
    } }

    private suspend fun revoke(identity: TestHttpClient.Login) {
        core.revoke(checkNotNull(session).identity)
        db.withTransaction {
            db.resolutionDao().extraPermissions().filter { it.memberId == identity.member }.forEach {
                db.libraryDao().updateExtraPermission(it.copy(isArchived = true))
            }
        }
        login = null
    }

    suspend fun download(manifest: RemoteManifest): Unit = withContext(Dispatchers.IO) { mutex.withLock {
        val identity = checkNotNull(login)
        val current = checkNotNull(session)
        require(core.view()?.manifest == manifest.core)
        try {
            // Fetch again: retry and stale UI cannot carry historical permissions into a new download.
            val latest = client.manifest(identity, device)
            reconcile(identity, latest)
            require(latest.core == manifest.core) { "授权清单已变化，请刷新后重试" }
            if (core.view()?.phase == SyncPhase.SERVICE_CONFIRMED) {
                client.authorize(identity, device, manifest)
                return@withLock
            }
            val command = core.next() ?: error("当前同步事务不可继续")
            if (command.token.action == SyncAction.SEND_RECEIPT) {
                sendReceipt(identity, manifest, command); return@withLock
            }
            require(command.token.action == SyncAction.VERIFY_RESOURCES)
            val directory = File(context.filesDir, "http-test/${identity.team}/${identity.account}/$device").apply { check(isDirectory || mkdirs()) }
            val files = mutableMapOf<String, File>()
            try {
                manifest.scores.forEach { score ->
                    ensureActive(); check(core.mayExecute(command.token))
                    val a = score.artifact
                    // Only local random paths, never remote names. Partial key is identity + immutable content hash.
                    val partial = File(directory, a.hash + ".partial")
                    client.download(identity, device, a, partial)
                    validatePdf(partial, a.pageCount)
                    val target = File(directory, UUID.randomUUID().toString() + ".pdf")
                    check(partial.renameTo(target))
                    files[a.artifactId] = target
                }
                val directoryFd = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
                try { android.system.Os.fsync(directoryFd) } finally { android.system.Os.close(directoryFd) }
                core.verified(command.token, manifest.core.artifacts)
                val commit = checkNotNull(core.next())
                check(commit.token.action == SyncAction.COMMIT_COLLECTION && core.mayExecute(commit.token))
                try {
                    client.authorize(identity, device, manifest)
                    ensureActive()
                    withContext(NonCancellable) {
                        install(identity, manifest, files)
                        core.committed(commit.token, SyncCommitResult.Committed(SyncCommit(manifest.core.operationId, manifest.core.artifacts)))
                    }
                } catch (failure: Exception) {
                    core.committed(commit.token, SyncCommitResult.NotCommitted)
                    // Reverify on the next user retry; no fake commit/readability evidence.
                    core = SyncStateCore()
                    session = core.switchSession(current.identity, identity.token)
                    check(core.receive(checkNotNull(session), manifest.core) == SyncDecision.ACCEPTED)
                    // A known rollback retries verification; no unresolved commit is discarded.
                    throw failure
                }
                val receipt = checkNotNull(core.next())
                sendReceipt(identity, manifest, receipt)
            } catch (failure: Exception) {
                if (core.mayExecute(command.token)) core.failed(command.token)
                throw failure
            } finally {
                // Referenced immutable files survive cancellation, receipt failure and process restart.
                val referenced = withContext(NonCancellable) { db.resolutionDao().versions().map { it.managedFilePath }.toSet() }
                files.values.filter { it.path !in referenced }.forEach { it.delete() }
            }
        } catch (rejected: TestHttpClient.Rejected) {
            if (rejected.status == 401 || rejected.status == 403) {
                try { reconcile(identity, client.manifest(identity, device)) } catch (_: Exception) { revoke(identity) }
            }
            throw rejected
        }
    } }
    private fun sendReceipt(identity: TestHttpClient.Login, manifest: RemoteManifest, command: SyncCommand) {
        try {
            client.json("/receipt", device, identity, org.json.JSONObject().put("revision", manifest.core.revision)
                .put("artifacts", manifest.json.getJSONArray("artifacts")))
            core.confirmed(command.token, true)
        } catch (failure: Exception) { core.confirmed(command.token, false); throw failure }
    }
    private fun validatePdf(file: File, expectedPages: Int) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
            require(pdf.pageCount == expectedPages)
            repeat(pdf.pageCount) { pdf.openPage(it).use { page -> require(page.width > 0 && page.height > 0) } }
        } }
    }
    private suspend fun install(identity: TestHttpClient.Login, manifest: RemoteManifest, files: Map<String, File>) = db.withTransaction {
        val dao = db.libraryDao()
        val old = db.resolutionDao().snapshot()
        manifest.scores.forEach { s ->
            val a = s.artifact
            if (old.definitions.none { it.id == s.definition }) dao.insertPartDefinition(PartDefinitionEntity(id = s.definition, name = s.partName + " · " + identity.account.take(8)))
            if (old.pieces.none { it.id == s.piece }) dao.insertPiece(PieceEntity(id = s.piece, title = s.title))
            if (old.parts.none { it.id == s.part }) dao.insertPiecePart(PiecePartEntity(id = s.part, pieceId = s.piece, partDefinitionId = s.definition, displayName = s.partName))
            val pdf = old.pdfs.find { it.id == s.pdf } ?: ScorePdfEntity(id = s.pdf, title = s.title).also { dao.insertScorePdf(it) }
            val existing = old.versions.find { it.id == a.sourceVersionId }
            if (existing == null) dao.insertScorePdfVersion(ScorePdfVersionEntity(id = a.sourceVersionId, scorePdfId = s.pdf,
                versionNumber = s.version, managedFilePath = files.getValue(a.artifactId).path,
                originalUri = "synthetic-artifact:${a.artifactId}", fileHash = a.hash, fileSizeBytes = a.size, pageCount = a.pageCount))
            else require(existing.scorePdfId == s.pdf && existing.fileHash == a.hash && existing.pageCount == a.pageCount &&
                existing.fileSizeBytes == a.size && File(existing.managedFilePath).isFile && TestHttpClient.hash(File(existing.managedFilePath)) == a.hash)
            if (old.bindings.none { it.piecePartId == s.part }) dao.insertPartScoreBinding(PartScoreBindingEntity(id = s.part, piecePartId = s.part, scorePdfId = s.pdf, startPage = 1, endPage = a.pageCount))
            else require(old.bindings.single { it.piecePartId == s.part }.let { it.scorePdfId == s.pdf && it.endPage == a.pageCount })
            dao.updateScorePdf(pdf.copy(activeVersionId = a.sourceVersionId))
            val permission = old.extraPermissions.find { it.memberId == identity.member && it.piecePartId == s.part }
            if (permission == null) dao.insertExtraPermission(MemberPiecePartPermissionEntity(id = UUID.nameUUIDFromBytes((identity.member + "/" + s.part).toByteArray()).toString(), memberId = identity.member, piecePartId = s.part))
            else dao.updateExtraPermission(permission.copy(isArchived = false))
            if (old.caches.none { it.memberId == identity.member && it.scorePdfVersionId == a.sourceVersionId })
                dao.insertCache(ScoreCacheEntryEntity(id = UUID.nameUUIDFromBytes((identity.member + "/" + a.sourceVersionId).toByteArray()).toString(), scorePdfVersionId = a.sourceVersionId, memberId = identity.member,
                    status = CacheStatus.CACHED, cachedFilePath = existing?.managedFilePath ?: files.getValue(a.artifactId).path, cachedAtEpochMillis = System.currentTimeMillis()))
        }
        manifest.scores.firstOrNull()?.let { score ->
            val member = db.resolutionDao().members().single { it.id == identity.member }
            dao.updateMember(member.copy(defaultPartDefinitionId = score.definition))
        }
    }
}
