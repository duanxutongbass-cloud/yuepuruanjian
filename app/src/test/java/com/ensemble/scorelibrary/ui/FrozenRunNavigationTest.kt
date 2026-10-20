package com.ensemble.scorelibrary.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.core.model.PedalAction
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.sync.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1200dp-mdpi")
class FrozenRunNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val team = TeamLibraryScope(context)
    private val runs = PerformanceRuns()
    private val allowed = MutableStateFlow(true)
    private val session = TeamSession(TEAM_SERVICE_URL, "s".repeat(43), System.currentTimeMillis() / 1000 + OFFLINE_SECONDS,
        TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Synthetic ensemble", "violinist", "Violinist", "member", true))
    private val collections = mutableListOf<TeamCollection>()
    @Volatile private var revoked = false
    private val gateway = object : TeamAccountGateway {
        override val server = TEAM_SERVICE_URL
        override val device = UUID.randomUUID().toString()
        override fun restore(): TeamSession? = if (revoked) null else session
        override fun verify(session: TeamSession): TeamSession { if (revoked) throw AccountRejected(401,"session_invalid"); return session }
        override fun signIn(server:String, username:String, password:String, name:String?, invitation:String?, consent:Boolean) = session
        override fun request(session:TeamSession, path:String, body:JSONObject?) = JSONObject().put("devices", JSONArray())
        override fun clear() {}
    }
    private val scores = object : TeamScoreSync {
        override suspend fun activate(session:TeamSession):String = team.activate(session).also { collections += it }.member
        override suspend fun sync(session:TeamSession, progress:(Int,Int)->Unit) = 1
        override fun deactivate() { allowed.value = false; team.deactivate() }
    }
    @After fun cleanup() {
        store.clear(); team.deactivate()
        collections.distinctBy { it.database }.forEach { it.database.close() }
        collections.map { it.directory.parentFile!!.canonicalFile }.distinct().forEach { target ->
            check(target.parentFile == File(context.noBackupFilesDir,"team-libraries").canonicalFile)
            target.deleteRecursively()
        }
    }
    private fun exercise(entered: Boolean) {
        val item = ResolvedConcertItem("item", "piece", "Synthetic score", VisiblePart("part","definition","Violin"),
            listOf(ResolvedScoreSegment("binding",null,"pdf","version","synthetic-file","synthetic-hash",1,2,CacheStatus.CACHED)))
        val concert = ResolvedConcert("concert","Synthetic concert",listOf(item))
        val repository = object : PresentationRepository {
            override fun observe(memberId:String?, request:ScreenRequest) = allowed.map { valid ->
                if (!valid || memberId == null) DomainResult.Failure(DomainError(ErrorCode.UNAUTHORIZED,""))
                else DomainResult.Success(Presentation(Dashboard(listOf(MemberChoice(memberId,"Member")),Profile(memberId,"Member","Violin",false),
                    listOf(PieceSummary("piece",item.title,null)),listOf(ConcertSummary(concert.id,concert.title))),
                    DomainResult.Success(when(request) {
                        ScreenRequest.Home -> ScreenContent.Home
                        is ScreenRequest.Concert -> ScreenContent.Concert(concert)
                        is ScreenRequest.Piece -> ScreenContent.Piece("piece",item.title,listOf(item.part))
                        else -> ScreenContent.Viewer(item,concert)
                    })))
            }
        }
        val engine = object : PdfEngine {
            override suspend fun inspect(segments:List<ResolvedScoreSegment>) = listOf(PageSize(600,900),PageSize(600,900))
            override suspend fun render(page:PdfPageRef,width:Int,height:Int,mode:FitMode,scroll:Float) = PdfFrame(
                Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888),PdfPageTransform.fit(PageSize(600,900),width,height,mode,scroll))
        }
        val library = LibraryViewModel(repository,SavedStateHandle(),object:DeviceAvailability {
            override val online=flowOf(true)
            override suspend fun filesAvailable(segments:List<ResolvedScoreSegment>)=true
        }).also { store.put("library",it) }
        val account = TeamAccountViewModel(gateway,scores,runs).also { store.put("account",it) }
        val pdf = PdfReaderViewModel(engine,SavedStateHandle(),PerformancePreparation(engine,TestPerformanceAnnotations(),team),runs)
            .also { store.put("pdf",it) }
        compose.setContent { MaterialTheme { TeamLibraryGate(account,library,pdf) { ScoreLibraryApp(library,pdf) } } }
        compose.waitUntil { account.state.value.libraryMember != null && !account.state.value.busy }
        compose.onNodeWithTag("nav-concerts").performClick()
        compose.onNodeWithText(concert.title).performClick()
        compose.onNodeWithTag("open-concert-reader").performClick()
        compose.waitUntil { pdf.state.value is PdfState.Ready }
        if (entered) {
            compose.runOnIdle { pdf.action(PedalAction.TOGGLE_PLAY_MODE) }
            compose.waitUntil { pdf.playMode.value }
            assertNotNull(runs.current()?.owner)
        }
        compose.waitUntil(5000) { !account.state.value.busy }
        compose.runOnIdle { check(!account.state.value.busy); revoked=true; account.sync() }
        try { compose.waitUntil(5000) {
            // IO failures resume on Android's main looper even when a full-screen viewer has no
            // pending Compose frames. Pump ready tasks without advancing the seven-day expiry.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            !account.state.value.busy && account.state.value.principal==null
        } }
        catch (error: Throwable) { println("Revocation state: ${account.state.value}; revoked=$revoked"); throw error }
        assertNull(team.active.value)
        if (entered) {
            compose.onNodeWithTag("pdf-page").assertExists()
            compose.runOnIdle { pdf.nextPage() }
            compose.waitUntil { (pdf.state.value as? PdfState.Ready)?.index==1 }
            assertTrue(pdf.playMode.value)
            compose.runOnIdle { pdf.interruptPerformance() }
            compose.waitUntil { runs.current()==null && library.selectedMember.value==null }
        } else compose.waitUntil { library.selectedMember.value==null }
        compose.onNodeWithTag("pdf-page").assertDoesNotExist()
        assertNull(runs.current())
    }
    @Test fun revokeInScene() = exercise(true)
    @Test fun ordinaryRevoke() = exercise(false)
}
