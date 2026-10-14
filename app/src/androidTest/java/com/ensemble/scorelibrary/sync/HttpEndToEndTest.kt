package com.ensemble.scorelibrary.sync

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.BuildConfig
import com.ensemble.scorelibrary.MainActivity
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.database.entity.AnnotationPageEntity
import com.ensemble.scorelibrary.di.AcceptanceDependencies
import com.ensemble.scorelibrary.domain.*
import com.ensemble.scorelibrary.pdf.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Real Activity + HTTP loopback service + disk Room + native PdfRenderer. No network mocks. */
@RunWith(AndroidJUnit4::class)
class HttpEndToEndTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun await(tag: String) = compose.waitUntil(180_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun step(name: String) {
        shell("echo $name > /data/local/tmp/http-step")
        val until = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < until) {
            if (shell("cat /data/local/tmp/http-ack").trim() == name) return
            Thread.sleep(500)
        }
        error("Host did not acknowledge $name")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.waitForIdle(500, 10_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("dd of=/data/local/tmp/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        bitmap.recycle()
    }
    @Test fun loginAuthorizedDownloadAccountIsolationRetryThenServiceStoppedOfflineReader() = runBlocking {
        Assume.assumeTrue(BuildConfig.HTTP_TEST)
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("isolatedTestDevice") == "true")
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = EntryPointAccessors.fromApplication(context, AcceptanceDependencies::class.java).database()
        val args = InstrumentationRegistry.getArguments()
        val aliceCode = checkNotNull(args.getString("aliceCode"))
        val bobCode = checkNotNull(args.getString("bobCode"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: HttpSyncViewModel
            scenario.onActivity { vm = ViewModelProvider(it)[HttpSyncViewModel::class.java] }
            fun settled() = compose.waitUntil(180_000) { !vm.state.value.busy }
            fun login(account: String, code: String) {
                await("http-login")
                compose.onNodeWithTag("http-account").performTextReplacement(account)
                compose.onNodeWithTag("http-code").performTextReplacement(code)
                compose.onNodeWithTag("http-login").performClick()
                settled()
                assertNotNull("Real HTTP login must succeed: ${vm.state.value.message}", vm.state.value.member)
                await("http-download")
            }
            fun download() { compose.onNodeWithTag("http-download").performClick(); settled() }
            fun refresh() { compose.onNodeWithTag("http-refresh").performClick(); settled() }
            fun logout() { compose.onNodeWithTag("http-logout").performClick(); settled(); await("http-login") }
            login("alice", aliceCode)
            val first = vm.state.value.manifest!!.scores.single()
            val alice = vm.state.value.member!!
            assertEquals("Violin", first.partName)
            compose.onAllNodesWithText("Cello", substring = true).assertCountEquals(0)
            download()
            assertTrue(vm.state.value.message, vm.state.value.message.startsWith("下载完成"))
            val old = db.resolutionDao().versions().single { it.id == first.artifact.sourceVersionId }
            val oldBytes = File(old.managedFilePath).readBytes()
            assertEquals(first.artifact.hash, TestHttpClient.hash(File(old.managedFilePath)))
            val marks = InkCodec.encode(listOf(InkMark(kind = MarkKind.PEN, points = listOf(InkPoint(.1f, .1f), InkPoint(.2f, .2f)))))
            db.annotationDao().insertPage(AnnotationPageEntity(versionId = old.id, pageIndex = 0, ownerKey = alice, payload = marks))
            await("piece-${first.piece}")
            compose.onNodeWithTag("piece-${first.piece}").performClick()
            await("preview-page")
            capture("http-authorized-download")
            download() // duplicate must not create duplicate rows
            assertEquals(1, db.resolutionDao().versions().count { it.scorePdfId == first.pdf })
            logout()
            login("bob", bobCode)
            val second = vm.state.value.manifest!!.scores.single()
            assertEquals("Cello", second.partName)
            download()
            await("piece-${second.piece}")
            compose.onNodeWithTag("piece-${first.piece}").assertDoesNotExist()
            val bob = vm.state.value.member!!
            assertTrue(ScoreResolver(db.resolutionDao().snapshot()).resolvePart(bob, first.piece, first.part) is DomainResult.Failure)
            assertEquals(marks, db.annotationDao().page(old.id, 0, alice)!!.payload)
            // Actual Android HTTP requests cannot override principal or guess Alice's resource.
            val device = HttpSyncStore(context, db).device
            val client = TestHttpClient()
            val b = client.login("bob", bobCode, device)
            for (path in listOf("/artifacts/${first.artifact.artifactId}", "/manifest?memberId=$alice")) {
                try { client.json(path, device, b); fail("Server must reject $path") }
                catch (e: TestHttpClient.Rejected) { assertEquals(403, e.status) }
            }
            logout()
            login("alice", aliceCode)
            step("corrupt-v2")
            refresh(); download()
            assertFalse(vm.state.value.message.startsWith("下载完成"))
            assertEquals(old.id, db.libraryDao().getScorePdf(first.pdf)!!.activeVersionId)
            assertArrayEquals(oldBytes, File(old.managedFilePath).readBytes())
            step("truncate-v2")
            download()
            assertFalse(vm.state.value.message.startsWith("下载完成"))
            val partial = File(context.filesDir, "http-test").walkTopDown().filter { it.extension == "partial" }.single()
            assertTrue(partial.length() > 0)
            assertEquals(old.id, db.libraryDao().getScorePdf(first.pdf)!!.activeVersionId)
            step("resume-v2")
            download()
            assertTrue(vm.state.value.message, vm.state.value.message.startsWith("下载完成"))
            val v2 = vm.state.value.manifest!!.scores.single()
            assertEquals(v2.artifact.sourceVersionId, db.libraryDao().getScorePdf(first.pdf)!!.activeVersionId)
            assertEquals(2, db.resolutionDao().versions().count { it.scorePdfId == first.pdf })
            assertArrayEquals(oldBytes, File(old.managedFilePath).readBytes())
            assertEquals(marks, db.annotationDao().page(old.id, 0, alice)!!.payload)
            download()
            assertEquals(2, db.resolutionDao().versions().count { it.scorePdfId == first.pdf })
            step("revoke")
            refresh()
            assertTrue(vm.state.value.manifest!!.scores.isEmpty())
            compose.onNodeWithTag("piece-${first.piece}").assertDoesNotExist()
            val a = client.login("alice", aliceCode, device)
            try { client.download(a, device, v2.artifact, File(context.cacheDir, "denied.partial")); fail("revocation") }
            catch (e: TestHttpClient.Rejected) { assertEquals(403, e.status) }
            assertEquals(marks, db.annotationDao().page(old.id, 0, alice)!!.payload)
            step("regrant")
            refresh(); download()
            assertTrue(vm.state.value.message, vm.state.value.message.startsWith("下载完成"))
            step("stop-service") // host closes only this test service; no system network/security changes
            try { client.manifest(a, device); fail("Service must actually be unavailable") }
            catch (_: java.io.IOException) { }
            refresh()
            assertNotNull(vm.state.value.member)
            await("piece-${first.piece}")
            compose.onNodeWithTag("piece-${first.piece}").performClick()
            await("preview-page")
            compose.onNodeWithTag("preview-open-reader").performClick()
            await("pdf-page")
            compose.onNodeWithTag("reader-part-name").assertTextContains("Violin", substring = true)
            capture("http-service-stopped-offline-reader")
            scenario.recreate()
            await("pdf-page")
            // A new login cannot be fabricated from previously downloaded content while service is stopped.
            compose.onNodeWithTag("back").performClick()
            await("http-logout")
            logout()
            compose.onNodeWithTag("http-account").performTextReplacement("bob")
            compose.onNodeWithTag("http-code").performTextReplacement(bobCode)
            compose.onNodeWithTag("http-login").performClick()
            settled()
            assertNull(vm.state.value.member)
            compose.onNodeWithTag("piece-${second.piece}").assertDoesNotExist()
            step("finished")
        }
    }
}
