package com.ensemble.scorelibrary.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PreviewTarget(val member: String, val piece: String, val width: Int, val height: Int)
sealed interface ScorePreviewState {
    data object Empty : ScorePreviewState
    data class Loading(val target: PreviewTarget) : ScorePreviewState
    data class Ready(val target: PreviewTarget, val score: ResolvedConcertItem, val image: PreviewImage) : ScorePreviewState {
        val frame get() = image.frame
    }
    data class Failure(val target: PreviewTarget, val message: String) : ScorePreviewState
}

/** A single bounded, read-only first authorized page; never a cached guess or another member's page. */
@HiltViewModel
class ScorePreviewViewModel @Inject constructor(
    private val repository: PresentationRepository,
    private val engine: PdfEngine
) : ViewModel() {
    private val mutable = MutableStateFlow<ScorePreviewState>(ScorePreviewState.Empty)
    val state = mutable.asStateFlow()
    private var target: PreviewTarget? = null
    private var generation = 0L
    private var bindingId = 0L
    private var job: Job? = null

    fun bind(member: String?, piece: String?, width: Int, height: Int) {
        val next = if (member == null || piece == null || width <= 0 || height <= 0) null
            else PreviewTarget(member, piece, width.coerceAtMost(1200), height.coerceAtMost(1800))
        if (next == target) return
        generation++
        val binding = ++bindingId
        job?.cancel()
        target = next
        replace(next?.let { ScorePreviewState.Loading(it) } ?: ScorePreviewState.Empty)
        if (next == null) return
        job = viewModelScope.launch {
            try {
                repository.observe(next.member, ScreenRequest.Preview(next.piece))
                    .map { result ->
                        currentCoroutineContext().ensureActive()
                        if (bindingId != binding) throw CancellationException("Preview binding changed")
                        // Revoke the displayed bitmap before waiting for cancelled native work to finish.
                        val revision = ++generation
                        if (target == next) replace(ScorePreviewState.Loading(next))
                        revision to result
                    }.collectLatest { (revision, result) ->
                        var pendingImage: PreviewImage? = null
                        try {
                            val presentation = when (result) {
                                is DomainResult.Success -> result.value
                                is DomainResult.Failure -> {
                                    publish(next, revision, ScorePreviewState.Failure(next, errorText(result.error.code)))
                                    return@collectLatest
                                }
                            }
                            val content = when (val content = presentation.content) {
                                is DomainResult.Success -> content.value
                                is DomainResult.Failure -> {
                                    publish(next, revision, ScorePreviewState.Failure(next, errorText(content.error.code)))
                                    return@collectLatest
                                }
                            }
                            val score = (content as? ScreenContent.Viewer)?.score
                            if (presentation.dashboard.profile?.id != next.member || score?.pieceId != next.piece) {
                                publish(next, revision, ScorePreviewState.Failure(next, errorText(ErrorCode.UNAUTHORIZED)))
                                return@collectLatest
                            }
                            val segment = score.segments.firstOrNull() ?: throw PdfFailure(ReaderError.INVALID_PAGE_RANGE)
                            // Only the source page selected by Domain, after real file/hash/page-range validation.
                            engine.inspect(listOf(segment))
                            val frame = engine.render(PdfPageRef(segment, segment.startPage - 1),
                                next.width, next.height, FitMode.PAGE, 0f)
                            val image = PreviewImage(frame)
                            pendingImage = image
                            currentCoroutineContext().ensureActive()
                            if (publish(next, revision, ScorePreviewState.Ready(next, score, image))) pendingImage = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: PdfFailure) {
                            publish(next, revision, ScorePreviewState.Failure(next, readerErrorText(error.reason)))
                        } catch (_: Exception) {
                            publish(next, revision, ScorePreviewState.Failure(next, "预览暂时无法读取，请重试"))
                        } finally { pendingImage?.releaseOwner() }
                    }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (target == next && bindingId == binding) replace(ScorePreviewState.Failure(next, "预览暂时无法读取，请重试"))
            }
        }
    }
    private fun publish(expected: PreviewTarget, revision: Long, value: ScorePreviewState): Boolean {
        if (target != expected || generation != revision) return false
        replace(value)
        return true
    }
    private fun replace(value: ScorePreviewState) {
        val previous = (mutable.value as? ScorePreviewState.Ready)?.image
        mutable.value = value
        if (previous !== (value as? ScorePreviewState.Ready)?.image) previous?.releaseOwner()
    }
    override fun onCleared() {
        clear()
        super.onCleared()
    }
    fun clear() = bind(null, null, 0, 0)
    fun retry() {
        val previous = target ?: return
        clear()
        bind(previous.member, previous.piece, previous.width, previous.height)
    }
}
