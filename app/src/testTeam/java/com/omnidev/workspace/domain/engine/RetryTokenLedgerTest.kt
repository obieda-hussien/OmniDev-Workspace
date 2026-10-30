package com.omnidev.workspace.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryTokenLedgerTest {
    @Test fun `grants up to the request when the pool allows it`() {
        assertEquals(100_000, RetryTokenLedger(1_000_000).reserve(100_000, observed = 300_000))
    }

    @Test fun `concurrent retries never reserve more than remains`() {
        val ledger = RetryTokenLedger(ceiling = 500_000)
        val a = ledger.reserve(requested = 200_000, observed = 200_000)
        val b = ledger.reserve(requested = 200_000, observed = 200_000)
        val c = ledger.reserve(requested = 200_000, observed = 200_000)
        assertEquals(200_000, a)
        assertEquals(100_000, b)
        assertEquals(0, c)
        assertEquals(300_000, a + b)
    }

    @Test fun `release makes the tokens available again`() {
        val ledger = RetryTokenLedger(ceiling = 400_000)
        val first = ledger.reserve(300_000, observed = 100_000)
        assertEquals(0, ledger.reserve(300_000, observed = 100_000))
        ledger.release(first)
        assertEquals(300_000, ledger.reserve(300_000, observed = 100_000))
    }

    @Test fun `no retry starts when too little remains`() {
        assertEquals(0, RetryTokenLedger(ceiling = 1_000_000).reserve(200_000, observed = 990_000))
    }

    @Test fun `observed usage past the ceiling grants nothing`() {
        assertEquals(0, RetryTokenLedger(ceiling = 100_000).reserve(50_000, observed = 150_000))
    }
}
