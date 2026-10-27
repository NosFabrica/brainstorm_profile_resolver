package com.nosfabrica.profileresolver.http

import com.nosfabrica.profileresolver.FakeStore
import com.nosfabrica.profileresolver.store.DomainHealth
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainGateTest {
    private var now = 1_000_000L
    private val store = FakeStore()
    private val gate = DomainGate(store, perDomainIntervalMs = 0, maxConcurrent = 4, nowSeconds = { now })

    @Test
    fun aDownDomainIsNotAskedUntilItsBackoffEnds() =
        runTest {
            var calls = 0
            gate.call("down.example") { calls++; FetchResult.DomainDown("timeout") }
            assertEquals(DomainHealth(1, now + 3600), store.domains["down.example"])

            assertNull(gate.call("down.example") { calls++; FetchResult.Ok("") })
            assertEquals(1, calls)

            now += 3601
            gate.call("down.example") { calls++; FetchResult.Ok("") }
            assertEquals(2, calls)
            assertNull(store.domains["down.example"], "an answer clears the backoff")
        }

    @Test
    fun backoffGrowsToWeekly() {
        assertEquals(listOf(1L, 6, 24, 72, 168, 168), (1..6).map { DomainGate.backoffSeconds(it) / 3600 })
    }

    @Test
    fun anotherRunRespectsTheStoredBackoff() =
        runTest {
            store.domains["down.example"] = DomainHealth(2, now + 100)
            var called = false
            assertNull(gate.call("down.example") { called = true; FetchResult.Ok("") })
            assertTrue(!called)
        }

    @Test
    fun aMissingAddressDoesNotMarkTheDomainDown() =
        runTest {
            gate.call("up.example") { FetchResult.AddressGone(404) }
            assertNull(store.domains["up.example"])
        }
}
