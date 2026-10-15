package com.example.data.crypto

/** A session age limit; user activity and wall-clock changes never extend it. */
class ForegroundSessionLease(private val elapsedMillis: () -> Long) {
    private var grantedAt: Long? = null

    fun grant(authenticatedAt: Long = elapsedMillis()) {
        require(authenticatedAt >= 0 && authenticatedAt <= Long.MAX_VALUE - DURATION_MILLIS)
        grantedAt = authenticatedAt
    }

    fun revoke() { grantedAt = null }

    fun remainingMillis(): Long {
        val start = grantedAt ?: return 0
        val now = elapsedMillis()
        // A monotonic-clock discontinuity also fails closed.
        if (now < start || now >= start + DURATION_MILLIS) return 0
        return start + DURATION_MILLIS - now
    }

    companion object { const val DURATION_MILLIS = 300_000L }
}
