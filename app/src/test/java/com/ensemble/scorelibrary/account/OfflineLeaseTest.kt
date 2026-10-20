package com.ensemble.scorelibrary.account

import org.junit.Assert.*
import org.junit.Test

class OfflineLeaseTest {
    private val verified = 1_800_000_000L
    @Test fun sevenDaysEndsExactlyWithoutOfflineRenewal() {
        val initial = OfflineLease(verified, verified + OFFLINE_SECONDS, verified)
        val used = initial.verify(verified + 6 * 86400).verify(verified + OFFLINE_SECONDS - 1)
        assertEquals(verified + OFFLINE_SECONDS, used.expiresAt)
        assertTrue(runCatching { used.verify(verified + OFFLINE_SECONDS) }.exceptionOrNull() is OfflineUnavailable)
    }
    @Test fun shorterServerExpiryIsNeverExtended() {
        val lease = OfflineLease(verified, verified + 3600, verified)
        assertEquals(verified + 3600, lease.expiresAt)
        assertTrue(runCatching { lease.verify(verified + 3600) }.isFailure)
    }
    @Test fun firstOfflineUseAndClockRollbackFailClosed() {
        assertTrue(runCatching { OfflineLease(0, verified + OFFLINE_SECONDS, 0).verify(verified) }.isFailure)
        val lease = OfflineLease(verified, verified + OFFLINE_SECONDS, verified + 86400)
        assertTrue(runCatching { lease.verify(verified + 3600) }.isFailure)
        assertEquals(lease.lastSeenAt, lease.verify(lease.lastSeenAt - 60).lastSeenAt)
    }
    @Test fun runningClockCannotExtendLeaseOrSurviveElapsedReset() {
        val lease = RuntimeLease(verified + 3600, verified, 10_000)
        assertTrue(lease.valid(verified + 3599, 3_609_000))
        assertFalse(lease.valid(verified - 86400, 3_610_000))
        assertFalse(lease.valid(verified, 9999))
    }
}
