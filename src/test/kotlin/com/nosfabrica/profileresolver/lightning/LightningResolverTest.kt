package com.nosfabrica.profileresolver.lightning

import com.nosfabrica.profileresolver.FakeStore
import com.nosfabrica.profileresolver.ScriptedHttp
import com.nosfabrica.profileresolver.http.DomainGate
import com.nosfabrica.profileresolver.http.FetchResult
import com.nosfabrica.profileresolver.store.LnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LightningResolverTest {
    private val signer = "b".repeat(64)
    private val alice = "https://wallet.example/.well-known/lnurlp/alice"
    private val bob = "https://wallet.example/.well-known/lnurlp/bob"

    private fun resolver(
        answers: Map<String, FetchResult>,
        store: FakeStore = FakeStore(),
    ): Pair<LightningResolver, ScriptedHttp> {
        val scripted = ScriptedHttp(answers)
        return LightningResolver(scripted.http, DomainGate(store, 0, 8), store) to scripted
    }

    private fun zaps(key: String = signer) = FetchResult.Ok("""{"tag":"payRequest","allowsNostr":true,"nostrPubkey":"${key.uppercase()}"}""")

    @Test
    fun readsTheSignerWhenTheAddressDoesZaps() =
        runTest {
            val (r, _) = resolver(mapOf(alice to zaps()))
            val answer = r.resolve(alice)
            assertEquals(LnStatus.OK, answer.status)
            assertEquals(signer, answer.signer)
        }

    @Test
    fun usersSharingAnAddressCostOneRequest() =
        runTest {
            // Hold the fetch so all five lookups overlap: the in-flight one is shared, not the cache.
            val hold = CompletableDeferred<Unit>()
            val scripted = ScriptedHttp(mapOf(alice to zaps()), hold)
            val store = FakeStore()
            val r = LightningResolver(scripted.http, DomainGate(store, 0, 8), store)
            val lookups = (1..5).map { async { r.resolve(alice) } }
            testScheduler.advanceUntilIdle()
            hold.complete(Unit)
            lookups.awaitAll().forEach { assertEquals(signer, it.signer) }
            assertEquals(listOf(alice), scripted.asked)
        }

    @Test
    fun aSettledAddressIsNotFetchedAgainOnALaterRun() =
        runTest {
            val store = FakeStore()
            resolver(mapOf(alice to zaps()), store).first.resolve(alice)
            val (later, http) = resolver(mapOf(alice to zaps()), store)
            assertEquals(LnStatus.OK, later.resolve(alice).status)
            assertEquals(emptyList(), http.asked)
        }

    @Test
    fun aDownDomainLeavesItsAddressesPendingAndUnasked() =
        runTest {
            val store = FakeStore()
            val (r, http) = resolver(mapOf(alice to FetchResult.DomainDown("timeout"), bob to zaps()), store)
            assertEquals(LnStatus.PENDING, r.resolve(alice).status)
            assertEquals(LnStatus.PENDING, r.resolve(bob).status)
            assertEquals(listOf(alice), http.asked, "bob's domain was already down")
            assertNull(store.answers[alice], "pending is never cached")
        }

    @Test
    fun noZapsNotFoundAndRefusedAreSettled() =
        runTest {
            val noZaps = "https://a.example/.well-known/lnurlp/x"
            val gone = "https://b.example/.well-known/lnurlp/x"
            val refused = "https://c.example/.well-known/lnurlp/x"
            val (r, _) =
                resolver(
                    mapOf(
                        noZaps to FetchResult.Ok("""{"tag":"payRequest"}"""),
                        gone to FetchResult.AddressGone(404),
                        refused to FetchResult.Refused("non-public host"),
                    ),
                )
            assertEquals(LnStatus.NO_ZAPS, r.resolve(noZaps).status)
            assertEquals(LnStatus.NOT_FOUND, r.resolve(gone).status)
            assertEquals(LnStatus.INVALID, r.resolve(refused).status)
        }

    @Test
    fun allowsNostrMustBeABooleanAndTheKeyHex() {
        assertNull(LightningResolver.parse("""{"allowsNostr":"true","nostrPubkey":"$signer"}"""))
        assertNull(LightningResolver.parse("""{"allowsNostr":true,"nostrPubkey":"npub1xyz"}"""))
        assertNull(LightningResolver.parse("""{"allowsNostr":false,"nostrPubkey":"$signer"}"""))
        assertNull(LightningResolver.parse("not json"))
        assertEquals(signer, LightningResolver.parse("""{"allowsNostr":true,"nostrPubkey":"$signer"}"""))
    }

    @Test
    fun onlyA404Or410SettlesAnAddressAsNotFound() =
        runTest {
            val store = FakeStore()
            val gone = "https://a.example/.well-known/lnurlp/gone"
            val blocked = "https://b.example/.well-known/lnurlp/blocked"
            val (r, _) = resolver(mapOf(gone to FetchResult.AddressGone(410), blocked to FetchResult.AddressGone(403)), store)
            assertEquals(LnStatus.NOT_FOUND, r.resolve(gone).status)
            // A 403 is usually a firewall: not the user's doing, and asked again next run.
            assertEquals(LnStatus.UNKNOWN, r.resolve(blocked).status)
            assertNull(store.answers[blocked], "unknown is never cached")
        }

    @Test
    fun aServerErrorLeavesTheAddressPendingAndItsDomainUp() =
        runTest {
            val store = FakeStore()
            val broken = "https://wallet.example/.well-known/lnurlp/broken"
            val (r, http) = resolver(mapOf(broken to FetchResult.ServerError(500), alice to zaps()), store)
            assertEquals(LnStatus.PENDING, r.resolve(broken).status)
            assertEquals(LnStatus.OK, r.resolve(alice).status)
            assertEquals(listOf(broken, alice), http.asked)
        }
}
