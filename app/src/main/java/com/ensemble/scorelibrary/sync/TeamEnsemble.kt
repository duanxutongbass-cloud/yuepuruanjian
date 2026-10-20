package com.ensemble.scorelibrary.sync

import com.ensemble.scorelibrary.domain.ScoreNumber

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.ensemble.scorelibrary.account.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject

data class EnsemblePart(val id: String, val name: String, val number: String)
data class EnsemblePiece(val key: String, val title: String, val number: String, val category: String,
    val removed: Boolean, val conflict: Boolean, val parts: List<EnsemblePart>) {
    val heading get() = "[$number] $title"
    val description get() = "$category · ${parts.size} 个声部 · " + parts.joinToString("、") { it.name }
}
data class EnsembleProgram(val id: String = UUID.randomUUID().toString(), val title: String = "", val pieces: List<String> = emptyList(),
    val accounts: Set<String>? = null, val archived: Boolean = false, val revision: Int = 0,
    val freezeId: String? = null)
data class EnsembleState(val key: String? = null, val pieces: List<EnsemblePiece> = emptyList(),
    val programs: List<EnsembleProgram> = emptyList(), val revision: Long = -1, val busy: Boolean = false,
    val message: String = "", val changes: Long = 0, val readingParts: List<Pair<String,String>> = emptyList(), val readingPart: String? = null,
    val numberInventory: TeamNumberInventory? = null, val permanentPreview:PermanentPreview?=null,
    val numberRelease:NumberReleaseInventory?=null,val numberReleasePreview:NumberReleasePreview?=null,
    val batchPreview:TeamBatchPlan?=null)

/** Resolve complete recycled pieces against the current catalog, never caller-supplied parts. */
internal fun permanentArtifacts(state:EnsembleState,keys:Set<String>):Set<String> {
    require(state.revision>=0 && keys.isNotEmpty())
    val pieces=keys.map { key -> state.pieces.single { it.key==key } }
    require(pieces.all { it.removed && !it.conflict && it.parts.isNotEmpty() })
    val ids=pieces.flatMap { it.parts }.map { it.id }
    require(ids.size in 1..200 && ids.toSet().size==ids.size)
    require(ids.all { UUID.fromString(it).toString()==it })
    return ids.toSet()
}

data class EnsembleManagement(val key:String,val programs:Boolean)

