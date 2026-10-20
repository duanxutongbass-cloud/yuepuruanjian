package com.ensemble.scorelibrary.pdf

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ensemble.scorelibrary.domain.ResolvedConcertItem
import com.ensemble.scorelibrary.domain.VisiblePart
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderCancellationAcceptanceTest {
    @Test fun rapidViewportFitChangesCannotPublishObsoleteNativeFrame() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "cancel-${UUID.randomUUID()}.pdf")
        val store = ViewModelStore()
        try {
            val segment = PdfFixture.segment(file, PdfFixture.write(file))
            val score = ResolvedConcertItem("", "p", "Cancellation fixture", VisiblePart("part", "d", "I"), listOf(segment))
            val engine = NativePdfEngine(context)
            val model = PdfReaderViewModel(engine, SavedStateHandle(), PerformancePreparation(engine, UnusedPreparationAnnotations()))
            withContext(Dispatchers.Main) { store.put("reader", model); model.bind("m", "library", score); model.viewport(800, 1100) }
            withTimeout(15_000) { model.state.first { it is PdfState.Ready } }
            val initial = requireNotNull(File("/proc/self/fd").list()).size
            repeat(30) { index ->
                withContext(Dispatchers.Main) {
                    model.fit(if (index % 2 == 0) FitMode.WIDTH else FitMode.PAGE)
                    model.viewport(if (index % 2 == 0) 1280 else 800, if (index % 2 == 0) 1700 else 1100)
                }
                delay(3)
            }
            withContext(Dispatchers.Main) { model.fit(FitMode.PAGE); model.viewport(1280, 1700) }
            val ready = withTimeout(20_000) { model.state.first {
                it is PdfState.Ready && it.mode == FitMode.PAGE && it.frame.bitmap.width == 1280 && it.frame.bitmap.height == 1700
            } } as PdfState.Ready
            assertEquals(2, ready.sourcePage); assertEquals(0, ready.index)
            delay(200)
            assertSame("Cancelled earlier work must not replace final frame", ready, model.state.value)
            assertTrue(requireNotNull(File("/proc/self/fd").list()).size <= initial + 8)
            withContext(Dispatchers.Main) { model.nextPage() }
            val next = withTimeout(15_000) { model.state.first { it is PdfState.Ready && it.sourcePage == 3 } } as PdfState.Ready
            assertEquals(1, next.index)
            withContext(Dispatchers.Main) { model.detach() }
            assertEquals(PdfState.Loading, model.state.value); assertNull(model.binding.value)
        } finally { withContext(Dispatchers.Main) { store.clear() }; file.delete() }
    }
}
