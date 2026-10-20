package com.example.data.crypto

/** A session age limit; user activity and wall-clock changes never extend it. */
class ForegroundSessionLease(private val elapsedMillis: () -> Long) {
    private var grantedAt: Long? = null
    private var durationMillis = DURATION_MILLIS

    fun grant(authenticatedAt: Long = elapsedMillis(), minutes: Int = 5) {
        require(minutes in setOf(5, 10, 20, 30))
        val duration = minutes * 60_000L
        require(authenticatedAt >= 0 && authenticatedAt <= Long.MAX_VALUE - duration)
        durationMillis = duration
        grantedAt = authenticatedAt
    }

    fun revoke() { grantedAt = null }

    fun remainingMillis(): Long {
        val start = grantedAt ?: return 0
        val now = elapsedMillis()
        // A monotonic-clock discontinuity also fails closed.
        if (now < start || now >= start + durationMillis) return 0
        return start + durationMillis - now
    }

    companion object { const val DURATION_MILLIS = 300_000L }
}
