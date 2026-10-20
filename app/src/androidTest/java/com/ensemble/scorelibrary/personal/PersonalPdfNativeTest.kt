package com.ensemble.scorelibrary.personal

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.ensemble.scorelibrary.account.TEAM_SERVICE_URL
import com.ensemble.scorelibrary.backup.ArchiveFormat
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** Native Android PDF acceptance. Not executable in JVM Robolectric (no PdfRenderer shadow).
 * Runs only on synthetic files created here; never targets user data or a NAS score. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=29)
class PersonalPdfNativeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun productionImporterAndViewerReadAllPagesAndPreserveExternalOriginal()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val testRoot=File(app.cacheDir,"personal-acceptance-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context=object:ContextWrapper(app) { override fun getNoBackupFilesDir()=testRoot }
        val owner=PersonalOwner(TEAM_SERVICE_URL,UUID.randomUUID().toString())
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("score.pdf").use { it.readBytes() }
        val uri=requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"personal-acceptance-${UUID.randomUUID()}.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf")
        }))
        try {
            app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            val store=PersonalDocumentStore(context,{owner},{SecretKeySpec(ByteArray(32){23},"AES")})
            val snapshot=store.importPdf(owner.key,uri,"本人原谱")
            val document=snapshot.documents.single()
            assertEquals(4,document.pages)
            assertEquals(ArchiveFormat.digest(bytes),document.hash)
            assertArrayEquals(bytes,store.open(owner.key,document.id).file.readBytes())
            compose.setContent { MaterialTheme { PersonalPdfViewer(owner.key,document,{store.open(owner.key,it)},{}) } }
            for(page in 1..document.pages) {
                compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("个人PDF第${page}页").fetchSemanticsNodes().size==1 }
                compose.onNodeWithText("$page / ${document.pages}").assertExists()
                if(page<document.pages)compose.onNodeWithText("下一页").assertIsEnabled().performClick()
            }
            compose.onNodeWithText("下一页").assertIsNotEnabled();compose.onNodeWithText("上一页").assertIsEnabled()
            val bitmap=compose.onNodeWithContentDescription("个人PDF第4页").captureToImage().asAndroidBitmap()
            val colors=mutableSetOf<Int>();for(y in 0 until bitmap.height step 8)for(x in 0 until bitmap.width step 8)colors+=bitmap.getPixel(x,y)
            assertTrue(colors.size>10)
            val proof=File(app.cacheDir,"personal-acceptance-last-page.png")
            proof.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            val trash=store.trash(owner.key,document.id,snapshot.revision,true)
            store.permanent(store.preview(owner.key,setOf(document.id),trash.revision),true)
            assertTrue(store.read(owner.key).documents.isEmpty())
            assertArrayEquals(bytes,app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            app.contentResolver.delete(uri,null,null)
            check(testRoot.canonicalFile.parentFile==app.cacheDir.canonicalFile)
            testRoot.deleteRecursively()
        }
    }
}
