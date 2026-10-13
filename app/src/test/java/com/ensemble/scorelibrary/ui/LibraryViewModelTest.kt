package com.ensemble.scorelibrary.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val online = MutableStateFlow(true)
    private var files = true
    private val device = object : DeviceAvailability {
        override val online = this@LibraryViewModelTest.online
        override suspend fun filesAvailable(segments: List<ResolvedScoreSegment>) = files
    }
    private fun home() = DomainResult.Success(Presentation(Dashboard(emptyList()), DomainResult.Success(ScreenContent.Home)))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private fun vm(repository: PresentationRepository, state: SavedStateHandle = SavedStateHandle()): LibraryViewModel =
        LibraryViewModel(repository, state, device).also { store.put("vm", it) }

    @Test fun loadingEmptyAndOfflineAreDistinct() = runTest(dispatcher) {
        val results = MutableSharedFlow<DomainResult<Presentation>>(replay = 1)
        val model = vm(object : PresentationRepository {
            override fun observe(memberId: String?, request: ScreenRequest) = results
        })
        runCurrent()
        assertEquals(UiState.Loading, model.state.value.state)
        results.emit(home()); runCurrent()
        assertTrue((model.state.value.state as UiState.Ready).presentation.dashboard.pieces.isEmpty())
        online.value = false; runCurrent()
        assertTrue((model.state.value.state as UiState.Ready).offline)
    }

    @Test fun memberSwitchClearsOldDataSynchronouslyAndCancelsOldRequest() = runTest(dispatcher) {
        val old = MutableSharedFlow<DomainResult<Presentation>>(replay = 1)
        old.emit(home())
        val new = MutableSharedFlow<DomainResult<Presentation>>()
        val saved = SavedStateHandle(mapOf("memberId" to "old"))
        val model = vm(object : PresentationRepository {
            override fun observe(memberId: String?, request: ScreenRequest) = if (memberId == "old") old else new
        }, saved)
        runCurrent(); assertTrue(model.state.value.state is UiState.Ready)
        model.selectMember("new")
        assertEquals(UiState.Loading, model.state.value.state)
        assertEquals("new", saved.get<String>("memberId"))
        runCurrent()
        old.emit(DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED, "p"))); runCurrent()
        assertEquals(UiState.Loading, model.state.value.state)
    }

    @Test fun errorsAndRetryDoNotCrashOrRevealData() = runTest(dispatcher) {
        var calls = 0
        val model = vm(object : PresentationRepository {
            override fun observe(memberId: String?, request: ScreenRequest): Flow<DomainResult<Presentation>> = flow {
                if (++calls == 1) throw IllegalStateException("private database failure")
                emit(home())
            }
        })
        runCurrent(); assertEquals(UiState.Error(), model.state.value.state)
        model.retry(); assertEquals(UiState.Loading, model.state.value.state)
        runCurrent(); assertTrue(model.state.value.state is UiState.Ready)
    }

    @Test fun viewerMissingFilesAndDomainErrorsAreRepresented() = runTest(dispatcher) {
        val values = MutableStateFlow<DomainResult<Presentation>>(home())
        val model = vm(object : PresentationRepository {
            override fun observe(memberId: String?, request: ScreenRequest) = values
        })
        model.show(ScreenRequest.Viewer("p", "part")); files = false
        values.value = DomainResult.Success(Presentation(Dashboard(emptyList()), DomainResult.Success(
            ScreenContent.Viewer(ResolvedConcertItem("i", "p", "Piece", VisiblePart("part", "d", "I"), emptyList())))))
        runCurrent(); assertTrue((model.state.value.state as UiState.Ready).missingFiles)
        values.value = DomainResult.Success(Presentation(Dashboard(emptyList()), DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED, "p"))))
        runCurrent(); assertEquals(UiState.Error(ErrorCode.UNAUTHORIZED), model.state.value.state)
    }

    @Test fun savedMemberAndNewRouteReachRepository() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<String?, ScreenRequest>>()
        val model = vm(object : PresentationRepository {
            override fun observe(memberId: String?, request: ScreenRequest) = flow {
                calls += memberId to request; emit(home())
            }
        }, SavedStateHandle(mapOf("memberId" to "restored")))
        runCurrent()
        model.show(ScreenRequest.Concert("c")); runCurrent()
        assertEquals("restored" to ScreenRequest.Concert("c"), calls.last())
        assertEquals(ScreenRequest.Concert("c"), model.state.value.request)
    }
}
