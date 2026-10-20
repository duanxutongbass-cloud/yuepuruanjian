package com.ensemble.scorelibrary.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ensemble.scorelibrary.core.model.CacheStatus
import com.ensemble.scorelibrary.data.AndroidDeviceAvailability
import com.ensemble.scorelibrary.domain.ResolvedScoreSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DeviceAvailabilityTest {
    @Test fun fileChecksAcceptManagedFilesAndRejectOutsidePathsAndStaleCaches() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val managed = File(context.filesDir, "availability-test.pdf")
        val outside = File(context.cacheDir, "outside-test.pdf")
        managed.writeText("test"); outside.writeText("test")
        try { runBlocking {
            val device = AndroidDeviceAvailability(context)
            val segment = ResolvedScoreSegment("b", null, "pdf", "v", managed.name, "hash", 1, 1, CacheStatus.NOT_CACHED)
            assertTrue(device.filesAvailable(listOf(segment)))
            assertFalse(device.filesAvailable(listOf(segment.copy(managedFilePath = outside.path))))
            assertFalse(device.filesAvailable(listOf(segment.copy(managedFilePath = "missing"))))
            assertTrue(device.filesAvailable(listOf(segment.copy(managedFilePath = "missing", cacheStatus = CacheStatus.CACHED, cachedFilePath = managed.path))))
            assertFalse(device.filesAvailable(listOf(segment.copy(managedFilePath = "missing", cacheStatus = CacheStatus.OUTDATED, cachedFilePath = managed.path))))
        } } finally { managed.delete(); outside.delete() }
    }
}