@HiltViewModel
class TeamEnsembleViewModel @Inject constructor(private val scope: TeamLibraryScope, private val gateway: TeamAccountGateway) : ViewModel() {
    private val mutable = MutableStateFlow(EnsembleState())
    val state = mutable.asStateFlow()
    private val mutableManagement=MutableStateFlow<EnsembleManagement?>(null)
    val management=mutableManagement.asStateFlow()
    fun openManagement(programs:Boolean) {
        val c=scope.active.value ?: return
        if(c.session?.principal?.role!="admin")return
        if(runCatching { scope.requireCurrent(c) }.isFailure)return
        mutableManagement.value=EnsembleManagement(c.key,programs)
        reload()
    }
    fun closeManagement() { mutableManagement.value=null }
    init {
        viewModelScope.launch {
            scope.active.collectLatest { c ->
                mutable.value = EnsembleState(key = c?.key)
                if (c != null) c.database.invalidationTracker.createFlow("members", "piece_parts", "part_definitions", "member_piece_part_permissions").collect {
                    val data = withContext(Dispatchers.IO) { c.database.resolutionDao().snapshot() }
                    if (scope.active.value === c) {
                        val ids = data.extraPermissions.filter { !it.isArchived && it.memberId == c.member }.map { it.piecePartId }.toSet()
                        val definitions = data.parts.filter { !it.isArchived && it.id in ids }.map { it.partDefinitionId }.toSet()
                        mutable.update { old -> old.copy(readingParts = data.definitions.filter { !it.isArchived && it.id in definitions }.map { it.id to it.name },
                            readingPart = data.members.firstOrNull { it.id == c.member }?.defaultPartDefinitionId) }
                    }
                }
            }
        }
    }
    fun readingPart(id: String) = operation(admin = false) { c ->
        c.database.withTransaction {
            scope.requireCurrent(c)
            val data = c.database.resolutionDao().snapshot()
            require(data.definitions.any { it.id == id && !it.isArchived })
            val member = data.members.single { it.id == c.member }
            c.database.libraryDao().updateMember(member.copy(defaultPartDefinitionId = id))
        }
        mutable.update { it.copy(message = "已选择本机阅读声部；此选择不会增加乐谱权限") }
    }
    fun reload() = operation { c -> load(c) }
    fun dismissPermanent() { if(!mutable.value.busy)mutable.update { it.copy(permanentPreview=null) } }
    fun previewPermanent(piece:EnsemblePiece)=previewPermanent(setOf(piece.key))
    fun previewPermanent(keys:Set<String>) {
        val selected=keys.toSet()
        operation { c ->
        mutable.update { it.copy(permanentPreview=null) }
        val revision=mutable.value.revision
        val ids=permanentArtifacts(mutable.value,selected)
        val result=gateway.scoreRequest(checkNotNull(c.session),"/v1/admin/permanent-deletions/preview",JSONObject()
            .put("artifactIds",JSONArray(ids.sorted())).put("expectedRevision",revision))
        scope.requireCurrent(c)
        val preview=PermanentPreview.parse(result,c.key,ids,revision)
        mutable.update { it.copy(permanentPreview=preview) }
        }
    }
    fun confirmPermanent(preview:PermanentPreview)=operation { c ->
        require(preview===mutable.value.permanentPreview && preview.accountKey==c.key && preview.revision==mutable.value.revision)
        val result=gateway.scoreRequest(checkNotNull(c.session),"/v1/admin/permanent-deletions",preview.body())
        require(result.get("ok")==true && result.getString("requestId")==preview.request)
        val ids=result.getJSONArray("artifactIds")
        require((0 until ids.length()).map { ids.getString(it) }.toSet()==preview.artifacts && ids.length()==preview.artifacts.size)
        scope.requireCurrent(c)
        mutable.update { it.copy(permanentPreview=null,changes=it.changes+1,
            numberInventory=null,numberRelease=null,numberReleasePreview=null,
            message="NAS已彻底删除所选分谱及批注，正在同步清理本机副本；离线设备下次联网处理。整曲删除后可在编号检查中另行释放编号。") }
        load(c)
    }
    fun checkNumbers() = operation { c ->
        mutable.update { it.copy(numberInventory = null,numberRelease=null,numberReleasePreview=null) }
        val result = TeamNumberInventory.parse(gateway.scoreRequest(checkNotNull(c.session), "/v1/admin/number-inventory"))
        scope.requireCurrent(c)
        mutable.update { it.copy(numberInventory = result, message = "编号检查完成；已有编号保持，新曲优先使用真正空号") }
        val release=NumberReleaseInventory.parse(gateway.scoreRequest(checkNotNull(c.session),"/v1/admin/number-releases"),c)
        scope.requireCurrent(c)
        require(release.revision==result.revision) { "编号清单已变化，请重新检查" }
        mutable.update { it.copy(numberRelease=release) }
    }
    fun dismissNumberRelease() { if(!mutable.value.busy)mutable.update { it.copy(numberReleasePreview=null) } }
    fun previewNumberRelease(selected:Set<String>) {
        val wanted=selected.toSet()
        operation { c ->
            val inventory=checkNotNull(mutable.value.numberRelease)
            require(inventory.key==c.key && wanted.size in 1..200 && inventory.eligible.map { it.sequence }.containsAll(wanted))
            mutable.update { it.copy(numberReleasePreview=null) }
            val reply=gateway.scoreRequest(checkNotNull(c.session),"/v1/admin/number-releases/preview",JSONObject()
                .put("sequences",JSONArray(wanted.sortedBy { ScoreNumber.sequence(it) })).put("expectedRevision",inventory.revision))
            scope.requireCurrent(c)
            val preview=NumberReleasePreview.parse(reply,inventory,wanted)
            mutable.update { it.copy(numberReleasePreview=preview) }
        }
    }
    fun confirmNumberRelease(preview:NumberReleasePreview)=operation { c ->
        require(preview===mutable.value.numberReleasePreview && preview.key==c.key &&
            preview.revision==mutable.value.numberRelease?.revision)
        val session=checkNotNull(c.session)
        val reply=gateway.scoreRequest(session,"/v1/admin/number-releases/commit",preview.body())
        require(reply.get("ok")==true && reply.get("requestId")==preview.request &&
            sequences(reply.getJSONArray("sequences")).toSet()==preview.sequences && integer(reply,"releasedCount")==preview.sequences.size.toLong() &&
            integer(reply,"revision")==preview.revision+1)
        scope.requireCurrent(c)
        mutable.update { it.copy(numberReleasePreview=null,numberRelease=null,numberInventory=null,changes=it.changes+1) }
        val numbers=TeamNumberInventory.parse(gateway.scoreRequest(session,"/v1/admin/number-inventory"))
        val release=NumberReleaseInventory.parse(gateway.scoreRequest(session,"/v1/admin/number-releases"),c)
        scope.requireCurrent(c)
        require(numbers.revision==release.revision && numbers.revision>=preview.revision+1)
        mutable.update { it.copy(numberInventory=numbers,numberRelease=release,
            message="已备份并释放 ${preview.sequences.size} 个编号，其余曲目原号保留；下一首新曲优先使用空号。") }
        load(c)
    }
    fun freeze(program: EnsembleProgram) = operation { c ->
        require(!program.archived && program.freezeId == null && program.revision > 0)
        // Stable across process loss; replay the same action after a lost acknowledgement.
        val receipt = gateway.scoreRequest(checkNotNull(c.session), "/v1/admin/program-freezes", JSONObject()
            .put("programId", program.id).put("expectedRevision", program.revision)
            .put("requestId", teamId("program-freeze/${program.id}/${program.revision}")))
        require(receipt.getBoolean("ok") && receipt.getString("programId") == program.id &&
            receipt.getInt("programRevision") == program.revision)
        require(UUID.fromString(receipt.getString("freezeId")).toString() == receipt.getString("freezeId"))
        load(c)
        mutable.update { it.copy(changes = it.changes + 1,
            message = "本场已冻结到 NAS；成员同步后取得各自获授权的固定分谱和已发布共享批注，可进行演出准备") }
    }
    fun save(program: EnsembleProgram) = operation { c ->
        val s = checkNotNull(c.session)
        gateway.scoreRequest(s, "/v1/admin/programs", JSONObject().put("id", program.id).put("title", program.title.trim())
            .put("pieceKeys", JSONArray(program.pieces)).put("accountIds", program.accounts?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
            .put("archived", program.archived).put("expectedRevision", program.revision))
        load(c)
        mutable.update { it.copy(changes = it.changes + 1, message = if (program.archived) "节目单已删除，可在回收站恢复" else "节目单已保存；每首曲目包含全部声部，成员按乐谱授权阅读") }
    }
    fun remove(piece: EnsemblePiece) = operation { c ->
        gateway.scoreRequest(checkNotNull(c.session), "/v1/admin/catalog-actions", JSONObject().put("requestId", UUID.randomUUID().toString())
            .put("artifactIds", JSONArray(piece.parts.map { it.id })).put("removed", !piece.removed).put("expectedRevision", mutable.value.revision))
        load(c)
        mutable.update { it.copy(changes = it.changes + 1, message = if (piece.removed) "乐谱已恢复，原编号与批注保留" else "整首曲目的 ${piece.parts.size} 份分谱已删除，可从回收站恢复；原 PDF 与批注保留") }
    }
    fun previewBatch(selected:Set<String>,removed:Boolean) {
        val c=scope.active.value ?: return
        if(mutable.value.busy || mutable.value.key!=c.key || c.session?.principal?.role!="admin")return
        try { scope.requireCurrent(c);mutable.update { it.copy(batchPreview=batchPlan(it,selected,removed)) } }
        catch(_:Exception) { mutable.update { it.copy(message="选择已变化或超过200份分谱，请刷新后重新选择") } }
    }
    fun dismissBatch() { if(!mutable.value.busy)mutable.update { it.copy(batchPreview=null) } }
    fun confirmBatch(plan:TeamBatchPlan)=operation { c ->
        require(plan===mutable.value.batchPreview && plan.key==c.key && plan.revision==mutable.value.revision)
        require(batchPlan(mutable.value,plan.pieces.map { it.key }.toSet(),plan.removed).artifacts==plan.artifacts)
        val result=gateway.scoreRequest(checkNotNull(c.session),"/v1/admin/catalog-actions",plan.body())
        require(result.get("ok")==true);scope.requireCurrent(c)
        mutable.update { it.copy(batchPreview=null) };load(c)
        mutable.update { it.copy(changes=it.changes+1,message=if(plan.removed)
            "已批量移入回收站；原PDF、编号和批注保留，可多选恢复" else "所选曲目已恢复，原编号、批注和阅读授权保留") }
    }
    fun receive(piece: EnsemblePiece) = operation { c ->
        require(!piece.removed)
        val s = checkNotNull(c.session)
        piece.parts.forEach { part ->
            scope.requireCurrent(c)
            gateway.scoreRequest(s, "/v1/admin/grants", JSONObject().put("accountId", s.principal.accountId)
                .put("artifactId", part.id).put("allowed", true))
        }
        load(c)
        mutable.update { it.copy(changes = it.changes + 1, message = "已为当前管理员接收本曲全部声部，请同步；可在「我的阅读声部」选择演出时阅读的声部") }
    }
    private fun load(c: TeamCollection) {
        val result = gateway.scoreRequest(checkNotNull(c.session), "/v1/admin/ensemble")
        scope.requireCurrent(c)
        val groups = result.getJSONArray("groups"); require(groups.length() <= 10000)
        val pieces = (0 until groups.length()).map { index ->
            val row = groups.getJSONObject(index); val parts = row.getJSONArray("parts"); require(parts.length() <= 5000)
            EnsemblePiece(row.getString("key"), row.getString("title"), row.getString("number"), row.getString("category"),
                row.getBoolean("removed"), row.getBoolean("conflict"), (0 until parts.length()).map { i -> parts.getJSONObject(i).let {
                    EnsemblePart(it.getString("artifactId"), it.getString("partName"), it.getString("scoreNumber")) } })
        }
        val rows = result.getJSONArray("programs"); require(rows.length() <= 1000)
        val programs = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i); val keys = row.getJSONArray("pieceKeys"); require(keys.length() in 1..200)
            val accounts = row.optJSONArray("accountIds")?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() }
            EnsembleProgram(row.getString("id"), row.getString("title"), (0 until keys.length()).map { keys.getString(it) },
                accounts, row.getBoolean("archived"), row.getInt("revision"), row.optJSONObject("freeze")?.getString("id")?.also {
                    require(UUID.fromString(it).toString() == it)
                })
        }
        val revision=result.getLong("revision")
        mutable.update { it.copy(pieces = pieces, programs = programs, revision = revision,
            permanentPreview=it.permanentPreview?.takeIf { p -> p.revision==revision },
            numberReleasePreview=it.numberReleasePreview?.takeIf { p -> p.revision==revision },
            batchPreview=it.batchPreview?.takeIf { p->p.revision==revision },
            numberRelease=it.numberRelease?.takeIf { p -> p.revision==revision },
            numberInventory=it.numberInventory?.takeIf { p -> p.revision==revision }) }
    }
    private fun operation(admin: Boolean = true, action: suspend (TeamCollection) -> Unit) {
        val c = scope.active.value ?: return
        if (mutable.value.busy || mutable.value.key != c.key || admin && c.session?.principal?.role != "admin") return
        mutable.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { scope.requireCurrent(c); action(c) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (scope.active.value === c) mutable.update { it.copy(message = when {
                    error is TeamCleanupFailure -> error.message.orEmpty()
                    error is AccountRejected && error.status == 404 -> "NAS 管理服务需要升级，请稍后重试"
                    error is AccountRejected && error.code == "program_frozen" -> "本场已经冻结，请刷新；修改内容请复制为新场次"
                    error is AccountRejected && error.code == "program_freeze_too_large" -> "本场页数或批注过多，请拆分节目单后重试"
                    error is AccountRejected && error.code in setOf("number_not_releasable","number_release_scope_changed") -> "编号状态已变化，请重新检查可释放清单；现存曲目未改号"
                    error is AccountRejected && error.status == 409 -> "目录或节目单已变化，请刷新后重新核对；未覆盖他人修改"
                    error is AccountRejected -> accountError(error)
                    else -> "操作未确认，请检查网络并刷新目录后核对"
                }) }
            } finally { if (scope.active.value === c) mutable.update { it.copy(busy = false) } }
        }
    }
}
