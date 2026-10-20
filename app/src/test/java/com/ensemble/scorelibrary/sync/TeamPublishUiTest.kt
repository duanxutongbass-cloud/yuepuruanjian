package com.ensemble.scorelibrary.sync

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ensemble.scorelibrary.account.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeamPublishUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val admin = TeamPrincipal(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        "Synthetic ensemble", "director", "Director", "admin", true)
    @Test fun chooserEnabledBeforeMetadata() {
        var chosen = false
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(), admin, emptyList(), { chosen = true }, {}) } }
        ui.onNodeWithTag("team-choose-pdf").assertIsEnabled().performClick(); assertTrue(chosen)
    }
    @Test fun memberCannotUpload() {
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(), admin.copy(role = "member"), emptyList(), {}, {}) } }
        ui.onNodeWithTag("team-choose-pdf").assertDoesNotExist()
        ui.onNodeWithText("仅团队管理员可以上传乐谱").assertExists()
    }
    @Test fun previewAndRecipients() {
        val member = TeamMember(UUID.randomUUID().toString(), "Player", "player", true, false)
        val initial = TeamPublishDetails("合成曲", "01 小提琴", "0101002", "01 合成分类", setOf(admin.accountId))
        var submitted: TeamPublishDetails? = null
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file = File("synthetic.pdf"), name = "0101002合成曲.pdf", pages = 1,
            size = 1024, suggestion = initial), admin, listOf(member), {}, { submitted = it.single() }) } }
        ui.onNodeWithTag("team-publish-preview").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithTag("team-publish-validation").assertTextContains("勾选",substring=true)
        ui.onNodeWithTag("team-publish-library-only").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-recipient-${member.id}").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick(); assertNull(submitted)
        ui.onNodeWithText("返回修改").performClick(); assertNull(submitted)
        ui.onNodeWithTag("team-publish-preview").performClick()
        ui.onNodeWithTag("team-publish-submit").performClick()
        assertEquals(setOf(admin.accountId, member.id), submitted!!.recipients)
        ui.waitForIdle()
        lateinit var bitmap: android.graphics.Bitmap
        ui.runOnUiThread {
            val view = ui.activity.window.decorView
            bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val target = File("build/reports/screenshots/team-admin-upload.png"); target.parentFile!!.mkdirs()
        target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun batchKeepsPerFileEditsAndRecipients() {
        val member = TeamMember(UUID.randomUUID().toString(), "Cellist", "cello", true, false)
        val d = TeamPublishDetails("合成曲", "01 小提琴", "0101002", "01 合成分类", setOf(admin.accountId))
        val one = TeamPublishJob("synthetic", File("one.pdf"), "0101002合成曲.pdf", "hash", 1024, 1, d)
        val two = one.copy(file = File("two.pdf"), name = "0201002合成曲.pdf", details = d.copy(part = "02 大提琴", number = "0201002"))
        var saved: List<TeamPublishDetails>? = null
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file = one.file, suggestion = d, batch = listOf(one, two)), admin, listOf(member), {}, { saved = it }) } }
        ui.onNodeWithTag("team-publish-part").performScrollTo().performTextReplacement("01 第一小提琴")
        ui.onNodeWithTag("team-publish-file-1").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-part").performScrollTo().assertTextContains("02 大提琴")
        ui.onNodeWithTag("team-publish-library-only").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-recipient-${member.id}").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-file-0").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-part").performScrollTo().assertTextContains("01 第一小提琴")
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").performClick()
        assertEquals(2, saved!!.size)
        assertEquals(setOf(admin.accountId), saved!![0].recipients)
        assertEquals(setOf(admin.accountId, member.id), saved!![1].recipients)
    }
    @Test fun sixFilesExplainMissingMetadataThenSubmitAllSix() {
        val d=TeamPublishDetails("合成曲","01 Violin","0101002","01 分类",setOf(admin.accountId))
        val jobs=(1..6).map { i -> TeamPublishJob("synthetic",File("file-$i.pdf"),"$i.pdf","hash",1024,1,
            d.copy(title=if(i==6) "" else d.title,number="0${i}01002",part="0$i Instrument",category=if(i==6) "" else d.category)) }
        var saved:List<TeamPublishDetails>?=null
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file=jobs.first().file,batch=jobs),admin,emptyList(),{},{saved=it}) } }
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-validation").assertTextContains("第 6 份",substring=true)
        assertNull(saved)
        ui.onNodeWithTag("team-publish-file-5").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-title").performScrollTo().performTextReplacement(d.title)
        ui.onNodeWithTag("team-publish-category").performScrollTo().performTextReplacement(d.category)
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").performClick()
        assertEquals(6,saved!!.size)
        assertEquals((1..6).map { "0${it}01001" },saved!!.map { it.number })
    }
    @Test fun supplementShowsExistingVoicesAndRequiresExplicitMetadataAlignment() {
        val d=TeamPublishDetails("另一写法","02 Cello","0201002","01 自定义分类",setOf(admin.accountId))
        val old=ExistingTeamScore("0101002","原曲名","01 原分类","01 Violin",false)
        var saved:List<TeamPublishDetails>?=null
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file=File("supplement.pdf"),suggestion=d,existing=listOf(old)),
            admin,emptyList(),{},{saved=it}) } }
        ui.onNodeWithTag("team-publish-title").assertTextContains(d.title)
        ui.onNodeWithTag("team-publish-use-existing").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-title").performScrollTo().assertTextContains(old.title)
        ui.onNodeWithTag("team-publish-number").assertTextContains(d.number)
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").performClick()
        assertEquals(old.title,saved!!.single().title)
        assertEquals(d.number,saved!!.single().number)
    }
    @Test fun automaticNumberPreviewDoesNotReuseFilenameSequence() {
        val d = TeamPublishDetails("新曲", "03 中提琴", "0301999", "01 久石让", setOf(admin.accountId))
        var saved: List<TeamPublishDetails>? = null
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file = File("auto.pdf"), suggestion = d), admin, emptyList(), {}, { saved = it }) } }
        ui.onNodeWithTag("team-publish-number").performScrollTo().assertTextContains("0301001")
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").performClick()
        assertEquals("0301001", saved!!.single().number)
    }
    @Test fun duplicateNeedsExplicitVersionAndAllocatesNewGlobalNumber() {
        val d = TeamPublishDetails("天空之城", "01 第一小提琴", "", "01 久石让", setOf(admin.accountId))
        val old = ExistingTeamScore("0101007", d.title, d.category, d.part, false)
        var saved: List<TeamPublishDetails>? = null
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file = File("duplicate.pdf"), suggestion = d, existing = listOf(old)), admin, emptyList(), {}, { saved = it }) } }
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-validation").assertTextContains("相同乐器", substring = true)
        assertNull(saved)
        ui.onNodeWithTag("team-publish-new-version").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-title").performScrollTo().assertTextContains("天空之城（第二版）")
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").performClick()
        assertEquals("0101001", saved!!.single().number)
        assertEquals("天空之城（第二版）", saved!!.single().title)
        assertEquals("天空之城", old.title)
    }
    @Test fun specialNumberChangeRequiresFreshConfirmation() {
        val d = TeamPublishDetails("新曲", "03 中提琴", "0301042", "01 久石让", setOf(admin.accountId))
        var saved: List<TeamPublishDetails>? = null
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file = File("special.pdf"), suggestion = d), admin, emptyList(), {}, { saved = it }) } }
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-manual-number").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-number").performScrollTo().assertTextContains("0301042")
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-validation").assertTextContains("勾选", substring = true)
        assertNull(saved)
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").performClick()
        assertEquals("0301042", saved!!.single().number)
    }
    @Test fun ambiguousSameTitleAllowsExplicitExistingGroupSelection() {
        val d = TeamPublishDetails("天空之城", "03 中提琴", "", "01 久石让", setOf(admin.accountId))
        val old = ExistingTeamScore("0101007", d.title, d.category, "01 第一小提琴", false)
        var saved: List<TeamPublishDetails>? = null
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file = File("ambiguous.pdf"), suggestion = d,
            existing = listOf(old, old.copy(number = "0101009"))), admin, emptyList(), {}, { saved = it }) } }
        ui.onNodeWithTag("team-publish-match-0101009").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-number").performScrollTo().assertTextContains("0301009")
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").performClick()
        assertEquals("0301009", saved!!.single().number)
    }
    @Test fun discardRequiresConfirmationAndPreservesOtherFilesEditedSpecialNumber() {
        val d = TeamPublishDetails("新曲甲", "03 中提琴", "0301042", "01 久石让", setOf(admin.accountId))
        val one = TeamPublishJob("synthetic", File("cancel-one.pdf"), "one.pdf", "hash", 100, 1, d)
        val two = one.copy(file = File("keep-two.pdf"), name = "two.pdf", details = d.copy(title = "新曲乙", number = "0301050"))
        val state = mutableStateOf(TeamPublishState(file = one.file, batch = listOf(one, two)))
        var discarded = -1
        var saved: List<TeamPublishDetails>? = null
        ui.setContent { MaterialTheme { TeamPublishForm(state.value, admin, emptyList(), {}, { saved = it }, onDiscard = { i, rows ->
            discarded = i
            val jobs = state.value.batch.mapIndexedNotNull { n, job -> if (n == i) null else job.copy(details = rows[n]) }
            state.value = state.value.copy(file = jobs.first().file, batch = jobs)
        }) } }
        ui.onNodeWithTag("team-publish-file-1").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-manual-number").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-number").performScrollTo().performTextReplacement("0301055")
        ui.onNodeWithTag("team-publish-file-0").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-discard").performScrollTo().performClick()
        assertEquals(-1, discarded)
        ui.onNodeWithTag("team-publish-discard-confirm").performClick()
        assertEquals(0, discarded)
        ui.onNodeWithTag("team-publish-number").performScrollTo().assertTextContains("0301055")
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").performClick()
        assertEquals("0301055", saved!!.single().number)
        assertEquals("新曲乙", saved!!.single().title)
    }
    @Test fun duplicateComparisonRequiresExplicitOpenAndDoesNotPublish() {
        val d = TeamPublishDetails("天空之城", "01 第一小提琴", "", "01 久石让", setOf(admin.accountId))
        val old = ExistingTeamScore("0101007", d.title, d.category, d.part, false, "existing-artifact")
        var choice: Pair<Int, ExistingTeamScore>? = null
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file = File("compare.pdf"), suggestion = d, existing = listOf(old)),
            admin, emptyList(), {}, { fail("Comparison cannot publish") }, onCompare = { i, match, _ -> choice = i to match }) } }
        assertNull(choice)
        ui.onNodeWithTag("team-publish-compare-0101007").performScrollTo().performClick()
        assertEquals(0 to old, choice)
    }
    @Test fun defaultTotalLibraryRequiresNoMemberSelectionAndDropsStaleAudience() {
        val member = TeamMember(UUID.randomUUID().toString(), "Player", "player", true, false)
        val d = TeamPublishDetails("全新乐曲", "03 中提琴", "", "01 久石让", setOf(admin.accountId, member.id))
        var submitted: List<TeamPublishDetails>? = null
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file = File("private.pdf"), suggestion = d),
            admin, listOf(member), {}, { submitted = it }) } }
        ui.onNodeWithTag("team-publish-library-only").assertIsOn()
        ui.onNodeWithTag("team-publish-recipient-${member.id}").assertDoesNotExist()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().assertIsEnabled()
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").assertIsEnabled().performClick()
        assertEquals(setOf(admin.accountId), submitted!!.single().recipients)
    }
    @Test fun largeQueuePagesFileChoicesInsteadOfRenderingOneHundredButtons() {
        val jobs = (1..101).map { n -> TeamPublishJob("synthetic", File("queue-$n.pdf"), "$n.pdf", "hash", 100, 1,
            TeamPublishDetails("独立曲$n", "01 小提琴", "0101${n.toString().padStart(3, '0')}", "01 久石让", setOf(admin.accountId))) }
        ui.setContent { MaterialTheme { TeamPublishForm(TeamPublishState(file = jobs.first().file, batch = jobs), admin, emptyList(), {}, {}) } }
        ui.onNodeWithTag("team-publish-file-0").assertExists()
        ui.onNodeWithTag("team-publish-file-10").assertDoesNotExist()
        ui.onNodeWithTag("team-publish-files-next").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-file-10").assertExists()
        ui.onNodeWithTag("team-publish-file-0").assertDoesNotExist()
        ui.onNodeWithTag("team-publish-files-previous").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-file-0").assertExists()
    }
    @Test fun refreshedCatalogClosesOldPreviewAndRequiresNewConfirmation() {
        val d = TeamPublishDetails("全新乐曲", "03 中提琴", "", "01 久石让", setOf(admin.accountId))
        val state = mutableStateOf(TeamPublishState(file = File("race.pdf"), suggestion = d))
        var saved: List<TeamPublishDetails>? = null
        ui.setContent { MaterialTheme { TeamPublishForm(state.value, admin, emptyList(), {}, { saved = it }) } }
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").assertExists()
        ui.runOnIdle { state.value = state.value.copy(existing = listOf(ExistingTeamScore("0101001", "另一首", "01 久石让", "01 第一小提琴", false)), catalogCheck = 1) }
        ui.onNodeWithTag("team-publish-submit").assertDoesNotExist()
        ui.onNodeWithTag("team-publish-number").performScrollTo().assertTextContains("0301002")
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().assertIsOff()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-validation").assertTextContains("勾选", substring = true)
        assertNull(saved)
        ui.onNodeWithTag("team-publish-confirm").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-preview").performScrollTo().performClick()
        ui.onNodeWithTag("team-publish-submit").performClick()
        assertEquals("0301002", saved!!.single().number)
    }
}
