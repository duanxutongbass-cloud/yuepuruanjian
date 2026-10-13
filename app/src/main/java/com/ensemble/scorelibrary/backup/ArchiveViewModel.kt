package com.ensemble.scorelibrary.backup

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ArchiveAction { EXPORT_LIBRARY, EXPORT_PERSONAL, RESTORE_LIBRARY, RESTORE_PERSONAL }
data class ArchiveUiState(val busy: Boolean = false, val pending: Boolean = false,
    val preview: ArchivePreview? = null, val message: String? = null, val revision: Int = 0)

@HiltViewModel
class ArchiveViewModel @Inject constructor(private val store: AssetArchiveStore,
    private val saved: SavedStateHandle) : ViewModel() {
    private var actor: String? = saved["archiveActor"]
    private var job: Job? = null
    private var source: Uri? = null
    private var epoch = 0
    private val interrupted = saved.get<Boolean>("archiveDelivered") == true
    private val mutable = MutableStateFlow(ArchiveUiState(pending = !interrupted && action() != null,
        message = if (interrupted) "上次备份操作未确认完成，请重新选择文件" else null))
    val state = mutable.asStateFlow()
    init { if (interrupted) { saved.remove<String>("archiveAction"); saved.remove<Boolean>("archiveDelivered") } }
    private fun action() = saved.get<String>("archiveAction")?.let { runCatching { ArchiveAction.valueOf(it) }.getOrNull() }
    fun bind(member: String?) {
        if (actor == member) return
        epoch++; job?.cancel(); actor = member; saved["archiveActor"] = member
        dismiss(); mutable.value = ArchiveUiState(revision = mutable.value.revision)
    }
    fun request(action: ArchiveAction): Boolean {
        if (mutable.value.busy || mutable.value.pending) return false
        if (action != ArchiveAction.RESTORE_LIBRARY && actor == null) return false
        saved["archiveAction"] = action.name
        saved["archiveDelivered"] = false
        mutable.value = mutable.value.copy(pending = true, message = null)
        return true
    }
    fun picked(uri: Uri?) {
        val action = action() ?: return
        if (uri == null) { dismiss(); return }
        saved["archiveDelivered"] = true
        val owner = actor
        val token = epoch
        work {
            if (action == ArchiveAction.EXPORT_LIBRARY || action == ArchiveAction.EXPORT_PERSONAL) {
                store.export(requireNotNull(owner), if (action == ArchiveAction.EXPORT_LIBRARY) ArchiveKind.LIBRARY else ArchiveKind.PERSONAL, uri)
                if (actor == owner && epoch == token) { dismiss(); mutable.value = mutable.value.copy(message = "备份已导出并校验") }
            } else {
                val preview = store.preview(uri)
                require(preview.kind == if (action == ArchiveAction.RESTORE_LIBRARY) ArchiveKind.LIBRARY else ArchiveKind.PERSONAL) { "所选备份类型不匹配" }
                if (preview.kind == ArchiveKind.PERSONAL) require(preview.owner == owner) { "只能恢复原成员本人的个人批注" }
                if (actor == owner && epoch == token) { source = uri; mutable.value = mutable.value.copy(preview = preview) }
            }
        }
    }
    fun restore() {
        val preview = mutable.value.preview ?: return
        val uri = source ?: return
        val owner = actor
        val token = epoch
        mutable.value = mutable.value.copy(preview = null)
        work {
            store.restore(owner, uri, preview.hash)
            if (actor == owner && epoch == token) { dismiss(); mutable.value = mutable.value.copy(message = "恢复完成") }
        }
    }
    fun dismiss() {
        source = null; saved.remove<String>("archiveAction"); saved.remove<Boolean>("archiveDelivered")
        mutable.value = mutable.value.copy(pending = false, preview = null)
    }
    fun cancel() {
        val restoring = action() in listOf(ArchiveAction.RESTORE_LIBRARY, ArchiveAction.RESTORE_PERSONAL)
        epoch++; job?.cancel(); dismiss()
        mutable.value = mutable.value.copy(busy = false, message = if (restoring)
            "已请求取消；正在提交的恢复可能完成，请核对当前数据"
            else "已请求取消；未完成的导出文件不能作为有效备份")
    }
    private fun work(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val owner = actor
        val token = epoch
        mutable.value = mutable.value.copy(busy = true, message = null)
        job = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (actor == owner && epoch == token) {
                    dismiss()
                    val detail = failure.message?.takeIf { text -> text.any { it in '\u4e00'..'\u9fff' } }
                    mutable.value = mutable.value.copy(message = detail ?: "备份文件无效或操作未完成，请重新检查；原有数据未覆盖")
                }
            } finally {
                // A cancelled caller may have crossed the atomic commit boundary; always refresh.
                mutable.value = mutable.value.copy(busy = if (epoch == token) false else mutable.value.busy,
                    revision = mutable.value.revision + 1)
            }
        }
    }
}
