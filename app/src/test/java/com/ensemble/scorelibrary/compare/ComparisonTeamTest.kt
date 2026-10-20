package com.ensemble.scorelibrary.compare

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ComparisonTeamTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val team = TeamLibraryScope(context)
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val collections = mutableListOf<TeamCollection>()
    private var reads = 0
    private val session = TeamSession(TEAM_SERVICE_URL, "synthetic", Long.MAX_VALUE,
        TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "合成团队", "synthetic", "合成管理员", "admin", true))
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() {
        models.clear(); team.deactivate(); Dispatchers.resetMain()
        collections.distinctBy { it.database }.forEach { it.database.close() }
        val root = File(context.noBackupFilesDir, "team-libraries").canonicalFile
        collections.map { it.directory.parentFile!!.canonicalFile }.distinct().forEach { dir ->
            check(dir.parentFile == root && dir.name.matches(Regex("[a-f0-9-]{36}")))
            dir.deleteRecursively()
        }
    }
    private fun activate(s: TeamSession = session) = team.activate(s).also { collections += it }
    private fun model(): ScoreComparisonViewModel {
        val repository = object : PresentationRepository {
            override fun observe(memberId: String?, request: ScreenRequest): Flow<DomainResult<Presentation>> {
                reads++
                val part = VisiblePart("part", "violin", "小提琴")
                val content = if (request is ScreenRequest.Piece) ScreenContent.Piece("p", "合成曲目", listOf(part)) else
                    ScreenContent.Viewer(ResolvedConcertItem("", "p", "合成曲目", part, listOf(
                        ResolvedScoreSegment("bind", null, "pdf", "v1", "fixture", "hash", 1, 1, CacheStatus.NOT_CACHED))))
                return flowOf(DomainResult.Success(Presentation(Dashboard(emptyList(), Profile(memberId!!, "合成成员", null, true)), DomainResult.Success(content))))
            }
        }
        val engine = object : PdfEngine {
            override suspend fun inspect(segments: List<ResolvedScoreSegment>) = listOf(PageSize(400, 600))
            override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float) =
                PdfFrame(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888), PdfPageTransform.fit(PageSize(400, 600), width, height, mode))
        }
        return ScoreComparisonViewModel(repository, engine, team).also { models.put("compare", it) }
    }
    private fun select(vm: ScoreComparisonViewModel, side: Int) {
        vm.viewport(side, 100, 150); vm.choosePiece(side, "p"); dispatcher.scheduler.runCurrent()
        vm.choosePart(side, "part"); dispatcher.scheduler.runCurrent()
        assertNotNull(vm.state.value.panes[side].image)
    }
    @Test fun sevenDayLeaseExpiryRetiresBothImagesWithoutRevivingLocalFallback() = runTest(dispatcher) {
        try {
        val collection = activate(); val vm = model(); val owner = Any(); vm.open(collection.member, owner)
        select(vm, 0); select(vm, 1)
        val images = vm.state.value.panes.map { it.image!! }
        collection.authorizeUntil(System.currentTimeMillis() / 1000 - 1)
        assertFalse(vm.current(collection.member, owner))
        advanceTimeBy(1000); runCurrent()
        assertNull(vm.state.value.member); assertTrue(images.all { !it.canDisplay() && it.frame.bitmap.isRecycled })
        val before = reads; vm.choosePiece(0, "p"); runCurrent(); assertEquals(before, reads)
        } finally { models.clear() }
    }
    @Test fun renewedCollectionForSameAccountInvalidatesOldHandleAndNeedsFreshChoice() = runTest(dispatcher) {
        try {
        val old = activate(); val vm = model(); vm.open(old.member, Any()); select(vm, 0)
        val image = vm.state.value.panes[0].image!!
        val renewed = activate(session.copy(token = "renewed")); runCurrent()
        assertEquals(old.member, renewed.member); assertNull(vm.state.value.member); assertFalse(image.canDisplay())
        vm.open(renewed.member, Any()); select(vm, 0)
        assertNotSame(image, vm.state.value.panes[0].image)
        } finally { models.clear() }
    }
    @Test fun adminRoleCannotOpenAnotherMemberEvenWithPermissiveTestRepository() = runTest(dispatcher) {
        try {
        activate(); val vm = model(); vm.open("forged", Any()); runCurrent(); vm.choosePiece(0, "p"); runCurrent()
        assertNull(vm.state.value.member); assertEquals(0, reads)
        } finally { models.clear() }
    }
    @Test fun sameAccountDifferentTeamRetiresBothSidesAndKeepsPersonalIdentity()=runTest(dispatcher) {
        try {
            val old=activate();val vm=model();val handle=Any();vm.open(old.member,handle)
            select(vm,0);select(vm,1);val images=vm.state.value.panes.map {it.image!!}
            val nextSession=session.copy(token="new-team-session",principal=session.principal.copy(teamId=UUID.randomUUID().toString(),teamName="另一个团队",role="member"))
            val next=activate(nextSession);runCurrent()
            assertNotEquals(old.key,next.key);assertNotEquals(old.member,next.member)
            assertNull(vm.state.value.member);assertFalse(vm.current(old.member,handle))
            assertTrue(images.all {!it.canDisplay() && it.frame.bitmap.isRecycled})
            val readBefore=reads;vm.choosePiece(0,"p");runCurrent();assertEquals(readBefore,reads)
            vm.open(next.member,Any());select(vm,0);assertNotSame(images[0],vm.state.value.panes[0].image)
            assertEquals(session.principal.accountId,nextSession.principal.accountId)
        } finally {models.clear()}
    }
}
