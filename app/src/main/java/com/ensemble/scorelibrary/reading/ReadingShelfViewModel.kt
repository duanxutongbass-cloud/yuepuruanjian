package com.ensemble.scorelibrary.reading

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ReadingShelfState(val member: String? = null, val owner: ReadingOwner? = null,
    val data: ReadingActivityData = ReadingActivityData(), val busy: Boolean = false, val error: String? = null)
val LocalReadingShelf = staticCompositionLocalOf<ReadingShelfViewModel?> { null }

@HiltViewModel
class ReadingShelfViewModel @Inject constructor(private val activity: ReadingActivity) : ViewModel() {
    private val mutable = MutableStateFlow(ReadingShelfState())
    val state = mutable.asStateFlow()
    private var identity: ReadingIdentity? = null
    private var generation = 0L
    private var job: Job? = null
    private var refreshWanted = false
    init {
        viewModelScope.launch { activity.identityChanges.collect { load(mutable.value.member) } }
        viewModelScope.launch { activity.changes.collect { owner ->
            if (owner == mutable.value.owner) {
                if (mutable.value.busy) refreshWanted = true else load(mutable.value.member)
            }
        } }
        viewModelScope.launch { activity.issue.collect { issue ->
            if (issue != null && issue.owner == mutable.value.owner && current(mutable.value.member))
                mutable.value = mutable.value.copy(error = issue.message)
        } }
    }
    fun current(member: String?): Boolean = member != null && mutable.value.member == member &&
        runCatching { identity?.also { it.guard() }?.owner == activity.identity(member).owner }.getOrDefault(false)
    fun bind(member: String?) { if (member != mutable.value.member || member != null && !current(member)) load(member) }
    fun retry() = load(mutable.value.member)
    private fun load(member: String?) {
        job?.cancel(); val token = ++generation; refreshWanted = false
        val target = try { member?.let(activity::identity) } catch (_: Exception) { null }
        identity = target
        val previous = mutable.value
        mutable.value = ReadingShelfState(member, target?.owner,
            if (target != null && previous.owner == target.owner) previous.data else ReadingActivityData(),
            busy = target != null, error = if (member != null && target == null) "当前账号需重新核验后才能读取本机记录" else null)
        if (target == null) return
        job = viewModelScope.launch {
            try {
                val data = activity.read(target); target.guard()
                if (generation == token) mutable.value = mutable.value.copy(data = data, busy = false,
                    error = activity.issue.value?.takeIf { it.owner == target.owner }?.message)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == token) mutable.value = ReadingShelfState(member, target.owner,
                    error = "本机最近阅读与收藏读取失败，原记录已保留；请重试")
            }
            if (generation == token && refreshWanted) load(member)
        }
    }
    fun favorite(member: String, piece: String, value: Boolean) {
        if (current(member) && value && piece !in mutable.value.data.favorites && mutable.value.data.favorites.size >= ReadingActivityStore.MAX_FAVORITES) {
            mutable.value = mutable.value.copy(error = "收藏已达 2000 首，请先取消部分收藏"); return
        }
        mutate(member) { target -> activity.favorite(target, piece, value) }
    }
    fun clearRecent(member: String) = mutate(member) { activity.clearRecent(it) }
    fun clearFavorites(member: String) = mutate(member) { activity.clearFavorites(it) }
    private fun mutate(member: String, change: suspend (ReadingIdentity) -> ReadingActivityData) {
        if (!current(member) || mutable.value.busy) return
        val target = identity ?: return
        job?.cancel(); val token = ++generation; refreshWanted = false
        mutable.value = mutable.value.copy(busy = true, error = null)
        job = viewModelScope.launch {
            try {
                val data = change(target); target.guard()
                if (generation == token) mutable.value = mutable.value.copy(data = data, busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == token) mutable.value = mutable.value.copy(busy = false,
                error = "本机记录保存失败，未确认本次修改；请重试") }
            if (generation == token && refreshWanted) load(member)
        }
    }
}
