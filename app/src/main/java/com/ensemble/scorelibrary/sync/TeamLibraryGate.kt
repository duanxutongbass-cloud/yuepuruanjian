package com.ensemble.scorelibrary.sync

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ensemble.scorelibrary.account.TeamAccountViewModel
import com.ensemble.scorelibrary.domain.ScreenRequest
import com.ensemble.scorelibrary.ui.LibraryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

val LocalTeamLibraryMode = staticCompositionLocalOf { false }

/** Automatic IO runs outside the UI thread, only while this app is in the foreground. */
@Composable
fun TeamLibraryGate(vm: TeamAccountViewModel, library: LibraryViewModel,
    pdf: com.ensemble.scorelibrary.pdf.PdfReaderViewModel? = null, content: @Composable (Boolean) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val run = pdf?.running?.collectAsStateWithLifecycle()?.value
    val member = state.libraryMember ?: run?.takeIf { it.owner != null }?.member
    val blocked = state.principal?.joined == false || state.libraryBlocked && member == null
    val selected by library.selectedMember.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current.applicationContext
    LaunchedEffect(lifecycle, context) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { launch { vm.sync() } }
            }
            launch {
                manager.registerDefaultNetworkCallback(callback)
                try { awaitCancellation() } finally { manager.unregisterNetworkCallback(callback) }
            }
            vm.open()
            while (true) { delay(60_000); vm.sync() }
        }
    }
    var previousTeam by remember { mutableStateOf<String?>(null) }
    // An entered frozen scene may retain its bounded run when the account expires.
    // Removing that principal must not navigate the unchanged run back to Home.
    val blockedIdentity = state.principal?.accountId.takeIf { blocked }
    LaunchedEffect(member, blocked, blockedIdentity) {
        if (member != null || previousTeam != null || blocked) {
            library.selectMember(if (blocked) null else member)
            library.show(ScreenRequest.Home)
            library.retry()
        }
        previousTeam = member
    }
    val personalChoice=rememberSaveable(state.server,state.principal?.accountId) { mutableStateOf(false) }
    CompositionLocalProvider(LocalTeamLibraryMode provides (member != null),
        com.ensemble.scorelibrary.personal.LocalPersonalLibraryChoice provides personalChoice) {
        if (blocked && state.principal!=null) Box(Modifier.fillMaxSize().then(
            if(state.principal?.joined==false)Modifier.testTag("team-membership-gate") else Modifier)) {
            if(state.principal?.joined==false)com.ensemble.scorelibrary.personal.PersonalAccountWorkspace(vm,personalChoice)
            else com.ensemble.scorelibrary.personal.AccountLibraryContent(vm,personalChoice) {
                com.ensemble.scorelibrary.account.TeamLibraryRecovery(vm,state)
            }
        }
        else if (blocked) com.ensemble.scorelibrary.account.TeamAccountGate(vm, state)
        else if (member == null || selected == member) key(member) { content(member != null) }
    }
}
