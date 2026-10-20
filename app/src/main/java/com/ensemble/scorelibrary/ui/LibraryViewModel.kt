package com.ensemble.scorelibrary.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ensemble.scorelibrary.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface UiState {
    data object Loading : UiState
    data class Ready(val presentation: Presentation, val offline: Boolean, val missingFiles: Boolean = false) : UiState
    data class Error(val code: ErrorCode? = null) : UiState
}
data class ScreenState(val request: ScreenRequest = ScreenRequest.Home, val state: UiState = UiState.Loading,
    val memberId: String? = null)

interface DeviceAvailability {
    val online: Flow<Boolean>
    suspend fun filesAvailable(segments: List<ResolvedScoreSegment>): Boolean
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: PresentationRepository,
    private val savedState: SavedStateHandle,
    private val device: DeviceAvailability
) : ViewModel() {
    private val member = savedState.getStateFlow<String?>("memberId", null)
    private val request = MutableStateFlow<ScreenRequest>(ScreenRequest.Home)
    private val refresh = MutableStateFlow(0)
    val selectedMember: StateFlow<String?> = member
    private val mutableState = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = mutableState.asStateFlow()
    init { viewModelScope.launch { combine(member, request, refresh) { id, screen, _ -> id to screen }
        .flatMapLatest { (id, screen) ->
            combine(repository.observe(id, screen), device.online) { result, online ->
                val ui = when (result) {
                    is DomainResult.Failure -> UiState.Error(result.error.code)
                    is DomainResult.Success -> {
                        val content = result.value.content
                        if (content is DomainResult.Failure) UiState.Error(content.error.code)
                        else {
                            val viewer = (content as DomainResult.Success).value as? ScreenContent.Viewer
                            UiState.Ready(result.value, offline = !online,
                                missingFiles = viewer != null && !device.filesAvailable(viewer.score.segments))
                        }
                    }
                }
                ScreenState(screen, ui, id)
            }.onStart { emit(ScreenState(screen, memberId = id)) }
                .catch { error ->
                    if (error is CancellationException) throw error
                    emit(ScreenState(screen, UiState.Error(), id))
                }
        }.collect { mutableState.value = it } } }

    fun selectMember(id: String?) {
        if (id != member.value) { mutableState.value = ScreenState(request.value); savedState["memberId"] = id }
    }
    fun show(screen: ScreenRequest) {
        if (screen != request.value) { mutableState.value = ScreenState(screen); request.value = screen }
    }
    fun retry() { mutableState.value = ScreenState(request.value); refresh.value += 1 }
}
