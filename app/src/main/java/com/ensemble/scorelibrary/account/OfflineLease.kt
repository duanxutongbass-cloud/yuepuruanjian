package com.ensemble.scorelibrary.account

import java.io.IOException

const val OFFLINE_SECONDS = 7L * 24 * 60 * 60
internal const val CLOCK_SKEW_SECONDS = 300L

class OfflineUnavailable(message: String = "请联网核验团队账号和乐谱授权") : IOException(message)

/** Only a lease saved after successful HTTPS verification may open remembered content. */
data class OfflineLease(val verifiedAt: Long, val sessionExpiresAt: Long, val lastSeenAt: Long) {
    val expiresAt: Long get() = minOf(sessionExpiresAt,
        if (verifiedAt > Long.MAX_VALUE - OFFLINE_SECONDS) Long.MAX_VALUE else verifiedAt + OFFLINE_SECONDS)
    fun verify(now: Long): OfflineLease {
        if (verifiedAt <= 0 || lastSeenAt < verifiedAt || now < maxOf(verifiedAt, lastSeenAt) - CLOCK_SKEW_SECONDS ||
            now >= expiresAt) throw OfflineUnavailable("离线核验期限已到或设备时间变化，请联网重新核验")
        return copy(lastSeenAt = maxOf(lastSeenAt, now))
    }
}

/** A wall-clock rollback cannot extend a lease while this process remains alive. */
internal class RuntimeLease(private val wallDeadline: Long, wallNow: Long, elapsedNow: Long) {
    private val started = elapsedNow
    private val remainingMillis = ((wallDeadline - wallNow).coerceAtLeast(0).coerceAtMost(OFFLINE_SECONDS)) * 1000
    fun valid(wallNow: Long, elapsedNow: Long): Boolean = wallNow < wallDeadline && elapsedNow >= started &&
        elapsedNow - started < remainingMillis
}
