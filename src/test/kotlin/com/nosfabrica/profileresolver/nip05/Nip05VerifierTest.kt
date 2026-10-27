package com.nosfabrica.profileresolver.nip05

import com.nosfabrica.profileresolver.FakeStore
import com.nosfabrica.profileresolver.ScriptedHttp
import com.nosfabrica.profileresolver.http.DomainGate
import com.nosfabrica.profileresolver.http.FetchResult
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
    fun verifiedListedToOtherAndNotListed() =
        runTest {
            val (v, _) =
                verifier(
                    mapOf(
                        id("alice@a.example").toUserUrl() to FetchResult.Ok("""{"names":{"alice":"$alice"}}"""),
                        id("bob@b.example").toUserUrl() to FetchResult.Ok("""{"names":{"bob":"$alice"}}"""),
                    ),
                )
            assertEquals(Nip05Check.VERIFIED, v.verify(id("alice@a.example"), alice))
            assertEquals(Nip05Check.LISTED_TO_OTHER, v.verify(id("bob@b.example"), bob))
            assertEquals(Nip05Check.NOT_LISTED, v.verify(id("carol@c.example"), bob))
        }

    @Test
    fun aDomainThatListsEveryNameAnswersItsOtherNamesFromTheList() =
        runTest {
            val list = FetchResult.Ok("""{"names":{"alice":"$alice","bob":"$bob"}}""")
            val (v, http) = verifier(mapOf(id("alice@bots.example").toUserUrl() to list))
            assertEquals(Nip05Check.VERIFIED, v.verify(id("alice@bots.example"), alice))
            assertEquals(Nip05Check.VERIFIED, v.verify(id("bob@bots.example"), bob))
            assertEquals(1, http.asked.size)
        }

    @Test
    fun aNameMissingFromTheListIsAskedForItself() =
        runTest {
            val list = FetchResult.Ok("""{"names":{"alice":"$alice","bob":"$bob"}}""")
            val carol = FetchResult.Ok("""{"names":{"carol":"$bob"}}""")
            val (v, http) = verifier(mapOf(id("alice@d.example").toUserUrl() to list, id("carol@d.example").toUserUrl() to carol))
            v.verify(id("alice@d.example"), alice)
            assertEquals(Nip05Check.VERIFIED, v.verify(id("carol@d.example"), bob))
            assertEquals(2, http.asked.size)
        }

    @Test
    fun aDownDomainIsPending() =
        runTest {
            val (v, _) = verifier(mapOf(id("alice@e.example").toUserUrl() to FetchResult.DomainDown("timeout")))
            assertEquals(Nip05Check.PENDING, v.verify(id("alice@e.example"), alice))
        }

    private fun hex(i: Int) = i.toString(16).padStart(64, '0')

    private fun usersOn(
        domain: String,
        count: Int,
    ) = (0 until count).map { id("user$it@$domain") to hex(it) }

    private fun namesJson(entries: List<Pair<Nip05Id, String>>) =
        entries.joinToString(",", prefix = """{"names":{""", postfix = "}}") { (id, pk) -> "\"${id.name}\":\"$pk\"" }

    @Test
    fun aBusyDomainIsAnsweredFromItsWholeList() =
        runTest {
            val users = usersOn("busy.example", 20)
            val bare = Nip05Id.domainUrl("busy.example")
            val (v, http) = verifier(mapOf(bare to FetchResult.Ok(namesJson(users))))

            v.prefetch(users.map { it.first })
            users.forEach { (id, pk) -> assertEquals(Nip05Check.VERIFIED, v.verify(id, pk)) }

            assertEquals(listOf(bare), http.asked)
            assertEquals(2L * 1024 * 1024, http.maxBytesAsked[bare], "the list gets the larger cap")
        }

    @Test
    fun aQuietDomainIsAskedNameByName() =
        runTest {
            val users = usersOn("quiet.example", 19)
            val (v, http) = verifier(users.associate { (id, pk) -> id.toUserUrl() to FetchResult.Ok(namesJson(listOf(id to pk))) })

            v.prefetch(users.map { it.first })
            users.forEach { (id, pk) -> assertEquals(Nip05Check.VERIFIED, v.verify(id, pk)) }

            assertEquals(19, http.asked.size)
            assertEquals(false, Nip05Id.domainUrl("quiet.example") in http.asked)
        }

    @Test
    fun anEmptyOrRefusedListFallsBackToPerName() =
        runTest {
            for (bareAnswer in listOf(FetchResult.Ok("""{"names":{}}"""), FetchResult.AddressGone(404), FetchResult.Refused("body over 2097152 bytes"))) {
                val users = usersOn("big.example", 20)
                val answers = users.associate { (id, pk) -> id.toUserUrl() to (FetchResult.Ok(namesJson(listOf(id to pk))) as FetchResult) } +
                    (Nip05Id.domainUrl("big.example") to bareAnswer)
                val (v, http) = verifier(answers)

                v.prefetch(users.map { it.first })
                users.forEach { (id, pk) -> assertEquals(Nip05Check.VERIFIED, v.verify(id, pk), "after $bareAnswer") }
                assertEquals(21, http.asked.size, "one list request, then one per name, after $bareAnswer")
            }
        }

    @Test
    fun aPartialListAnswersOnlyTheNamesItHolds() =
        runTest {
            val users = usersOn("partial.example", 20)
            val listed = users.take(19)
            val missing = users.last()
            val answers =
                mapOf(
                    Nip05Id.domainUrl("partial.example") to FetchResult.Ok(namesJson(listed)),
                    missing.first.toUserUrl() to FetchResult.Ok(namesJson(listOf(missing))),
                )
            val (v, http) = verifier(answers)

            v.prefetch(users.map { it.first })
            users.forEach { (id, pk) -> assertEquals(Nip05Check.VERIFIED, v.verify(id, pk)) }
            assertEquals(listOf(Nip05Id.domainUrl("partial.example"), missing.first.toUserUrl()), http.asked)
        }

    @Test
    fun aListIsAskedForOncePerRun() =
        runTest {
            val users = usersOn("once.example", 20)
            val (v, http) = verifier(mapOf(Nip05Id.domainUrl("once.example") to FetchResult.AddressGone(404)))
            v.prefetch(users.map { it.first })
            v.prefetch(users.map { it.first })
            assertEquals(1, http.asked.count { it == Nip05Id.domainUrl("once.example") })
        }

    @Test
    fun aListedNameWithAnotherKeyIsListedToOther() =
        runTest {
            val users = usersOn("other.example", 20)
            val (v, _) = verifier(mapOf(Nip05Id.domainUrl("other.example") to FetchResult.Ok(namesJson(users))))
            v.prefetch(users.map { it.first })
            assertEquals(Nip05Check.LISTED_TO_OTHER, v.verify(users[0].first, hex(999)))
        }

    @Test
    fun onlyA404OrAMissingNameIsNotListed() =
        runTest {
            val gone = id("gone@f.example")
            val blocked = id("blocked@g.example")
            val moved = id("moved@h.example")
            val missing = id("missing@i.example")
            val garbage = id("garbage@j.example")
            val refused = id("refused@k.example")
            val (v, _) =
                verifier(
                    mapOf(
                        gone.toUserUrl() to FetchResult.AddressGone(404),
                        blocked.toUserUrl() to FetchResult.AddressGone(403),
                        moved.toUserUrl() to FetchResult.AddressGone(301),
                        missing.toUserUrl() to FetchResult.Ok("""{"names":{}}"""),
                        garbage.toUserUrl() to FetchResult.Ok("<html>"),
                        refused.toUserUrl() to FetchResult.Refused("non-public host"),
                    ),
                )
            assertEquals(Nip05Check.NOT_LISTED, v.verify(gone, alice))
            assertEquals(Nip05Check.NOT_LISTED, v.verify(missing, alice))
            assertEquals(Nip05Check.UNKNOWN, v.verify(blocked, alice), "a 403 is usually a firewall")
            assertEquals(Nip05Check.UNKNOWN, v.verify(moved, alice), "a redirect is a misconfiguration")
            assertEquals(Nip05Check.UNKNOWN, v.verify(garbage, alice))
            assertEquals(Nip05Check.UNKNOWN, v.verify(refused, alice))
        }
}
