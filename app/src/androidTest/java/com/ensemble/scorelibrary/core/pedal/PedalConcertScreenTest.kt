package com.ensemble.scorelibrary.core.pedal

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.MainActivity
import com.ensemble.scorelibrary.annotation.AnnotationViewModel
import com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase
import com.ensemble.scorelibrary.core.database.entity.*
import com.ensemble.scorelibrary.core.model.PedalAction
import com.ensemble.scorelibrary.pdf.*
import com.ensemble.scorelibrary.ui.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 31)
class PedalConcertScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefix = UUID.randomUUID().toString()
    private val member = "$prefix-member"
    private val concert = "$prefix-concert"
    private val files = mutableListOf<File>()
    private fun id(suffix: String) = "$prefix-$suffix"
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    private fun reader() = ViewModelProvider(compose.activity)[PdfReaderViewModel::class.java]
    private fun pedal() = ViewModelProvider(compose.activity)[PedalViewModel::class.java]
    private fun annotations() = ViewModelProvider(compose.activity)[AnnotationViewModel::class.java]
    private fun waitForFirstItem() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("concert-item-${id("item0")}").fetchSemanticsNodes().size == 1
        }
    }
    private fun ready(n: Int, page: Int) {
        try { compose.waitUntil(30_000) {
            val vm = reader(); val state = vm.state.value as? PdfState.Ready
            vm.binding.value?.second?.itemId == id("item$n") && state?.sourcePage == page &&
                !annotations().state.value.busy && annotations().state.value.scope?.piece == id("piece$n") &&
                annotations().state.value.scope?.page == page - 1 && annotations().state.value.data != null &&
                compose.onAllNodesWithContentDescription("Flow piece ${n + 1}，PDF 第 $page 页")
                    .fetchSemanticsNodes().size == 1
        } } catch (error: Throwable) {
            throw AssertionError("Expected piece=$n page=$page; PDF=${reader().state.value}; binding=${reader().binding.value}; pedal=${pedal().state.value}; ink=${annotations().state.value}", error)
        }
        assertEquals(id("part$n-${n % 2}"), reader().binding.value!!.second.part.id)
        assertEquals(id("version$n"), (reader().state.value as PdfState.Ready).reference.segment.versionId)
        compose.onNodeWithTag("reader-part-name").assertTextEquals(if (n % 2 == 0) "Violin I" else "Violin II")
        compose.onNodeWithTag("reader-piece-title").assertTextEquals("Flow piece ${n + 1}")
        compose.onNodeWithText("Unauthorized cello").assertDoesNotExist()
    }
    private fun key(code: Int, repeat: Int = 0, release: Boolean = true) {
        compose.waitUntil(15_000) { compose.activity.window.decorView.hasWindowFocus() }
        Thread.sleep(170)
        compose.runOnIdle {
            val time = SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, repeat, 0, -1, 0))
            if (release) compose.activity.dispatchKeyEvent(KeyEvent(time, time + 1, KeyEvent.ACTION_UP, code, 0, 0, -1, 0))
        }
    }
    private fun screenOn(view: android.view.View): Boolean = view.keepScreenOn ||
        (view is android.view.ViewGroup && (0 until view.childCount).any { screenOn(view.getChildAt(it)) })
    private fun seed(locked: Boolean = true) {
        val db = Room.databaseBuilder(context, ScoreLibraryDatabase::class.java, "ensemble-score-library.db")
            .addMigrations(ScoreLibraryDatabase.MIGRATION_1_2, ScoreLibraryDatabase.MIGRATION_2_3).build()
        try { runBlocking {
            val dao = db.libraryDao()
            (0..2).forEach { n -> dao.insertPartDefinition(PartDefinitionEntity(id = id("definition$n"),
                name = "$prefix-definition$n")) }
            dao.insertMember(MemberEntity(id = member, displayName = "Flow member $prefix", defaultPartDefinitionId = id("definition0")))
            dao.insertPermission(MemberPartPermissionEntity(memberId = member, partDefinitionId = id("definition0")))
            dao.insertMember(MemberEntity(id = id("admin"), displayName = "Flow admin $prefix",
                role = com.ensemble.scorelibrary.core.model.MemberRole.ADMIN, defaultPartDefinitionId = id("definition0")))
            dao.insertPermission(MemberPartPermissionEntity(memberId = id("admin"), partDefinitionId = id("definition0")))
            dao.insertConcert(ConcertEntity(id = concert, title = "Five-piece pedal concert"))
            (0..4).forEach { n ->
                dao.insertPiece(PieceEntity(id = id("piece$n"), title = "Flow piece ${n + 1}"))
                (0..2).forEach { part -> dao.insertPiecePart(PiecePartEntity(id = id("part$n-$part"), pieceId = id("piece$n"),
                    partDefinitionId = id("definition$part"), displayName = when(part) { 0 -> "Violin I"; 1 -> "Violin II"; else -> "Unauthorized cello" })) }
                if (n % 2 == 1) {
                    dao.insertExtraPermission(MemberPiecePartPermissionEntity(memberId = member, piecePartId = id("part$n-1")))
                    dao.insertAssignment(PiecePartAssignmentEntity(memberId = member, piecePartId = id("part$n-1")))
                }
                val file = File(context.filesDir, id("score$n.pdf")).also { files += it }
                val hash = PdfFixture.write(file)
                dao.insertScorePdf(ScorePdfEntity(id = id("pdf$n"), title = "PDF $n"))
                dao.insertScorePdfVersion(ScorePdfVersionEntity(id = id("version$n"), scorePdfId = id("pdf$n"), versionNumber = 1,
                    managedFilePath = file.path, originalUri = "content://test/$n", fileHash = hash, fileSizeBytes = file.length(), pageCount = 4))
                dao.updateScorePdf(ScorePdfEntity(id = id("pdf$n"), title = "PDF $n", activeVersionId = id("version$n")))
                (0..1).forEach { part -> dao.insertPartScoreBinding(PartScoreBindingEntity(id = id("binding$n-$part"),
                    piecePartId = id("part$n-$part"), scorePdfId = id("pdf$n"), startPage = 2+part, endPage = 3+part)) }
            }
            listOf(4,1,3,0,2).forEach { n -> dao.insertConcertItem(ConcertItemEntity(id = id("item$n"), concertId = concert,
                pieceId = id("piece$n"), sortOrder = n * 2)) }
            if (locked) com.ensemble.scorelibrary.annotation.AnnotationRepository(db).freeze(
                com.ensemble.scorelibrary.annotation.AnnotationScope(id("admin"), id("piece0"), id("part0-0"), id("version0"), 1, id("item0")))
        } } finally { db.close() }
    }
    private fun screenshot(name: String, stage: String = "phase6") {
        var captured: Bitmap? = null
        // Layout-triggered rendering can start after the ViewModel first becomes ready.
        compose.waitUntil(30_000) {
            val candidate = compose.onRoot().captureToImage().asAndroidBitmap()
            var ink = 0
            for (y in 250 until candidate.height - 100 step 4)
                for (x in candidate.width / 4 until candidate.width * 3 / 4 step 4) {
                    val color = candidate.getPixel(x, y)
                    if (android.graphics.Color.red(color) < 120 && android.graphics.Color.green(color) < 120) ink++
                }
            if (ink > 50) { captured = candidate; true } else false
        }
        val bitmap = requireNotNull(captured)
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/$stage-$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        var ink = 0
        for (y in 250 until bitmap.height - 100 step 4) for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 4) {
            val color = bitmap.getPixel(x,y)
            if (android.graphics.Color.red(color) < 120 && android.graphics.Color.green(color) < 120) ink++
        }
        assertTrue("Actual native score must not be blank", ink > 20)
    }
    @After fun cleanup() { files.forEach(File::delete); shell("wm size reset"); shell("wm density reset")
        shell("svc wifi enable"); shell("svc data enable") }

    @Test fun realActivityLearnsKeysAndPlaysFiveDomainAssignedPiecesWithoutTouchOrDialogs() {
        // Only suppress Android's first-use fullscreen tutorial in this emulator test, not app dialogs.
        shell("settings put secure immersive_mode_confirmations confirmed")
        shell("svc wifi disable"); shell("svc data disable")
        shell("wm size 800x1200"); shell("wm density 160"); compose.activityRule.scenario.recreate()
        seed()
        compose.runOnIdle { ViewModelProvider(compose.activity)[LibraryViewModel::class.java].retry() }
        compose.onNodeWithTag("nav-profile").performClick()
        compose.waitUntil(20_000) { !pedal().state.value.busy }
        compose.onNodeWithText("选择成员").performClick()
        compose.onNodeWithText("Flow member $prefix").performClick()
        compose.runOnIdle { pedal().reset() }
        compose.waitUntil(10_000) { !pedal().state.value.busy }
        compose.onNodeWithTag("pedal-learn-NEXT_PAGE").performScrollTo().performClick()
        key(KeyEvent.KEYCODE_F1)
        compose.waitUntil(10_000) { !pedal().state.value.busy && pedal().state.value.learning == null }
        compose.onNodeWithTag("pedal-learn-PREVIOUS_PAGE").performScrollTo().performClick()
        key(KeyEvent.KEYCODE_F2)
        compose.waitUntil(10_000) { !pedal().state.value.busy && pedal().state.value.learning == null }
        compose.onNodeWithTag("pedal-test").performScrollTo().performClick(); key(KeyEvent.KEYCODE_F1)
        compose.onNodeWithTag("pedal-feedback").assertTextContains("下一页", substring = true)
        assertNull(reader().binding.value)
        compose.onNodeWithTag("pedal-test").performClick()
        assertEquals(PedalAction.NEXT_PAGE, runBlocking { AndroidPedalSettingsStore(context).load() }.mapping("default")[KeyEvent.KEYCODE_F1])
        compose.onNodeWithTag("nav-concerts").performClick()
        compose.onNodeWithTag("concert-$concert").performClick()
        waitForFirstItem()
        compose.onNodeWithTag("concert-item-${id("item0")}").performClick()
        ready(0,2)
        compose.waitUntil(15_000) { (ViewModelProvider(compose.activity)[LibraryViewModel::class.java].state.value.state
            as? com.ensemble.scorelibrary.ui.UiState.Ready)?.offline == true }
        compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(10_000) { reader().playMode.value }
        ready(0,2); compose.waitForIdle()
        assertEquals(PerformanceState.Ready(10), reader().performance.value)
        assertTrue(screenOn(compose.activity.window.decorView))
        compose.onNodeWithTag("annotation-手写").assertDoesNotExist()
        screenshot("standard-pad")
        key(KeyEvent.KEYCODE_F1, repeat = 1); ready(0,2)
        for (position in 1..9) {
            key(KeyEvent.KEYCODE_F1)
            val n = position / 2; ready(n, 2 + n % 2 + position % 2)
        }
        key(KeyEvent.KEYCODE_F1); ready(4,3)
        assertEquals("已到最后一页", reader().notice.value)
        key(KeyEvent.KEYCODE_F2); ready(4,2)
        key(KeyEvent.KEYCODE_F2); ready(3,4)
        shell("wm size 1280x1800"); compose.activityRule.scenario.recreate()
        compose.waitUntil(30_000) { reader().playMode.value && reader().performance.value is PerformanceState.Ready }
        ready(3,4)
        assertTrue(reader().playMode.value)
        screenshot("large-pad")
        key(KeyEvent.KEYCODE_F1); ready(4,2)
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("bottom-navigation").assertExists()
        assertFalse(reader().playMode.value)
        assertFalse(screenOn(compose.activity.window.decorView))
        key(KeyEvent.KEYCODE_F1)
        assertNull(reader().binding.value)
    }
    @Test fun actualPreparationRejectsUnlockedAndMissingLastPdfBeforeTurningAnyPage() {
        shell("settings put secure immersive_mode_confirmations confirmed")
        seed(locked = false)
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[LibraryViewModel::class.java].selectMember(member)
            ViewModelProvider(compose.activity)[LibraryViewModel::class.java].retry()
            pedal().reset()
        }
        compose.waitUntil(10_000) { !pedal().state.value.busy }
        compose.onNodeWithTag("nav-concerts").performClick()
        compose.onNodeWithTag("concert-$concert").performClick()
        waitForFirstItem()
        compose.onNodeWithTag("concert-item-${id("item0")}").performClick(); ready(0,2)
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(20_000) { reader().performance.value is PerformanceState.Failure && reader().state.value is PdfState.Ready }
        compose.onNodeWithTag("performance-error").assertTextContains("未锁定", substring = true)
        assertFalse(reader().playMode.value); assertFalse(screenOn(compose.activity.window.decorView))
        val db = Room.databaseBuilder(context, ScoreLibraryDatabase::class.java, "ensemble-score-library.db")
            .addMigrations(ScoreLibraryDatabase.MIGRATION_1_2, ScoreLibraryDatabase.MIGRATION_2_3).build()
        try { runBlocking { com.ensemble.scorelibrary.annotation.AnnotationRepository(db).freeze(
            com.ensemble.scorelibrary.annotation.AnnotationScope(id("admin"), id("piece0"), id("part0-0"), id("version0"), 1, id("item0"))) } }
        finally { db.close() }
        assertTrue(files[4].delete())
        key(KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(30_000) { (reader().performance.value as? PerformanceState.Failure)?.message?.contains("Flow piece 5") == true }
        compose.onNodeWithTag("performance-error").assertTextContains("文件缺失", substring = true)
        assertFalse(reader().playMode.value); ready(0,2)
    }
    @Test fun repeatedOfflineFullAppReadingPreservesInkAndRestoresProgramme() {
        shell("settings put secure immersive_mode_confirmations confirmed")
        shell("svc wifi disable"); shell("svc data disable")
        shell("wm size 800x1200"); shell("wm density 160"); compose.activityRule.scenario.recreate()
        seed()
        compose.runOnIdle {
            ViewModelProvider(compose.activity)[LibraryViewModel::class.java].selectMember(member)
            ViewModelProvider(compose.activity)[LibraryViewModel::class.java].retry()
        }
        compose.onNodeWithTag("nav-concerts").performClick()
        compose.onNodeWithTag("concert-$concert").performClick()
        waitForFirstItem()
        compose.onNodeWithTag("concert-item-${id("item0")}").performClick(); ready(0,2)
        compose.onNodeWithTag("annotation-手写").performClick()
        compose.onNodeWithTag("annotation-canvas").performTouchInput {
            swipe(androidx.compose.ui.geometry.Offset(width * .4f, height * .4f),
                androidx.compose.ui.geometry.Offset(width * .55f, height * .45f), durationMillis = 150)
        }
        compose.waitUntil(15_000) { !annotations().state.value.busy && annotations().state.value.data?.personal?.isNotEmpty() == true }
        val marks = annotations().state.value.data!!.personal
        compose.onNodeWithTag("play-mode").performClick()
        compose.waitUntil(30_000) { reader().playMode.value && reader().performance.value is PerformanceState.Ready }
        ready(0,2)
        val seconds = (InstrumentationRegistry.getArguments().getString("phase9SoakSeconds")?.toLongOrNull() ?: 180L).coerceIn(30, 7200)
        val started = SystemClock.elapsedRealtime(); val samples = org.json.JSONArray()
        val initialFd = requireNotNull(File("/proc/self/fd").list()).size
        var position = 0; var direction = 1; var turns = 0; var recreations = 0
        while (SystemClock.elapsedRealtime() - started < seconds * 1000) {
            if (position == 9) direction = -1 else if (position == 0) direction = 1
            val button = compose.onNodeWithTag(if (direction > 0) "next-page" else "previous-page")
            compose.waitUntil(15_000) {
                !button.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            button.assertIsEnabled().performClick()
            position += direction; turns++
            ready(position / 2, 2 + (position / 2) % 2 + position % 2)
            compose.onNodeWithTag("bottom-navigation").assertDoesNotExist()
            if (turns % 30 == 0) {
                shell(if (recreations % 2 == 0) "wm size 1280x1800" else "wm size 800x1200")
                compose.activityRule.scenario.recreate(); recreations++
                compose.waitUntil(30_000) { reader().playMode.value && reader().performance.value is PerformanceState.Ready }
                ready(position / 2, 2 + (position / 2) % 2 + position % 2)
            }
            if (position == 0) assertEquals(marks, annotations().state.value.data!!.personal)
            if (turns % 20 == 0) {
                val memory = android.os.Debug.MemoryInfo(); android.os.Debug.getMemoryInfo(memory)
                samples.put(org.json.JSONObject().put("turns", turns).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("pssKb", memory.totalPss).put("fd", requireNotNull(File("/proc/self/fd").list()).size))
            }
        }
        val finalFd = requireNotNull(File("/proc/self/fd").list()).size
        assertTrue("Full App descriptor growth: $initialFd -> $finalFd", finalFd <= initialFd + 12)
        compose.waitForIdle(); assertTrue(screenOn(compose.activity.window.decorView))
        screenshot("ui-soak", "phase9")
        val report = org.json.JSONObject().put("scope", "Real MainActivity/Compose/Room/native PDF; five small fixture scores; not hours-long real rehearsal")
            .put("elapsedMs", SystemClock.elapsedRealtime() - started).put("turns", turns).put("recreations", recreations)
            .put("initialFd", initialFd).put("finalFd", finalFd).put("samples", samples)
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/phase9-ui-soak.json")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write(report.toString(2).toByteArray()) }
        ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        compose.onNodeWithTag("back").performClick(); compose.onNodeWithTag("bottom-navigation").assertExists()
        assertFalse(reader().playMode.value)
    }
}
