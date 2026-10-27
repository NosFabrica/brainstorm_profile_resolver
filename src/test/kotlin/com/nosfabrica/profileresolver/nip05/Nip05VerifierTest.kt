package com.nosfabrica.profileresolver.nip05

import com.nosfabrica.profileresolver.FakeStore
import com.nosfabrica.profileresolver.ScriptedHttp
import com.nosfabrica.profileresolver.http.DomainGate
import com.nosfabrica.profileresolver.http.FetchResult
import com.nosfabrica.profileresolver.store.Nip05Status
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Id
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class Nip05VerifierTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun verifier(answers: Map<String, FetchResult>): Pair<Nip05Verifier, ScriptedHttp> {
        val scripted = ScriptedHttp(answers)
        return Nip05Verifier(scripted.http, DomainGate(FakeStore(), 0, 8)) to scripted
    }

    private fun id(value: String) = Nip05Id.parse(value)!!

    @Test
    fun verifiedMismatchAndNotFound() =
        runTest {
            val (v, _) =
                verifier(
                    mapOf(
                        id("alice@a.example").toUserUrl() to FetchResult.Ok("""{"names":{"alice":"$alice"}}"""),
                        id("bob@b.example").toUserUrl() to FetchResult.Ok("""{"names":{"bob":"$alice"}}"""),
                    ),
                )
            assertEquals(Nip05Status.VERIFIED, v.verify(id("alice@a.example"), alice))
            assertEquals(Nip05Status.MISMATCH, v.verify(id("bob@b.example"), bob))
            assertEquals(Nip05Status.NOT_FOUND, v.verify(id("carol@c.example"), bob))
        }

    @Test
    fun aDomainThatListsEveryNameAnswersItsOtherNamesFromTheList() =
        runTest {
            val list = FetchResult.Ok("""{"names":{"alice":"$alice","bob":"$bob"}}""")
            val (v, http) = verifier(mapOf(id("alice@bots.example").toUserUrl() to list))
            assertEquals(Nip05Status.VERIFIED, v.verify(id("alice@bots.example"), alice))
            assertEquals(Nip05Status.VERIFIED, v.verify(id("bob@bots.example"), bob))
            assertEquals(1, http.asked.size)
        }

    @Test
    fun aNameMissingFromTheListIsAskedForItself() =
        runTest {
            val list = FetchResult.Ok("""{"names":{"alice":"$alice","bob":"$bob"}}""")
            val carol = FetchResult.Ok("""{"names":{"carol":"$bob"}}""")
            val (v, http) = verifier(mapOf(id("alice@d.example").toUserUrl() to list, id("carol@d.example").toUserUrl() to carol))
            v.verify(id("alice@d.example"), alice)
            assertEquals(Nip05Status.VERIFIED, v.verify(id("carol@d.example"), bob))
            assertEquals(2, http.asked.size)
        }

    @Test
    fun aDownDomainIsPending() =
        runTest {
            val (v, _) = verifier(mapOf(id("alice@e.example").toUserUrl() to FetchResult.DomainDown("timeout")))
            assertEquals(Nip05Status.PENDING, v.verify(id("alice@e.example"), alice))
        }
}
