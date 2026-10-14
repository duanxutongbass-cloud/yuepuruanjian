package com.ensemble.scorelibrary.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreviewLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun close() { store.clear() }
    @Test fun pauseAndResumeWithinOneUiTurnMustReauthorizeEvenIfCompositionNeverSawPausedValue() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val score = ResolvedConcertItem("", "p", "Synthetic title repeated to exercise a long wrapped heading", VisiblePart("part", "d", "A very long synthetic authorized part heading for stable viewport sizing"), listOf(
            ResolvedScoreSegment("b", null, "pdf", "v", "fixture", "hash", 2, 3, CacheStatus.NOT_CACHED)))
        var requests = 0
        val repository = object : PresentationRepository {
            override fun observe(memberId: String?, request: ScreenRequest) = flowOf(DomainResult.Success(
                Presentation(Dashboard(emptyList(), Profile("m", "Synthetic", "I", false)),
                    DomainResult.Success(ScreenContent.Viewer(score))))).also { requests++ }
        }
        val engine = object : PdfEngine {
            override suspend fun inspect(segments: List<ResolvedScoreSegment>) = listOf(PageSize(600, 900))
            override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float) =
                PdfFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888),
                    PdfPageTransform.fit(PageSize(600, 900), width, height, mode, scroll))
        }
        val vm = ScorePreviewViewModel(repository, engine).also { store.put("preview", it) }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            ScoreTheme { ScorePreviewPane("m", "p", vm, { _, _ -> }, {}) }
        } }
        compose.waitUntil { vm.state.value is ScorePreviewState.Ready }
        compose.waitForIdle()
        val stableRequests = requests
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("Ready metadata must not cause another viewport bind", stableRequests, requests)
        val old = vm.state.value
        val before = requests
        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(ScorePreviewState.Empty, vm.state.value)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitUntil { vm.state.value is ScorePreviewState.Ready }
        assertTrue(requests > before)
        assertNotSame(old, vm.state.value)
    }
}
