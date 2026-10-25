package com.example

import com.example.data.crypto.ForegroundSessionLease
import com.example.data.repository.automaticSyncDelay
import org.junit.Assert.*
import org.junit.Test

class AutomaticSyncScheduleTest {
    @Test fun queuedWorkGetsRetriesWithinTheUnchangedAuthenticatedLease() {
        var elapsed = 0L
        val lease = ForegroundSessionLease { elapsed }
        lease.grant()
        assertEquals(30_000L, automaticSyncDelay(300_000, true, lease.remainingMillis(), 0))
        elapsed = 290_000L
        assertEquals(5_000L, automaticSyncDelay(300_000, true, lease.remainingMillis(), 0))
        elapsed = 300_000L
        assertEquals(0L, lease.remainingMillis())
        assertNull(automaticSyncDelay(300_000, true, lease.remainingMillis(), 0))
    }

    @Test fun backoffNeverSchedulesPastExpiryAndNoWorkKeepsConfiguredInterval() {
        assertEquals(300_000L, automaticSyncDelay(300_000, false, 900_000, 0))
        assertEquals(60_000L, automaticSyncDelay(300_000, true, 300_000, 60_000))
        assertNull(automaticSyncDelay(300_000, true, 10_000, 60_000))
    }
}
