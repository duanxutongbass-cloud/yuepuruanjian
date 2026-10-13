package com.ensemble.scorelibrary.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ensemble.scorelibrary.data.ImportScoreRequest
import com.ensemble.scorelibrary.data.LocalAdministration
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdministrationState(val busy: Boolean = false, val message: String? = null,
    val createdMember: String? = null, val revision: Long = 0)

@HiltViewModel
class AdministrationViewModel @Inject constructor(private val administration: LocalAdministration) : ViewModel() {
    private val mutable = MutableStateFlow(AdministrationState())
    val state = mutable.asStateFlow()
    fun acknowledgeMember() { mutable.value = mutable.value.copy(createdMember = null) }
    fun createMember(actor: String?, name: String) = runOperation {
        val id = administration.createMember(actor, name)
        if (actor == null) mutable.value = mutable.value.copy(createdMember = id)
        "成员已创建"
    }
    fun importScore(actor: String, uri: Uri, request: ImportScoreRequest) = runOperation {
        administration.importScore(actor, uri, request)
        "导入成功，可在曲库中打开曲谱"
    }
    private fun runOperation(block: suspend () -> String) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                val message = block()
                mutable.value = mutable.value.copy(message = message, revision = mutable.value.revision + 1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.value = mutable.value.copy(message = if (failure is IllegalArgumentException || failure is IllegalStateException)
                    failure.message ?: "操作失败" else "操作失败，请检查文件或重试")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
