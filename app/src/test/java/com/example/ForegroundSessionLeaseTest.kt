package com.example

import com.example.data.crypto.ForegroundSessionLease
import org.junit.Assert.*
import org.junit.Test

class ForegroundSessionLeaseTest {
    @Test fun expiresAtFiveMinutesEvenWhileUserIsActive() {
        var elapsed = 10_000L
        val lease = ForegroundSessionLease { elapsed }
        assertEquals(0L, lease.remainingMillis())
        lease.grant()
        assertEquals(300_000L, lease.remainingMillis())
        repeat(5) { elapsed += 59_999; assertTrue(lease.remainingMillis() > 0) }
        elapsed += 5
        assertEquals(0L, lease.remainingMillis())
        elapsed += 1
        assertEquals(0L, lease.remainingMillis())
    }

    @Test fun lockRevokesLeaseAndOnlyAFreshGrantCanRestoreIt() {
        var elapsed = 100L
        val lease = ForegroundSessionLease { elapsed }
        lease.grant()
        elapsed += 1000
        lease.revoke()
        assertEquals(0L, lease.remainingMillis())
        elapsed += 1000
        assertEquals(0L, lease.remainingMillis())
        lease.grant()
        assertEquals(300_000L, lease.remainingMillis())
        assertEquals(0L, ForegroundSessionLease { elapsed }.remainingMillis())
    }

    @Test fun wallClockEditsCannotExtendSessionAndMonotonicDiscontinuityFailsClosed() {
        var elapsed = 100L
        var wallClock = 1_700_000_000_000L
        val lease = ForegroundSessionLease { elapsed }
        lease.grant()
        elapsed += 60_000
        wallClock -= 86_400_000
        assertTrue(wallClock > 0)
        assertEquals(240_000L, lease.remainingMillis())
        elapsed = 99
        assertEquals(0L, lease.remainingMillis())
    }

    @Test fun authenticationProbeTimeDoesNotExtendGrant() {
        var elapsed = 1000L
        val authenticatedAt = elapsed
        val lease = ForegroundSessionLease { elapsed }
        elapsed += 1234
        lease.grant(authenticatedAt)
        assertEquals(300_000 - 1234L, lease.remainingMillis())
        assertThrows(IllegalArgumentException::class.java) { lease.grant(Long.MAX_VALUE) }
    }
}
