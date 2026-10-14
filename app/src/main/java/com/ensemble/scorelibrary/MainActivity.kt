package com.ensemble.scorelibrary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.ensemble.scorelibrary.ui.ScoreLibraryApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val archiveViewModel: com.ensemble.scorelibrary.backup.ArchiveViewModel by viewModels()
    private val managementViewModel: com.ensemble.scorelibrary.ui.ManagementViewModel by viewModels()
    private val pedalViewModel: com.ensemble.scorelibrary.core.pedal.PedalViewModel by viewModels()
    private val annotationViewModel: com.ensemble.scorelibrary.annotation.AnnotationViewModel by viewModels()
    private val administrationViewModel: com.ensemble.scorelibrary.ui.AdministrationViewModel by viewModels()
    private val pdfViewModel: com.ensemble.scorelibrary.pdf.PdfReaderViewModel by viewModels()
    private val previewViewModel: com.ensemble.scorelibrary.ui.ScorePreviewViewModel by viewModels()
    private val libraryViewModel: com.ensemble.scorelibrary.ui.LibraryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.ensemble.scorelibrary.ui.ScoreTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.ensemble.scorelibrary.ui.LocalManagement provides managementViewModel,
                    com.ensemble.scorelibrary.core.pedal.LocalPedal provides pedalViewModel,
                    com.ensemble.scorelibrary.annotation.LocalAnnotations provides annotationViewModel,
                    com.ensemble.scorelibrary.backup.LocalArchive provides archiveViewModel) {
                    ScoreLibraryApp(libraryViewModel, pdfViewModel, administrationViewModel, previewViewModel)
                }
            }
        }
    }
    // Implements Activity's public dispatch contract; Core's inherited bridge is marked library-restricted.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || !window.decorView.hasWindowFocus()) return super.dispatchKeyEvent(event)
        val envelope = libraryViewModel.state.value
        val ready = envelope.state as? com.ensemble.scorelibrary.ui.UiState.Ready
        val viewer = ((ready?.presentation?.content as? com.ensemble.scorelibrary.domain.DomainResult.Success)?.value
            as? com.ensemble.scorelibrary.domain.ScreenContent.Viewer)
        val active = viewer != null && envelope.memberId == libraryViewModel.selectedMember.value &&
            pdfViewModel.anchor.value == envelope.memberId to viewer.score &&
            !getSystemService(android.view.inputmethod.InputMethodManager::class.java).isAcceptingText
        val ink = annotationViewModel.state.value
        val blocked = ink.transitionBlocked
        val dispatch = pedalViewModel.handle(event, active, blocked)
        if (dispatch.consumed) {
            if (dispatch.action != com.ensemble.scorelibrary.core.model.PedalAction.NO_OP) pdfViewModel.action(dispatch.action)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onPause() { pedalViewModel.resetInput(); super.onPause() }
}
