package com.ensemble.scorelibrary.sync

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import com.ensemble.scorelibrary.diagnostics.*

data class TeamPublishState(val key: String? = null, val file: File? = null, val name: String = "",
    val hash: String = "", val size: Long = 0, val pages: Int = 0,
    val suggestion: TeamPublishDetails? = null, val job: TeamPublishJob? = null,
    val busy: Boolean = false, val sent: Long = 0, val message: String = "", val batch: List<TeamPublishJob> = emptyList(), val blocked: Boolean = false,
    val existing: List<ExistingTeamScore> = emptyList(), val catalogCheck: Long = 0,
    val reservedSequences: Set<Long> = emptySet(), val maximumSequence: Long = 999)

@HiltViewModel
class TeamPublishViewModel @Inject constructor(@param:ApplicationContext private val context: Context,
    private val scope: TeamLibraryScope, private val publisher: TeamPublisher,
    private val operations: TestOperations? = null) : ViewModel() {
    private val journal = TeamPublishJournal(File(context.noBackupFilesDir, "team-publishing"))
    private val mutable = MutableStateFlow(TeamPublishState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var operation = UUID.randomUUID().toString()
    init {
        viewModelScope.launch {
            scope.active.collect { collection ->
                work?.cancelAndJoin()
                mutable.value = TeamPublishState(key = collection?.key)
                if (collection?.session?.principal?.role == "admin") {
                    try {
                        val restored = withContext(Dispatchers.IO) { journal.readBatch(collection.key) }
                        val pending = restored.firstOrNull { !it.finished } ?: restored.firstOrNull()
                        scope.requireCurrent(collection)
                        if (pending != null) mutable.value = TeamPublishState(collection.key, pending.file, pending.name,
                            pending.hash, pending.size, pending.pages, pending.details, pending,
                            message = if (restored.all { it.finished }) queueStatus(restored) + "；可继续选择 PDF"
                                else queueStatus(restored) + "；已恢复未确认队列，请重试核验", batch = restored)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        mutable.value = TeamPublishState(key = collection.key, blocked = true, message = "发布记录暂时无法读取，请检查存储后重新登录；保留原记录")
                    }
                }
            }
        }
    }
    fun select(files: List<Pair<Uri, String>>) = start(TestEvent.UPLOAD_SELECT) { collection ->
        val previous = mutable.value
        check(previous.job == null || previous.batch.all { it.finished }) { "请先重试核验或取消上一次未完成上传" }
        require(files.isNotEmpty()) { "请至少选择一份 PDF" }
        val created = mutableListOf<File>()
        var installed = false
        try {
        val staged = withContext(Dispatchers.IO) { files.mapIndexed { index, (uri, name) ->
            mutable.update { it.copy(message = "正在读取并检查第 ${index + 1} / ${files.size} 份 PDF…") }
            val reading = android.os.SystemClock.elapsedRealtime()
            operations?.record(TestEvent.UPLOAD_READ,TestResult.START,operation,count=index+1)
            require(uri.scheme == "content") { "请从系统文件选择器选择 PDF" }
            val file = File(journal.directory(collection.key), "${UUID.randomUUID()}.pdf")
            created += file
            var keep = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        size += n; require(size <= MAX_TEAM_UPLOAD) { "PDF 超过 100 MB，请先拆分" }
                        output.write(buffer, 0, n); digest.update(buffer, 0, n)
                    }
                    output.fd.sync()
                } } ?: error("无法读取所选 PDF")
                val pages = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
                    require(pdf.pageCount in 1..2000) { "PDF 页数需在 1–2000 页之间" }
                    repeat(pdf.pageCount) { ensureActive(); pdf.openPage(it).use { page -> require(page.width > 0 && page.height > 0) } }
                    pdf.pageCount
                } }
                scope.requireCurrent(collection)
                val prefs = context.getSharedPreferences("organizer-naming", Context.MODE_PRIVATE)
                val rules = OrganizerRules.read(prefs.getString("rules", null)
                    ?: context.assets.open("score-organizer-rules.json").bufferedReader().use { it.readText() })
                val parsed = parseOrganizerFilename(name, rules)
                val details = TeamPublishDetails(parsed.title ?: name.removeSuffix(".pdf").take(100),
                    parsed.instrumentName?.let { "${parsed.instrumentCode} $it" }.orEmpty(),
                    if (parsed.sequence != null) "${parsed.instrumentCode}${parsed.categoryCode}${parsed.sequence}" else "",
                    parsed.categoryName?.let { "${parsed.categoryCode} $it" }.orEmpty(), setOf(collection.session!!.principal.accountId))
                keep = true
                TeamPublishState(collection.key, file, name, digest.digest().joinToString("") { "%02x".format(it) }, size, pages,
                    details, busy = true, message = parsed.error ?: "已按整理器规则填写，请核对后发布").also {
                    operations?.record(TestEvent.UPLOAD_READ,operation=operation,count=index+1,elapsed=android.os.SystemClock.elapsedRealtime()-reading)
                }
            } finally { if (!keep) file.delete() }
        } }
        scope.requireCurrent(collection)
        val (existing, inventory) = withContext(Dispatchers.IO) { publisher.numberingContext(checkNotNull(collection.session)) }
        scope.requireCurrent(collection)
        val jobs = staged.map { row -> TeamPublishJob(collection.key, checkNotNull(row.file), row.name, row.hash, row.size, row.pages, checkNotNull(row.suggestion)) }
        withContext(Dispatchers.IO) {
            if (previous.job != null) journal.forgetBatch(previous.batch.ifEmpty { listOf(previous.job) })
            else previous.batch.forEach { old ->
                require(old.file.canonicalFile.parentFile == journal.directory(collection.key).canonicalFile)
                check(!old.file.exists() || old.file.delete())
            }
        }
        mutable.value = staged.first().copy(batch = jobs, existing = existing, reservedSequences = inventory.occupied, maximumSequence = inventory.maximum,
            message = "已选择 ${jobs.size} 份 PDF，新曲优先补空号，已有曲目保持原号；请核对声部、分类与接收成员")
        installed = true
        } finally { if (!installed) withContext(NonCancellable + Dispatchers.IO) { created.forEach { it.delete() } } }
    }
    fun publish(details: List<TeamPublishDetails>) = start(TestEvent.UPLOAD_PUBLISH) { collection ->
        val current = mutable.value
        val session = checkNotNull(collection.session)
        val jobs = if (current.job != null) current.batch else {
            require(details.size == current.batch.size && details.isNotEmpty())
            current.batch.zip(details).map { (job, d) -> job.copy(details = d.copy(recipients = d.recipients + session.principal.accountId)) }
        }
        require(jobs.isNotEmpty() && jobs.any { !it.finished })
        jobs.forEach { it.details.validate() }
        publisher.validateBatch(session, jobs)
        if (current.job == null) try {
            withContext(Dispatchers.IO) { publisher.checkNewBatch(session, jobs) }
        } catch (conflict: UploadCatalogConflict) {
            scope.requireCurrent(collection)
            val inventory = withContext(Dispatchers.IO) { publisher.inventory(session) }
            scope.requireCurrent(collection)
            mutable.value = mutable.value.copy(existing = conflict.latest, reservedSequences = inventory.occupied, maximumSequence = inventory.maximum,
                catalogCheck = current.catalogCheck + 1)
            throw conflict
        }
        operations?.record(TestEvent.UPLOAD_PREFLIGHT,operation=operation,count=jobs.size)
        withContext(Dispatchers.IO) { journal.saveBatch(jobs) }
        mutable.value = mutable.value.copy(job = jobs.first { !it.finished }, batch = jobs, message = "正在自动分批上传到 NAS…")
        withContext(Dispatchers.IO) {
            scope.requireCurrent(collection)
            operations?.record(TestEvent.UPLOAD_SEND,TestResult.START,operation,count=jobs.size)
            publisher.publishBatch(session, jobs, committed = { updated -> persistQueue(collection, updated) },
                guard = { scope.requireCurrent(collection) }) { sent, _ ->
                scope.requireCurrent(collection)
                mutable.update { if (it.key == collection.key) it.copy(sent = sent) else it }
            }
            operations?.record(TestEvent.UPLOAD_SEND,operation=operation,count=jobs.size)
        }
        scope.requireCurrent(collection)
        val finished = mutable.value.batch
        check(finished.all { it.finished })
        val destination = if (finished.all { it.details.recipients == setOf(session.principal.accountId) })
            "已保存管理员总库，之后可单独分发给成员" else "按已确认的接收成员授权"
        mutable.value = mutable.value.copy(message = queueStatus(finished) + "；" + destination)
    }
    internal suspend fun comparisonFiles(index: Int, match: ExistingTeamScore, planned: List<TeamPublishDetails>): UploadComparisonFiles {
        val collection = checkNotNull(scope.active.value)
        scope.requireCurrent(collection)
        val current = mutable.value
        check(current.key == collection.key && !current.busy && current.job == null)
        require(planned.size == current.batch.size && index in current.batch.indices)
        val source = current.batch[index]
        val session = checkNotNull(collection.session)
        val stamp = scope.preparationStamp(collection)
        val directory = journal.directory(collection.key).canonicalFile
        require(source.file.canonicalFile.parentFile == directory)
        val downloaded = File(directory, "${UUID.randomUUID()}.pdf")
        var handedOff = false
        val paired = stagedComparisonMatch(current.batch, planned, index, match)
        fun guard() = requireUploadComparisonCurrent(scope, collection, stamp, mutable.value, listOfNotNull(source, paired))
        if (paired != null) {
            require(paired.file.canonicalFile.parentFile == directory)
            guard()
            return UploadComparisonFiles(
                UploadPdfSource(source.file, source.hash, source.size, source.pages, "本次上传 · ${source.name}"),
                UploadPdfSource(paired.file, paired.hash, paired.size, paired.pages, "本批另一份 · ${paired.name}"),
                verifyLocal = ::guard, verifyRemote = { guard() }, cleanup = {})
        }
        try {
            val artifact = publisher.comparisonArtifact(session, match)
            guard()
            publisher.downloadComparison(session, artifact, downloaded)
            guard()
            return UploadComparisonFiles(
                UploadPdfSource(source.file, source.hash, source.size, source.pages, "本次上传 · ${source.name}"),
                UploadPdfSource(downloaded, artifact.hash, artifact.size, artifact.pages, "库内原谱 · ${artifact.number} ${artifact.title} · ${artifact.partName}"),
                verifyLocal = ::guard,
                verifyRemote = { guard(); publisher.verifyComparison(session, artifact); guard() },
                cleanup = { downloaded.delete() }
            ).also { handedOff = true }
        } finally { if (!handedOff) downloaded.delete() }
    }
    fun discard(index: Int, details: List<TeamPublishDetails>) = start(TestEvent.UPLOAD_CANCEL) { collection ->
        val current = mutable.value
        require(current.job == null && details.size == current.batch.size && index in current.batch.indices)
        val remaining = current.batch.mapIndexedNotNull { i, job -> if (i == index) null else job.copy(details = details[i]) }
        withContext(Dispatchers.IO) {
            scope.requireCurrent(collection)
            journal.discardStaged(current.batch[index])
        }
        scope.requireCurrent(collection)
        val first = remaining.firstOrNull()
        mutable.value = if (first == null) TeamPublishState(key = collection.key, busy = true, message = "本批缓存已取消，原PDF和库内乐谱保留")
        else current.copy(file = first.file, name = first.name, hash = first.hash, size = first.size, pages = first.pages,
            suggestion = first.details, batch = remaining, message = "已取消本份上传，其余编辑保留；原PDF和库内乐谱保留")
    }
    fun cancel() = start(TestEvent.UPLOAD_CANCEL) { collection ->
        val current = mutable.value
        require(current.job != null && current.batch.any { !it.finished })
        val cancelled = withContext(Dispatchers.IO) {
            val confirmed = publisher.cancel(checkNotNull(collection.session), current.batch,
                guard = { scope.requireCurrent(collection) }, cancelled = { updated -> persistQueue(collection, updated) })
            if (confirmed) {
                scope.requireCurrent(collection)
                journal.forgetBatch(mutable.value.batch)
            }
            confirmed
        }
        scope.requireCurrent(collection)
        val latest = mutable.value
        mutable.value = if (cancelled) TeamPublishState(key = collection.key, busy = true,
            message = queueStatus(latest.batch) + "；未完成部分已取消，服务器已入库乐谱保留，可重新选择 PDF")
            else latest.copy(busy = true, message = queueStatus(latest.batch) + "；部分回执仍待核验，请重试；已取消部分不会再上传")
    }
    private fun queueStatus(jobs: List<TeamPublishJob>): String =
        "共 ${jobs.size} 份：已确认入库 ${jobs.count { it.completed }}，已取消 ${jobs.count { it.cancelled }}，待核验 ${jobs.count { !it.finished }}"

    /** Called only on the IO worker, after a verified server receipt and current-account guard. */
    private fun persistQueue(collection: TeamCollection, jobs: List<TeamPublishJob>) {
        scope.requireCurrent(collection)
        journal.saveBatch(jobs)
        scope.requireCurrent(collection)
        mutable.update { old ->
            if (old.key != collection.key) old else old.copy(batch = jobs,
                job = jobs.firstOrNull { !it.finished } ?: jobs.first(), sent = jobs.filter { it.completed }.sumOf { it.size },
                message = queueStatus(jobs))
        }
    }
    private fun start(event:TestEvent, action: suspend (TeamCollection) -> Unit) {
        if (mutable.value.busy || mutable.value.blocked) return
        val collection = scope.active.value ?: run {
            mutable.value = mutable.value.copy(message = "团队登录已失效，请返回账号页面联网核验后重试"); return
        }
        if (collection.session?.principal?.role != "admin" || mutable.value.key != collection.key) {
            mutable.value = mutable.value.copy(message = "当前账号或曲库已切换，请返回后重新打开上传页面"); return
        }
        if(event==TestEvent.UPLOAD_SELECT) operation=UUID.randomUUID().toString()
        val operationId=operation
        val started=android.os.SystemClock.elapsedRealtime()
        operations?.record(event,TestResult.START,operationId)
        mutable.value = mutable.value.copy(busy = true, message = "")
        work = viewModelScope.launch {
            try {
                scope.requireCurrent(collection); action(collection)
                operations?.record(event,operation=operationId,count=mutable.value.batch.size,elapsed=android.os.SystemClock.elapsedRealtime()-started)
            }
            catch (cancelled: CancellationException) {
                operations?.record(event,TestResult.CANCELLED,operationId); throw cancelled
            }
            catch (error: Exception) {
                operations?.record(event,TestResult.ERROR,operationId,TestOperations.error(error),elapsed=android.os.SystemClock.elapsedRealtime()-started)
                if (scope.active.value === collection) mutable.value = mutable.value.copy(message =
                    (if (event in setOf(TestEvent.UPLOAD_PUBLISH, TestEvent.UPLOAD_CANCEL) && mutable.value.job != null)
                        queueStatus(mutable.value.batch) + "；" else "") + when (error) {
                    is AccountRejected -> accountError(error)
                    is IllegalArgumentException, is IllegalStateException -> error.message ?: "请核对资料后重试"
                    else -> "上传尚未确认，请检查网络后重试；已保留本次发布记录"
                })
            } finally { if (mutable.value.key == collection.key) mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
