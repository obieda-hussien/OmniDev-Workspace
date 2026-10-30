package com.omnidev.workspace.domain.engine

/**
 * Thread-safe reservation of tokens for worker retries. Workers of one parallel wave can fail together
 * and each used to look at the same remaining pool, so their retries could add up past the team ceiling.
 * A retry now reserves its allowance here first and releases it when the attempt ends.
 *
 * [observed] (tokens already used by the team) keeps growing while a reserved attempt runs, so the
 * accounting is conservative: it can refuse a retry slightly early but never grant more than remains.
 */
internal class RetryTokenLedger(private val ceiling: Int, private val minGrant: Int = MIN_RETRY_TOKENS) {
    private var reserved = 0

    @Synchronized
    fun reserve(requested: Int, observed: Int): Int {
        val grant = minOf(requested, ceiling - observed - reserved)
        if (grant < minGrant) return 0
        reserved += grant
        return grant
    }

    @Synchronized
    fun release(grant: Int) {
        reserved = (reserved - grant).coerceAtLeast(0)
    }

    companion object {
        /** A retry with less than this cannot finish a task; do not start one. */
        const val MIN_RETRY_TOKENS = 20_000
    }
}
