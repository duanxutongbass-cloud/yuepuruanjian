package com.ensemble.scorelibrary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.ensemble.scorelibrary.ui.ScoreLibraryApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @javax.inject.Inject lateinit var operations: com.ensemble.scorelibrary.diagnostics.TestOperations
    private val archiveViewModel: com.ensemble.scorelibrary.backup.ArchiveViewModel by viewModels()
    private val managementViewModel: com.ensemble.scorelibrary.ui.ManagementViewModel by viewModels()
    private val pedalViewModel: com.ensemble.scorelibrary.core.pedal.PedalViewModel by viewModels()
    private val annotationViewModel: com.ensemble.scorelibrary.annotation.AnnotationViewModel by viewModels()
    private val administrationViewModel: com.ensemble.scorelibrary.ui.AdministrationViewModel by viewModels()
    private val pdfViewModel: com.ensemble.scorelibrary.pdf.PdfReaderViewModel by viewModels()
    private val previewViewModel: com.ensemble.scorelibrary.ui.ScorePreviewViewModel by viewModels()
    private val comparisonViewModel: com.ensemble.scorelibrary.compare.ScoreComparisonViewModel by viewModels()
    private val folderViewModel: com.ensemble.scorelibrary.ui.LibraryFolderViewModel by viewModels()
    private val readingShelfViewModel: com.ensemble.scorelibrary.reading.ReadingShelfViewModel by viewModels()
    private val folderBackupViewModel: com.ensemble.scorelibrary.backup.FolderBackupViewModel by viewModels()
    private val folderSyncViewModel: com.ensemble.scorelibrary.sync.TeamFolderViewModel by viewModels()
    private val libraryViewModel: com.ensemble.scorelibrary.ui.LibraryViewModel by viewModels()
    private val httpViewModel: com.ensemble.scorelibrary.sync.HttpSyncViewModel by viewModels()
    private val teamAccountViewModel: com.ensemble.scorelibrary.account.TeamAccountViewModel by viewModels()
    private val appUpdateViewModel: com.ensemble.scorelibrary.update.AppUpdateViewModel by viewModels()
    private val inkToolsViewModel: com.ensemble.scorelibrary.sync.TeamInkViewModel by viewModels()
    private val teamPublishViewModel: com.ensemble.scorelibrary.sync.TeamPublishViewModel by viewModels()
    private val teamEnsembleViewModel: com.ensemble.scorelibrary.sync.TeamEnsembleViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.ensemble.scorelibrary.ui.ScoreTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.ensemble.scorelibrary.diagnostics.LocalTestOperations provides operations,
                    com.ensemble.scorelibrary.compare.LocalScoreComparison provides comparisonViewModel,
                    com.ensemble.scorelibrary.sync.LocalTeamPublisher provides teamPublishViewModel,
                    com.ensemble.scorelibrary.sync.LocalTeamEnsemble provides teamEnsembleViewModel,
                    com.ensemble.scorelibrary.sync.LocalInkTools provides inkToolsViewModel,
                    com.ensemble.scorelibrary.update.LocalAppUpdates provides appUpdateViewModel,
                    com.ensemble.scorelibrary.account.LocalTeamAccount provides teamAccountViewModel,
                    com.ensemble.scorelibrary.ui.LocalManagement provides managementViewModel,
                    com.ensemble.scorelibrary.ui.LocalFolders provides folderViewModel,
                    com.ensemble.scorelibrary.reading.LocalReadingShelf provides readingShelfViewModel,
                    com.ensemble.scorelibrary.backup.LocalFolderBackups provides folderBackupViewModel,
                    com.ensemble.scorelibrary.sync.LocalFolderSync provides folderSyncViewModel,
                    com.ensemble.scorelibrary.core.pedal.LocalPedal provides pedalViewModel,
                    com.ensemble.scorelibrary.annotation.LocalAnnotations provides annotationViewModel,
                    com.ensemble.scorelibrary.backup.LocalArchive provides archiveViewModel) {
                    if (BuildConfig.HTTP_TEST) {
                        com.ensemble.scorelibrary.sync.HttpSessionGate(httpViewModel, libraryViewModel) {
                            ScoreLibraryApp(libraryViewModel, pdfViewModel, null, previewViewModel)
                        }
                    } else com.ensemble.scorelibrary.sync.TeamLibraryGate(teamAccountViewModel, libraryViewModel, pdfViewModel) { team ->
                        androidx.compose.runtime.CompositionLocalProvider(
                            com.ensemble.scorelibrary.ui.LocalManagement provides if (team) null else managementViewModel,
                            com.ensemble.scorelibrary.ui.LocalFolders provides folderViewModel,
                            com.ensemble.scorelibrary.backup.LocalArchive provides if (team) null else archiveViewModel) {
                            ScoreLibraryApp(libraryViewModel, pdfViewModel, if (team) null else administrationViewModel, previewViewModel)
                        }
                    }
                    if (!BuildConfig.HTTP_TEST) com.ensemble.scorelibrary.sync.TeamEnsembleDialogHost(teamAccountViewModel)
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
        val frozen = pdfViewModel.frozenViewer(libraryViewModel.selectedMember.value, envelope.request)
        val activeScore = frozen?.anchor ?: viewer?.score
        val active = activeScore != null && envelope.memberId == libraryViewModel.selectedMember.value &&
            pdfViewModel.anchor.value == envelope.memberId to activeScore &&
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
    override fun onStop() {
        operations.record(com.ensemble.scorelibrary.diagnostics.TestEvent.APP_BACKGROUND)
        pdfViewModel.interruptPerformance(); super.onStop()
    }
}
