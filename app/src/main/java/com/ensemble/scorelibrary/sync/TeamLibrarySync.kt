package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.domain.ScoreNumber

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.withTransaction
import com.ensemble.scorelibrary.account.TeamSession
import com.ensemble.scorelibrary.account.OfflineUnavailable
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.CacheStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

interface TeamPdfVerifier {
    fun verify(file: File, pages: Int)
    fun verify(file:File,pages:Int,check:()->Unit) { check();verify(file,pages);check() }
}
class AndroidTeamPdfVerifier @Inject constructor() : TeamPdfVerifier {
    override fun verify(file: File, pages: Int)=verify(file,pages) {}
    override fun verify(file:File,pages:Int,check:()->Unit) {
        try {
            check()
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
                check(pdf.pageCount == pages)
                repeat(pdf.pageCount) { check();pdf.openPage(it).use { page -> check(page.width > 0 && page.height > 0) } }
            } }
        } catch (error: Exception) {
            if(error is TeamDownloadsPaused || error is CancellationException || error is OfflineUnavailable)throw error
            throw TeamSyncFailure("PDF 损坏、加密或页数不符，未加入曲库", error)
        }
    }
}
interface TeamScoreSync {
    val offlineDeadline: Long? get() = null
    val downloads:kotlinx.coroutines.flow.StateFlow<TeamDownloadState>? get()=null
    fun pauseDownloads(expectedKey:String?=null) {}
    fun resumeDownloads(expectedKey:String?=null) {}
    suspend fun activate(session: TeamSession): String
    suspend fun sync(session: TeamSession, progress: (Int, Int) -> Unit): Int
    fun deactivate()
}

