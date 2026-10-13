package com.ensemble.scorelibrary.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.ensemble.scorelibrary.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PadScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun screenshot(name: String) {
        val profile = Profile("m", "当前成员", "小提琴 I", false)
        val content = ScreenContent.Piece("piece", "天空之城", listOf(
            VisiblePart("part", "violin", "小提琴 I"), VisiblePart("part2", "violin2", "小提琴 II")))
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            LibraryScreen(UiState.Ready(Presentation(Dashboard(emptyList(), profile), DomainResult.Success(content)), false), RootPage.Library)
        } } }
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        val colors = mutableSetOf<Int>()
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) colors += bitmap.getPixel(x, y)
        assertTrue("Rendered image must contain text and background", colors.size > 3)
        val folder = File("build/reports/screenshots").also { it.mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test @Config(qualifiers = "w800dp-h1200dp-mdpi") fun standardPad() { screenshot("standard-pad") }
    @Test @Config(qualifiers = "w1280dp-h1800dp-mdpi") fun largePortraitPad() { screenshot("large-portrait-pad") }
}
