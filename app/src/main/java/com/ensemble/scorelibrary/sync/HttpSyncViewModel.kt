package com.ensemble.scorelibrary.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class HttpUiState(val member: String? = null, val name: String = "", val manifest: RemoteManifest? = null,
    val busy: Boolean = false, val message: String = "仅限临时合成账号；请使用本次服务生成的登录码", val revision: Int = 0)

@HiltViewModel
class HttpSyncViewModel @Inject constructor(private val store: HttpSyncStore) : ViewModel() {
    private val mutable = MutableStateFlow(HttpUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun login(account: String, code: String) {
        if (mutable.value.busy) return
        mutable.value = HttpUiState(busy = true, message = "正在请求登录与本人授权清单…")
        job = viewModelScope.launch {
            try {
                val manifest = store.login(account, code)
                val login = checkNotNull(store.login)
                mutable.value = HttpUiState(login.member, login.name, manifest, message = "登录成功 · 服务端授权 ${manifest.scores.size} 份分谱")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = HttpUiState(message = "登录失败：请检查本次登录码和测试服务。首次登录需要联网。") }
        }
    }
    fun logout() {
        val previous = job
        mutable.value = HttpUiState(busy = true, message = "正在退出当前会话…")
        previous?.cancel()
        job = viewModelScope.launch { previous?.join(); store.logout(); mutable.value = HttpUiState() }
    }
    fun refresh() = operation("正在检查服务端授权…") {
        val manifest = store.refresh()
        mutable.value = mutable.value.copy(manifest = manifest, message = "已刷新 · 本人授权 ${manifest.scores.size} 份分谱")
    }
    fun download() = operation("正在下载、校验并安全入库…") {
        store.download(checkNotNull(mutable.value.manifest))
        mutable.value = mutable.value.copy(message = "下载完成 · 字节与 PDF 校验通过，可断网阅读")
    }
    private fun operation(message: String, block: suspend () -> Unit) {
        if (mutable.value.busy || mutable.value.member == null) return
        mutable.value = mutable.value.copy(busy = true, message = message)
        job = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                mutable.value = if (store.login == null) HttpUiState(message = "会话或授权已失效，请重新登录")
                else mutable.value.copy(message = "同步未完成 · ${e.message ?: "测试服务不可达"}。已入库的授权谱保留，可重试。")
            } finally { mutable.value = mutable.value.copy(busy = false, revision = mutable.value.revision + 1) }
        }
    }
}
