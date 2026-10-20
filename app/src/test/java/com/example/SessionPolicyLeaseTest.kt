package com.example

import com.example.data.crypto.ForegroundSessionLease
import org.junit.Assert.*
import org.junit.Test

class SessionPolicyLeaseTest {
    @Test fun selectedDurationsExpireFromOriginalAuthenticationWithoutActivityRenewal() {
        for (minutes in listOf(5, 10, 20, 30)) {
            var elapsed = 1000L
            val authenticatedAt = elapsed
            val lease = ForegroundSessionLease { elapsed }
            elapsed += 3000
            lease.grant(authenticatedAt, minutes)
            assertEquals(minutes * 60_000L - 3000, lease.remainingMillis())
            elapsed = authenticatedAt + minutes * 60_000L - 1
            assertEquals(1L, lease.remainingMillis())
            elapsed++
            assertEquals(0L, lease.remainingMillis())
        }
    }
    @Test fun revokeDiscontinuityAndUnsupportedDurationsRemainFailClosed() {
        var elapsed = 1000L
        val lease = ForegroundSessionLease { elapsed }
        for (duration in listOf(0, -1, 6, 60, Int.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) { lease.grant(elapsed, duration) }
        lease.grant(elapsed, 30); lease.revoke(); assertEquals(0L, lease.remainingMillis())
        lease.grant(elapsed, 20); elapsed--; assertEquals(0L, lease.remainingMillis())
        assertThrows(IllegalArgumentException::class.java) { lease.grant(Long.MAX_VALUE, 30) }
    }
    @Test fun policyCommitUsingSameAuthenticationTimestampDoesNotRestartTheClock() {
        var elapsed = 1000L
        val lease = ForegroundSessionLease { elapsed }
        lease.grant(elapsed)
        elapsed += 120_000
        lease.grant(1000L, 10)
        assertEquals(480_000L, lease.remainingMillis())
        elapsed += 30_000
        lease.grant(1000L, 5)
        assertEquals(150_000L, lease.remainingMillis())
    }
}
