package com.ensemble.scorelibrary.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.ensemble.scorelibrary.annotation.*
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.domain.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers="w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderLayoutTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val store=ViewModelStore()
    private val renders=AtomicInteger()
    private val loads=AtomicInteger()
    private var repositoryAdmin=false
    private val annotations=object:AnnotationStore {
        override suspend fun load(scope:AnnotationScope):AnnotationData {
            loads.incrementAndGet()
            return AnnotationData(emptyList(),emptyList(),emptyList(),0,0,repositoryAdmin,null,false)
        }
        override suspend fun save(scope:AnnotationScope,layer:AnnotationLayer,marks:List<InkMark>,generation:Long) {}
        override suspend fun publish(scope:AnnotationScope) {}
        override suspend fun freeze(scope:AnnotationScope) {}
    }
    private val ink=AnnotationViewModel(annotations)
    private val engine=object:PdfEngine {
        override suspend fun inspect(segments:List<ResolvedScoreSegment>)=listOf(PageSize(595,842))
        override suspend fun render(page:PdfPageRef,width:Int,height:Int,mode:FitMode,scroll:Float):PdfFrame {
            renders.incrementAndGet()
            val transform=PdfPageTransform.fit(PageSize(595,842),width,height,mode,scroll)
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.drawColor(Color.LTGRAY)
            val paint=Paint().apply{color=Color.WHITE}
            canvas.drawRect(transform.offsetX,transform.offsetY,transform.offsetX+595*transform.scale,
                transform.offsetY+842*transform.scale,paint)
            paint.color=Color.BLACK;paint.textSize=22f
            canvas.drawText("Synthetic page top",transform.offsetX+20,transform.offsetY+40,paint)
            return PdfFrame(bitmap,transform)
        }
    }
    private val pdf=PdfReaderViewModel(engine,SavedStateHandle(),PerformancePreparation(engine,annotations))
    @After fun cleanup(){store.clear()}
    @Test fun tappingThePageShowsControlsAndTappingAgainRestoresFocus() {
        store.put("pdf",pdf);store.put("ink",ink)
        val score=ResolvedConcertItem("","p","Synthetic score",VisiblePart("part","d","Violin"),
            listOf(ResolvedScoreSegment("b",null,"pdf","v","synthetic","synthetic",1,1,CacheStatus.CACHED)))
        compose.setContent{MaterialTheme{CompositionLocalProvider(LocalAnnotations provides ink){PdfReaderScreen("m",score,pdf,canManage=false)}}}
        compose.waitUntil(10000){(pdf.state.value as? PdfState.Ready)?.rendering == false && ink.state.value.data!=null}
        compose.onNodeWithTag("reader-controls").assertDoesNotExist()
        compose.onNodeWithTag("pdf-page").performTouchInput { click() }
        compose.waitUntil(10000){!pdf.fullscreen.value && (pdf.state.value as? PdfState.Ready)?.rendering == false}
        compose.onNodeWithTag("reader-controls").assertExists()
        compose.onNodeWithTag("pdf-page").performTouchInput { click() }
        compose.waitUntil(10000){pdf.fullscreen.value && (pdf.state.value as? PdfState.Ready)?.rendering == false}
        compose.onNodeWithTag("reader-controls").assertDoesNotExist()
        assertTrue(ink.state.value.data!!.personal.isEmpty())
    }
    @Test fun teamAdminInkRoleDoesNotLoopAgainstMemberLibraryProjection() {
        repositoryAdmin=true
        val principal=com.ensemble.scorelibrary.account.TeamPrincipal(java.util.UUID.randomUUID().toString(),java.util.UUID.randomUUID().toString(),
            java.util.UUID.randomUUID().toString(),"Synthetic team","director","Director","admin",true)
        val session=com.ensemble.scorelibrary.account.TeamSession(com.ensemble.scorelibrary.account.TEAM_SERVICE_URL,"s".repeat(43),
            System.currentTimeMillis()/1000+3600,principal)
        val role=java.util.concurrent.atomic.AtomicReference("admin")
        val gateway=object:com.ensemble.scorelibrary.account.TeamAccountGateway {
            override val server=session.server
            override val device=java.util.UUID.randomUUID().toString()
            override fun restore()=session
            override fun verify(session:com.ensemble.scorelibrary.account.TeamSession)=session.copy(principal=session.principal.copy(role=role.get()))
            override fun signIn(server:String,username:String,password:String,name:String?,invitation:String?,consent:Boolean)=session
            override fun clear() {}
            override fun request(session:com.ensemble.scorelibrary.account.TeamSession,path:String,body:org.json.JSONObject?)=
                org.json.JSONObject().put("devices",org.json.JSONArray()).put("members",org.json.JSONArray())
        }
        val account=com.ensemble.scorelibrary.account.TeamAccountViewModel(gateway)
        store.put("account",account);store.put("pdf",pdf);store.put("ink",ink)
        val score=ResolvedConcertItem("","p","Synthetic score",VisiblePart("part","d","Violin"),
            listOf(ResolvedScoreSegment("b",null,"pdf","v","synthetic","synthetic",1,1,CacheStatus.CACHED)))
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalAnnotations provides ink,
            com.ensemble.scorelibrary.sync.LocalTeamLibraryMode provides true,
            com.ensemble.scorelibrary.account.LocalTeamAccount provides account) {
            PdfReaderScreen("m",score,pdf,canManage=false)
        } } }
        compose.runOnIdle { account.open();pdf.setFullscreen(false) }
        compose.waitUntil(10000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            account.state.value.principal!=null && !account.state.value.busy && ink.state.value.data!=null && pdf.state.value is PdfState.Ready
        }
        compose.onNodeWithTag("annotation-音乐符号").performClick()
        compose.onNodeWithTag("symbol-f").assertIsEnabled()
        val loaded=loads.get();val rendered=renders.get()
        repeat(5) { compose.mainClock.advanceTimeBy(200);compose.waitForIdle() }
        assertEquals("Authority reload must settle",loaded,loads.get())
        assertEquals(rendered,renders.get())
        assertTrue("Team administrator retains actual shared ink role",ink.state.value.data!!.admin)
        compose.onNodeWithTag("symbol-f").assertIsEnabled().performClick()
        compose.onNodeWithTag("music-symbol-palette").assertDoesNotExist()
        compose.runOnIdle { ink.layer(AnnotationLayer.SHARED) }
        compose.onNodeWithTag("annotation-发布当前页").assertExists()
        compose.runOnIdle { role.set("member");account.refresh() }
        compose.waitUntil(10000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            account.state.value.principal?.role=="member" && !account.state.value.busy && !ink.state.value.busy
        }
        compose.onNodeWithTag("annotation-发布当前页").assertDoesNotExist()
        assertEquals("Role revalidation resets editing to the personal layer",AnnotationLayer.PERSONAL,ink.state.value.layer)
        compose.runOnIdle { ink.layer(AnnotationLayer.SHARED) }
        compose.onNodeWithTag("music-symbol-picker").assertIsNotEnabled()
        val afterRevoke=loads.get()
        repeat(5) { compose.mainClock.advanceTimeBy(200);compose.waitForIdle() }
        assertEquals("Stale repository role must not restart refresh indefinitely",afterRevoke,loads.get())
    }
    @Test fun toolbarAndStampDoNotCoverPage() {
        store.put("pdf",pdf);store.put("ink",ink)
        val score=ResolvedConcertItem("","p","Synthetic score",VisiblePart("part","d","Violin"),
            listOf(ResolvedScoreSegment("b",null,"pdf","v","synthetic","synthetic",1,1,CacheStatus.CACHED)))
        compose.setContent{MaterialTheme{CompositionLocalProvider(LocalAnnotations provides ink){PdfReaderScreen("m",score,pdf,canManage=false)}}}
        compose.waitUntil(10000){pdf.state.value is PdfState.Ready}
        compose.runOnIdle{pdf.setFullscreen(false)}
        compose.waitUntil(10000){pdf.state.value is PdfState.Ready && ink.state.value.data!=null}
        val controls=compose.onNodeWithTag("reader-controls").fetchSemanticsNode().boundsInRoot
        val page=compose.onNodeWithTag("pdf-page").fetchSemanticsNode().boundsInRoot
        assertTrue("Page starts below toolbar",page.top>=controls.bottom)
        val beforeOpen=renders.get()
        compose.onNodeWithTag("music-symbol-picker").performClick()
        compose.waitUntil(10000){pdf.state.value is PdfState.Ready}
        compose.onNodeWithTag("music-symbol-palette").assertExists()
        val after=compose.onNodeWithTag("pdf-page").fetchSemanticsNode().boundsInRoot
        val expanded=compose.onNodeWithTag("reader-controls").fetchSemanticsNode().boundsInRoot
        assertTrue(after.top>=expanded.bottom)
        assertEquals("Opening the palette must not resize the PDF",page,after)
        assertEquals(controls,expanded)
        val palette=compose.onNodeWithTag("music-symbol-palette").fetchSemanticsNode().boundsInRoot
        assertTrue("Compact palette",palette.width<=420 && palette.height<=250)
        val count=renders.get()
        compose.mainClock.advanceTimeBy(1500)
        compose.waitForIdle()
        assertEquals("Palette must settle without a resize loop",count,renders.get())
        assertEquals("Opening the palette must not render a new PDF bitmap",beforeOpen,count)
        repeat(3) {
            compose.onNodeWithText("收起符号").performClick()
            compose.onNodeWithTag("annotation-音乐符号").performClick()
            compose.onNodeWithTag("pdf-page").assertExists()
            assertEquals(page,compose.onNodeWithTag("pdf-page").fetchSemanticsNode().boundsInRoot)
        }
        assertEquals(beforeOpen,renders.get())
        lateinit var bitmap:Bitmap
        compose.runOnUiThread{
            val view=compose.activity.window.decorView
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val folder=File("build/reports/screenshots").apply{check(isDirectory||mkdirs())}
        File(folder,"reader-toolbar-stamp.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
