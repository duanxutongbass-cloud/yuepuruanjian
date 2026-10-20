package com.ensemble.scorelibrary.account

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeamAccountScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstLoginRendersWithoutManualConnectionSetup() {
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().padding(20.dp)) {
                    TeamAccountScreen(TeamAccountState(), { _, _, _, _, _ -> }, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
                }
            }
        }
        compose.onNodeWithTag("team-server").assertDoesNotExist()
        compose.onNodeWithTag("team-username").assertExists()
        compose.onNodeWithTag("team-password").assertExists()
        capture("team-account-builtin-service.png")
    }
    @Test fun offlineRenders() {
        compose.setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize().padding(20.dp)) {
                TeamAccountScreen(TeamAccountState(principal = TeamPrincipal("a", "m", "t", "17弦乐团", "violinist", "测试成员", "member", true),
                    offline = true, syncMessage = "离线可读 12 份已缓存乐谱；笔迹先保存在本机，联网核验后同步"),
                    { _, _, _, _, _ -> }, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {})
            } }
        }
        compose.onNodeWithTag("team-offline-status").assertExists()
        compose.onNodeWithTag("team-sync-status").assertExists()
        capture("team-account-offline-0105.png")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) colors += bitmap.getPixel(x, y)
        assertTrue("Login must render text and fields", colors.size > 3)
        val output = File("build/reports/screenshots/$name")
        val folder = checkNotNull(output.parentFile)
        check(folder.mkdirs() || folder.isDirectory)
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