@javax.inject.Singleton
class TeamLibrarySync @Inject constructor(private val scope: TeamLibraryScope, private val client: TeamScoreTransport,
    private val verifier: TeamPdfVerifier, private val ink: TeamInkSync? = null, private val offline: TeamOfflineStore? = null,
    private val queue:TeamDownloads=TeamDownloads(scope),private val folders:TeamFolderSync?=null,
    private val deletions:TeamDeletionCoordinator?=null) : TeamScoreSync {
    private val mutex = Mutex()
    override val offlineDeadline: Long? get() = scope.active.value?.expiresAt
    override val downloads get()=queue.state
    override fun pauseDownloads(expectedKey:String?)=queue.pause(expectedKey)
    override fun resumeDownloads(expectedKey:String?)=queue.resume(expectedKey)
    override suspend fun activate(session: TeamSession): String = withContext(Dispatchers.IO) { mutex.withLock {
        if (!session.principal.joined) { deactivate(); error("未通过入团审核，不能激活团队曲库") }
        scope.deactivate()
        val collection = scope.openManaged(session)
        try {
            deletions?.activate(collection)
            scope.publish(collection)
            queue.bind(collection)
            // Reopening a process does not reuse remembered grants as fresh server authorization.
            reconcile(collection, TeamCatalog(0, emptyList()))
            val dao = collection.database.libraryDao()
            if (collection.database.resolutionDao().members().none { it.id == collection.member })
                dao.insertMember(MemberEntity(id = collection.member, displayName = session.principal.name))
            if (offline != null && session.verifiedAt > 0) {
                val remembered = try { offline.read(session) } catch (error: OfflineUnavailable) {
                    if (session.offline) throw error else null
                }
                if (remembered != null) {
                    val old = collection.database.resolutionDao().snapshot()
                    val files = remembered.catalog.artifacts.mapNotNull { a ->
                        val v = old.versions.singleOrNull { it.id == a.version && it.scorePdfId == a.pdf && it.fileHash == a.hash &&
                            it.fileSizeBytes == a.size && it.pageCount == a.pages && it.originalUri == "team-artifact:${a.id}" }
                        val file = v?.managedFilePath?.let(::File)?.takeIf { it.isFile &&
                            it.canonicalFile.parentFile == collection.directory.canonicalFile && it.length() == a.size && hash(it) == a.hash }
                        file?.let { a.id to it }
                    }.toMap()
                    val artifacts = remembered.catalog.artifacts.filter { it.id in files }
                    val keys = artifacts.map { it.pieceKey }.toSet()
                    val available=remembered.catalog.copy(artifacts=artifacts)
                    val programs=remembered.catalog.programs.filter { p ->
                        keys.containsAll(p.pieceKeys) && (p.freezeId==null ||
                            collection.database.annotationDao().snapshot(p.concert)?.let {
                                TeamProgramSnapshot.matches(it,p,available,collection.member)
                            }==true)
                    }
                    val catalog = available.copy(programs=programs)
                    install(collection, session, catalog, files)
                    collection.revision = catalog.revision
                    collection.authorizeUntil(remembered.lease.expiresAt)
                }
            } else if (session.offline) throw OfflineUnavailable()
            val referenced = collection.database.resolutionDao().versions().map { it.managedFilePath }.toSet()
            collection.directory.listFiles().orEmpty().filter { file ->
                file.isFile && file.canonicalFile.parentFile == collection.directory.canonicalFile && file.path !in referenced &&
                    (file.name.matches(Regex("[a-f0-9-]{36}\\.pdf")) || file.name.matches(Regex("download-[a-zA-Z0-9-]+\\.pending")))
            }.forEach { it.delete() }
            collection.member
        } catch (error: Exception) { queue.detach();scope.deactivate(); throw error }
    } }
    override fun deactivate() { queue.detach();scope.deactivate() }
    private fun current(session: TeamSession): TeamCollection {
        if (!session.principal.joined) { deactivate(); error("未通过入团审核") }
        val collection = checkNotNull(scope.active.value)
        scope.requireActive(collection)
        if (session.offline) scope.requireCurrent(collection)
        check(collection.key == teamId(session.server + "/" + session.principal.teamId + "/" + session.principal.accountId))
        return collection
    }
    override suspend fun sync(session: TeamSession, progress: (Int, Int) -> Unit): Int = withContext(Dispatchers.IO) { mutex.withLock {
        val collection = current(session)
        collection.session = session
        if (session.offline) {
            val remembered = offline?.read(session) ?: throw OfflineUnavailable()
            if (remembered.lease.expiresAt < collection.expiresAt) collection.authorizeUntil(remembered.lease.expiresAt)
            folders?.schedule(collection)
            return@withLock collection.database.resolutionDao().snapshot().extraPermissions.count { it.memberId == collection.member && !it.isArchived }
        }
        deletions?.refresh(collection)
        val catalog = client.catalog(session)
        scope.requireActive(collection)
        check(catalog.revision >= collection.revision) { "服务授权版本回退，请重新登录后重试" }
        // Revocations survive network failure, cancellation and failed PDF verification.
        checkpoint(collection, session, catalog)
        collection.revision = catalog.revision
        // Folder IO is independent of the PDF queue, including a deliberately paused queue.
        folders?.schedule(collection)
        val staged = mutableMapOf<String, File>()
        val temporary = mutableListOf<File>()
        val received=mutableMapOf<String,File>()
        val partials=mutableListOf<File>()
        var batchError:Exception?=null
        try {
            queue.plan(collection,catalog);queue.requireRunning(collection)
            progress(0, catalog.artifacts.size)
            val old = collection.database.resolutionDao().snapshot()
            val versions=old.versions.associateBy { it.id }
            catalog.artifacts.forEachIndexed { index, artifact ->
                ensureActive(); scope.requireCurrent(collection)
                val existing = versions[artifact.version]
                if (existing != null) check(existing.fileHash == artifact.hash && existing.fileSizeBytes == artifact.size &&
                    existing.pageCount == artifact.pages && existing.scorePdfId == artifact.pdf) { "已发布乐谱内容发生变化，请管理员重新发布" }
                val reusable = existing?.managedFilePath?.let(::File)?.takeIf { file ->
                    file.canonicalFile.parentFile == collection.directory.canonicalFile && file.isFile &&
                        file.length() == artifact.size && hash(file) == artifact.hash
                }
                if (reusable != null) {
                    staged[artifact.id] = reusable
                    queue.update(collection,artifact.id,DownloadPhase.READY,artifact.size)
                }
                else {
                    val pending = queue.partial(collection,artifact).also { partials+=it }
                    try {
                    client.transfer(session, artifact, pending) { done,total -> queue.progress(collection,artifact.id,done,total) }
                    ensureActive(); scope.requireCurrent(collection)
                    if (pending.length() != artifact.size || hash(pending) != artifact.hash)
                        throw TeamDownloadCorrupt("乐谱校验失败，未加入曲库，请重试")
                    queue.requireRunning(collection);queue.update(collection,artifact.id,DownloadPhase.VERIFYING)
                    try { verifier.verify(pending, artifact.pages) { ensureActive();queue.requireRunning(collection) } }
                    catch(error:Exception) {
                        if(error !is TeamDownloadsPaused && error !is CancellationException && error !is OfflineUnavailable)
                            queue.discard(collection,artifact)
                        throw error
                    }
                    queue.requireRunning(collection);queue.update(collection,artifact.id,DownloadPhase.STAGED)
                    received[artifact.id]=pending
                    } catch(error:Exception) {
                        if(error is TeamDownloadCorrupt)queue.discard(collection,artifact)
                        queue.update(collection,artifact.id,if(error is TeamDownloadsPaused || error is CancellationException)
                            DownloadPhase.WAITING else DownloadPhase.FAILED,pending.takeIf { it.isFile }?.length() ?: 0)
                        throw error
                    }
                }
                progress(index + 1, catalog.artifacts.size)
            }
            val freezes=mutableMapOf<String,TeamProgramSnapshot>()
            var frozenBytes=0L
            for(program in catalog.programs.filter { it.freezeId!=null }) {
                ensureActive();queue.requireRunning(collection)
                val cached=collection.database.annotationDao().snapshot(program.concert)
                if(cached==null || !TeamProgramSnapshot.matches(cached,program,catalog,collection.member)) {
                    val snapshot=client.frozen(session,program,catalog)
                    frozenBytes+=snapshot.payload.toByteArray(Charsets.UTF_8).size
                    require(frozenBytes<=MAX_TEAM_SNAPSHOT) { "本次冻结批注超过同步上限，请缩减分配给本机的节目单" }
                    require(snapshot.program==program)
                    freezes[program.concert]=snapshot
                }
            }
            // Preserve verified partials until the whole authorized transaction commits. A failed
            // later PDF, cancellation, or process loss must not force earlier files to redownload.
            val expectedHashes=catalog.artifacts.associate { it.id to it.hash }
            for((id,partial) in received) {
                ensureActive();queue.requireRunning(collection)
                val target=File(collection.directory,java.util.UUID.randomUUID().toString()+".pdf").also { temporary+=it }
                partial.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output);output.fd.sync() } }
                check(hash(target)==expectedHashes.getValue(id))
                staged[id]=target
            }
            // Server reauthorizes every download and the whole batch immediately before the local commit.
            deletions?.refresh(collection)
            val latest = client.catalog(session)
            scope.requireCurrent(collection)
            check(latest.revision >= collection.revision)
            checkpoint(collection, session, latest)
            collection.revision = latest.revision
            if (latest != catalog) { queue.plan(collection,latest);throw TeamSyncFailure("授权或乐谱清单已更新，请重新同步") }
            ensureActive()
            queue.committing(collection)
            withContext(NonCancellable) {
                scope.requireCurrent(collection)
                install(collection, session, catalog, staged, freezes)
            }
            queue.complete(collection,catalog)
            ink?.sync(collection)
            catalog.artifacts.size
        } catch (error: com.ensemble.scorelibrary.account.AccountRejected) {
            batchError=error
            if (error.status == 403 || error.status == 404) {
                try {
                    val latest = client.catalog(session)
                    scope.requireCurrent(collection)
                    if (latest.revision >= collection.revision) { checkpoint(collection, session, latest); collection.revision = latest.revision;queue.plan(collection,latest) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    scope.beginAuthorizationChange(collection)
                    try { offline?.invalidate(session); reconcile(collection, TeamCatalog(0, emptyList())) }
                    finally { scope.endAuthorizationChange(collection) }
                }
            }
            throw error
        } catch(error:Exception) {
            batchError=error;throw error
        } finally {
            queue.end(collection,batchError)
            withContext(NonCancellable) {
                val referenced = collection.database.resolutionDao().versions().map { it.managedFilePath }.toSet()
                temporary.filter { it.path !in referenced }.forEach { it.delete() }
                // Explicit logout/account replacement must discard late writes from the old
                // transfer. Ordinary pause/process interruption within the same account retains them.
                if(scope.active.value!==collection)partials.forEach { file ->
                    check(file.canonicalFile.parentFile==collection.directory.canonicalFile)
                    file.delete()
                }
            }
        }
    } }

    private suspend fun checkpoint(collection: TeamCollection, session: TeamSession, catalog: TeamCatalog) {
        deletions?.requireLive(collection,catalog)
        // A crash after observing a revocation must never reopen the previous saved grants.
        val changing = catalog.revision != collection.revision
        if (changing) scope.beginAuthorizationChange(collection)
        try {
        offline?.invalidate(session)
        reconcile(collection, catalog)
        if (offline != null && session.verifiedAt > 0) {
            offline.save(session, catalog)
            collection.authorizeUntil(minOf(session.expiresAt, System.currentTimeMillis() / 1000 + com.ensemble.scorelibrary.account.OFFLINE_SECONDS))
        }
        } finally { if (changing) scope.endAuthorizationChange(collection) }
    }

    private suspend fun reconcile(collection: TeamCollection, catalog: TeamCatalog) = collection.database.withTransaction {
        val dao = collection.database.libraryDao()
        val old = collection.database.resolutionDao().snapshot()
        val concerts=catalog.programs.map { it.concert }.toSet()
        old.concerts.filter { row -> row.id !in concerts && !row.isArchived }
            .forEach { dao.updateConcert(it.copy(isArchived = true)) }
        val bindings=old.bindings.filterNot { it.isArchived }.map { it.piecePartId to it.scorePdfId }.toSet()
        val allowed = catalog.artifacts.filter { (it.part to it.pdf) in bindings }.map { it.part }.toSet()
        old.extraPermissions.filter { it.memberId == collection.member }.forEach { permission ->
            val archived = permission.piecePartId !in allowed
            if (permission.isArchived != archived) dao.updateExtraPermission(permission.copy(isArchived = archived))
        }
    }

    private suspend fun install(collection: TeamCollection, session: TeamSession, catalog: TeamCatalog, files: Map<String, File>,
        freezes:Map<String,TeamProgramSnapshot> = emptyMap()) =
        collection.database.withTransaction {
            scope.requireCurrent(collection)
            deletions?.requireLive(collection,catalog)
            val db = collection.database
            val dao = db.libraryDao()
            val old = db.resolutionDao().snapshot()
            val member = old.members.singleOrNull { it.id == collection.member }
            // Online administrator is not a local mother-library administrator and gains no extra PDF permission.
            if (member == null) dao.insertMember(MemberEntity(id = collection.member, displayName = session.principal.name))
            else if (member.displayName != session.principal.name) dao.updateMember(member.copy(displayName = session.principal.name))
            val definitions = old.definitions.associateBy { it.id }.toMutableMap()
            val pieces = old.pieces.associateBy { it.id }.toMutableMap()
            val parts=old.parts.associateBy { it.id }
            val pdfs=old.pdfs.associateBy { it.id }
            val versions=old.versions.associateBy { it.id }
            val bindings=old.bindings.groupBy { it.piecePartId }
            val permissions=old.extraPermissions.filter { it.memberId==collection.member }.associateBy { it.piecePartId }
            val caches=old.caches.filter { it.memberId==collection.member }.associateBy { it.scorePdfVersionId }
            val concerts=old.concerts.associateBy { it.id }
            val itemsByConcert=old.items.groupBy { it.concertId }
            val items=old.items.associateBy { it.id }
            catalog.artifacts.forEach { a ->
                val definition = definitions[a.definition]
                if (definition == null) {
                    val row = PartDefinitionEntity(id = a.definition, name = a.partName)
                    dao.insertPartDefinition(row); definitions[row.id] = row
                } else if (definition.name != a.partName) dao.updatePartDefinition(definition.copy(name = a.partName))
                val piece = pieces[a.piece]
                val row = PieceEntity(id = a.piece, title = a.title, categoryName = a.category.takeIf { it.isNotEmpty() },
                    categoryCode = ScoreNumber.optional(a.number)?.category,
                    organizerSequence = ScoreNumber.optional(a.number)?.sequence)
                if (piece == null) { dao.insertPiece(row); pieces[row.id] = row }
                else if (piece.title != row.title || piece.categoryName != row.categoryName || piece.categoryCode != row.categoryCode ||
                    piece.organizerSequence != row.organizerSequence || piece.isArchived)
                    dao.updatePiece(piece.copy(title = row.title, categoryName = row.categoryName, categoryCode = row.categoryCode,
                        organizerSequence = row.organizerSequence, isArchived = false))
                if (a.part !in parts) dao.insertPiecePart(PiecePartEntity(id = a.part, pieceId = a.piece,
                    partDefinitionId = a.definition, displayName = a.partName))
                else {
                    val part = parts.getValue(a.part)
                    if (part.displayName != a.partName || part.isArchived) dao.updatePiecePart(part.copy(displayName = a.partName, isArchived = false))
                }
                if (a.pdf !in pdfs) dao.insertScorePdf(ScorePdfEntity(id = a.pdf, title = a.title))
                val version = versions[a.version]
                if (version == null) dao.insertScorePdfVersion(ScorePdfVersionEntity(id = a.version, scorePdfId = a.pdf,
                    versionNumber = 1, managedFilePath = files.getValue(a.id).path, originalUri = "team-artifact:${a.id}",
                    fileHash = a.hash, fileSizeBytes = a.size, pageCount = a.pages))
                else if (version.managedFilePath != files.getValue(a.id).path) {
                    // Repairing a missing/corrupt cached file preserves immutable version identity and annotations.
                    dao.updateVersion(version.copy(managedFilePath = files.getValue(a.id).path))
                }
                val pdf = pdfs[a.pdf] ?: ScorePdfEntity(id = a.pdf, title = a.title)
                if (pdf.activeVersionId != a.version) dao.updateScorePdf(pdf.copy(activeVersionId = a.version))
                val binding = bindings[a.part]?.firstOrNull()
                if (binding == null) dao.insertPartScoreBinding(PartScoreBindingEntity(id = a.part, piecePartId = a.part,
                    scorePdfId = a.pdf, startPage = 1, endPage = a.pages))
                else if (binding.scorePdfId != a.pdf || binding.endPage != a.pages || binding.isArchived)
                    dao.updatePartScoreBinding(binding.copy(scorePdfId = a.pdf, startPage = 1, endPage = a.pages, isArchived = false))
                val permission = permissions[a.part]
                if (permission == null) dao.insertExtraPermission(MemberPiecePartPermissionEntity(id = teamId("permission/${collection.member}/${a.part}"),
                    memberId = collection.member, piecePartId = a.part))
                else if (permission.isArchived) dao.updateExtraPermission(permission.copy(isArchived = false))
                val cache = caches[a.version]
                if (cache == null)
                    dao.insertCache(ScoreCacheEntryEntity(id = teamId("cache/${collection.member}/${a.version}"), scorePdfVersionId = a.version,
                        memberId = collection.member, status = CacheStatus.CACHED, cachedFilePath = files.getValue(a.id).path,
                        cachedAtEpochMillis = System.currentTimeMillis()))
                else if (cache.cachedFilePath != files.getValue(a.id).path || cache.status != CacheStatus.CACHED)
                    dao.updateCache(cache.copy(cachedFilePath = files.getValue(a.id).path, status = CacheStatus.CACHED,
                        cachedAtEpochMillis = System.currentTimeMillis()))
            }
            // A single authorized part can be previewed directly; multiple parts keep the explicit part picker.
            catalog.programs.forEach { programme ->
                val row = ConcertEntity(id = programme.concert, title = programme.title,
                    status = com.ensemble.scorelibrary.core.model.ConcertStatus.PUBLISHED)
                val existingProgramme = concerts[row.id]
                if (existingProgramme == null) dao.insertConcert(row) else if (existingProgramme != row) dao.updateConcert(row)
                val incoming = programme.pieceKeys.map(programme::item).toSet()
                itemsByConcert[row.id].orEmpty().filter { it.id !in incoming && !it.isArchived }
                    .forEach { dao.updateConcertItem(it.copy(isArchived = true)) }
                programme.pieceKeys.forEachIndexed { index, key ->
                    val item = ConcertItemEntity(id = programme.item(key), concertId = row.id,
                        pieceId = teamId("piece/$key"), sortOrder = index)
                    val existingItem = items[item.id]
                    if (existingItem == null) dao.insertConcertItem(item) else if (existingItem != item) dao.updateConcertItem(item)
                }
                if(programme.freezeId!=null) {
                    val existing=db.annotationDao().snapshot(programme.concert)
                    val fresh=db.resolutionDao().snapshot()
                    val incoming=freezes[programme.concert]?.entity(fresh,collection.member)
                    if(existing==null) db.annotationDao().insertSnapshot(checkNotNull(incoming) { "本场冻结版本尚未完整缓存，请联网同步" })
                    else if(incoming!=null) require(existing.id==incoming.id && existing.payload==incoming.payload && existing.manifest==incoming.manifest) {
                        "本场冻结记录不一致，已保留原记录，请管理员核对"
                    }
                    val saved=checkNotNull(existing ?: incoming)
                    require(TeamProgramSnapshot.matches(saved,programme,catalog,collection.member))
                    TeamProgramSnapshot.validate(saved,fresh,collection.member)
                }
            }
            val singleParts = catalog.artifacts.groupBy { it.piece }.values.filter { it.size == 1 }.map { it.single().part }.toSet()
            old.assignments.filter { it.memberId == collection.member }.forEach {
                val archived = it.piecePartId !in singleParts
                if (it.isArchived != archived) dao.updateAssignment(it.copy(isArchived = archived))
            }
            val assigned=old.assignments.filter { it.memberId==collection.member }.map { it.piecePartId }.toSet()
            singleParts.filter { it !in assigned }.forEach { part ->
                dao.insertAssignment(PiecePartAssignmentEntity(id = teamId("assignment/${collection.member}/$part"),
                    memberId = collection.member, piecePartId = part))
            }
            scope.requireCurrent(collection)
        }
    companion object {
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class TeamSyncModule {
    @dagger.Binds abstract fun transport(client: TeamScoreClient): TeamScoreTransport
    @dagger.Binds abstract fun verifier(verifier: AndroidTeamPdfVerifier): TeamPdfVerifier
    @dagger.Binds abstract fun sync(sync: TeamLibrarySync): TeamScoreSync
}
