package com.ensemble.scorelibrary.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.account.*
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.sync.TeamLibraryScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class PerformanceRunsTest {
    private val items = (1..2).map { n -> ResolvedConcertItem("item$n", "piece$n", "Score $n", VisiblePart("part$n", "def$n", "Part $n"),
        listOf(ResolvedScoreSegment("binding$n", null, "pdf$n", "version$n", "file$n", "hash$n", 1, 1, CacheStatus.CACHED))) }
    private val concert = ResolvedConcert("concert", "Synthetic rehearsal", items)
    private val engine = object : PdfEngine {
        override suspend fun inspect(segments: List<ResolvedScoreSegment>) = listOf(PageSize(600,900))
        override suspend fun render(page: PdfPageRef, width: Int, height: Int, mode: FitMode, scroll: Float): PdfFrame = error("Not used")
    }
    private val mark = InkMark(kind = MarkKind.PEN, points = listOf(InkPoint(.1f,.2f),InkPoint(.2f,.3f)))
    private var locked = true
    private var changed = false
    private var loads = 0
    private val annotations = object : AnnotationStore {
        override suspend fun load(scope: AnnotationScope): AnnotationData {
            loads++
            return AnnotationData(if (changed) emptyList() else listOf(mark), emptyList(), listOf(mark), 1, 1, true, concert.id, locked)
        }
        override suspend fun save(scope: AnnotationScope, layer: AnnotationLayer, marks: List<InkMark>, generation: Long) = error("No writes from a run")
        override suspend fun publish(scope: AnnotationScope) = error("No publishing from a run")
        override suspend fun freeze(scope: AnnotationScope) = error("No freezing from a run")
    }
    private suspend fun prepare() = PerformancePreparation(engine, annotations).prepare("member", items, true) { _, _ -> }
    private fun request() = ScreenRequest.Viewer(items[0].pieceId, items[0].part.id, concert.id, items[0].itemId)
    @Test fun sceneOnly() = runBlocking {
        val runs = PerformanceRuns()
        val run = runs.begin("member", "context", items[0], concert, prepare())
        assertSame(run, runs.viewer("member", request()))
        assertNull(runs.viewer("other", request()))
        assertNull(runs.viewer("member", request().copy(itemId = "other")))
        assertNull(runs.viewer("member", ScreenRequest.Home))
        assertNull(runs.viewer("member", request().copy(concertId = null)))
        changed = true
        val page = AnnotationScope("member", "piece1", "part1", "version1", 0, "item1")
        assertEquals(listOf(mark), runs.page(run.id, page)!!.personal)
        assertFalse(runs.page(run.id, page)!!.admin)
        assertNull(runs.page(run.id, page.copy(page = 1)))
        assertNull(runs.page(run.id, page.copy(member = "other")))
        assertEquals(2, loads)
        runs.end(run.id)
        assertNull(runs.viewer("member", request()))
        assertNull(runs.page(run.id, page))
        assertNull(PerformanceRuns().current()) // A recreated process has no scene capability.
    }
    @Test fun noReplayOrPartialScene() = runBlocking {
        val runs = PerformanceRuns(); val prepared = prepare()
        val run = runs.begin("member", "context", items[0], concert, prepared)
        runs.end(run.id)
        assertTrue(runCatching { runs.begin("member", "context", items[0], concert, prepared) }.isFailure)
        val partial = prepare().let { PreparedPerformance(it.pages, it.ink.filterKeys { k -> k.item == "item1" }, null, Long.MAX_VALUE) { block -> block() } }
        assertTrue(runCatching { runs.begin("member", "context", items[0], concert, partial) }.isFailure)
        locked = false
        assertTrue(runCatching { prepare() }.isFailure)
    }
    @Test fun lateSceneCloseDoesNotStopNewScene() = runBlocking {
        val runs = PerformanceRuns()
        val old = runs.begin("member", "context", items[0], concert, prepare())
        val fresh = runs.begin("member", "context", items[0], concert, prepare())
        runs.end(old.id)
        assertSame(fresh, runs.current())
        runs.end(); assertNull(runs.current())
    }
    @Test fun finiteDeadline() {
        val now = 1_800_000_000L
        val run = FrozenPerformanceRun("id", "member", "context", items[0], concert, "account", now + 3600, emptyMap(), 1000, now)
        assertTrue(run.valid(now + 3599, 3_600_000))
        assertFalse(run.valid(now + 3600, 3_601_000))
        assertFalse(run.valid(now, 3_601_000))
        assertFalse(run.valid(now - 301, 1001))
        assertFalse(run.valid(now, 999))
    }
    @Test fun preparingDuringRevocationFails() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val team = TeamLibraryScope(context)
        val p = TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            "Synthetic ensemble", "violinist", "Violinist", "member", true)
        val c = team.activate(TeamSession(TEAM_SERVICE_URL, "synthetic-session", Long.MAX_VALUE, p))
        try {
            val prepared = PerformancePreparation(engine, annotations, team).prepare(c.member, items, true) { _, _ -> }
            team.beginAuthorizationChange(c)
            assertTrue(runCatching { PerformanceRuns().begin(c.member, "context", items[0], concert, prepared) }.isFailure)
            team.endAuthorizationChange(c)
            assertTrue(runCatching { PerformanceRuns().begin(c.member, "context", items[0], concert, prepared) }.isFailure)
            val fresh = PerformancePreparation(engine, annotations, team).prepare(c.member, items, true) { _, _ -> }
            team.deactivate()
            assertTrue(runCatching { PerformanceRuns().begin(c.member, "context", items[0], concert, fresh) }.isFailure)
        } finally {
            team.deactivate(); c.database.close()
            val target = c.directory.parentFile!!.canonicalFile
            check(target.parentFile == File(context.noBackupFilesDir, "team-libraries").canonicalFile)
            target.deleteRecursively()
        }
    }
}
