package com.example.data.repository

/** Never renews authentication. Pending work gets a retry before the lease expires. */
internal fun automaticSyncDelay(interval: Long, pending: Boolean, remaining: Long, backoff: Long): Long? {
    if (remaining <= 1_000L || backoff >= remaining) return null
    val desired = if (pending) minOf(interval, 30_000L, remaining / 2) else minOf(interval, remaining)
    return maxOf(desired, backoff).takeIf { it <= remaining }
}
