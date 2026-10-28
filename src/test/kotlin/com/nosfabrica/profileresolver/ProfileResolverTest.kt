package com.nosfabrica.profileresolver

import com.nosfabrica.profileresolver.http.DomainGate
import com.nosfabrica.profileresolver.http.FetchResult
import com.nosfabrica.profileresolver.lightning.LightningResolver
import com.nosfabrica.profileresolver.nip05.Nip05Verifier
import com.nosfabrica.profileresolver.profiles.ProfileFields
import com.nosfabrica.profileresolver.profiles.ProfileSource
import com.nosfabrica.profileresolver.store.LnStatus
import com.nosfabrica.profileresolver.store.FullPass
import com.nosfabrica.profileresolver.store.Nip05Status
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileResolverTest {
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val stranger = "c".repeat(64)
    private val wos = "d".repeat(64)
    private val wosAlice = "https://walletofsatoshi.com/.well-known/lnurlp/alice"
    private val now = 2_000_000_000L

    private val zaps = FetchResult.Ok("""{"allowsNostr":true,"nostrPubkey":"$wos"}""")

    private class Setup(
        answers: Map<String, FetchResult>,
        users: List<String>,
        now: Long,
    ) {
        val store = FakeStore()
        val graph = FakeGraph(users)
        val scripted = ScriptedHttp(answers)
        val resolver =
            ProfileResolver(
                graph = graph,
                store = store,
                lightning = LightningResolver(scripted.http, DomainGate(store, 0, 8, { now }), store, { now }),
                nip05 = Nip05Verifier(scripted.http, DomainGate(store, 0, 8, { now })),
                nip05RecheckSeconds = 30 * 86_400,
                nowSeconds = { now },
            )
    }

    private fun profile(
        pubkey: String,
        content: String,
        createdAt: Long = 1,
    ) = ProfileFields.parse(pubkey, createdAt, content)

    @Test
    fun aGraphUsersAddressBecomesAnEdgeAndTheWorkersSet() =
        runTest {
            val s = Setup(mapOf(wosAlice to zaps), listOf(alice), now)
            s.resolver.process(listOf(profile(alice, """{"lud16":"alice@walletofsatoshi.com"}""")))

            assertEquals(wos, s.graph.zapsVia[alice])
            assertEquals(wos, s.store.zapsVia[alice])
            assertEquals(LnStatus.OK, s.graph.users[alice]!!.lnurlStatus)
        }

    @Test
    fun usersOutsideTheGraphAreLeftAlone() =
        runTest {
            val s = Setup(mapOf(wosAlice to zaps), listOf(alice), now)
            s.resolver.process(listOf(profile(stranger, """{"lud16":"alice@walletofsatoshi.com"}""")))
            assertEquals(emptyList(), s.scripted.asked)
            assertEquals(1, s.resolver.tally.notInGraph)
        }

    @Test
    fun anUnchangedSettledAddressIsNotFetchedAgain() =
        runTest {
            val s = Setup(mapOf(wosAlice to zaps), listOf(alice), now)
            val p = profile(alice, """{"lud16":"alice@walletofsatoshi.com"}""")
            s.resolver.process(listOf(p))
            s.store.answers.clear() // even without the address cache, the graph says it's settled
            s.resolver.process(listOf(p))
            assertEquals(listOf(wosAlice), s.scripted.asked)
        }

    @Test
    fun aRemovedAddressClearsTheEdge() =
        runTest {
            val s = Setup(mapOf(wosAlice to zaps), listOf(alice), now)
            s.resolver.process(listOf(profile(alice, """{"lud16":"alice@walletofsatoshi.com"}""", createdAt = 1)))
            s.resolver.process(listOf(profile(alice, """{"name":"alice"}""", createdAt = 2)))
            assertNull(s.graph.zapsVia[alice])
            assertNull(s.store.zapsVia[alice])
            assertNull(s.graph.users[alice]!!.lnurl)
        }

    @Test
    fun aUsersOwnKeyAsSignerMakesNoEdge() =
        runTest {
            val own = "https://self.example/.well-known/lnurlp/me"
            val s = Setup(mapOf(own to FetchResult.Ok("""{"allowsNostr":true,"nostrPubkey":"$alice"}""")), listOf(alice), now)
            s.resolver.process(listOf(profile(alice, """{"lud16":"me@self.example"}""")))
            assertNull(s.graph.zapsVia[alice])
            assertEquals(LnStatus.OK, s.graph.users[alice]!!.lnurlStatus)
        }

    @Test
    fun theNewestProfileOfAnAuthorWins() =
        runTest {
            val s = Setup(mapOf(wosAlice to zaps), listOf(alice), now)
            s.resolver.process(
                listOf(
                    profile(alice, """{"lud16":"alice@walletofsatoshi.com"}""", createdAt = 2),
                    profile(alice, """{"lud16":"old@elsewhere.example"}""", createdAt = 1),
                ),
            )
            assertEquals(listOf(wosAlice), s.scripted.asked)
        }

    @Test
    fun aPendingAddressIsRetriedOnceTheDomainAnswers() =
        runTest {
            val down = mapOf(wosAlice to FetchResult.DomainDown("timeout"))
            val s = Setup(down, listOf(alice), now)
            s.resolver.process(listOf(profile(alice, """{"lud16":"alice@walletofsatoshi.com"}""")))
            assertEquals(LnStatus.PENDING, s.graph.users[alice]!!.lnurlStatus)

            // A later run, after the backoff, with the domain back.
            val later = Setup(mapOf(wosAlice to zaps), emptyList(), now + 7200)
            later.graph.users.putAll(s.graph.users)
            later.store.domains.putAll(s.store.domains)
            later.resolver.retryDue()
            assertEquals(wos, later.graph.zapsVia[alice])
        }

    @Test
    fun nip05IsVerifiedAndRecheckedOnlyWhenDue() =
        runTest {
            val url = "https://example.com/.well-known/nostr.json?name=bob"
            val s = Setup(mapOf(url to FetchResult.Ok("""{"names":{"bob":"$bob"}}""")), listOf(bob), now)
            val p = profile(bob, """{"nip05":"bob@example.com"}""")
            s.resolver.process(listOf(p))
            assertEquals(Nip05Status.VERIFIED, s.graph.users[bob]!!.nip05Status)

            s.resolver.process(listOf(p))
            s.resolver.retryDue()
            assertEquals(1, s.scripted.asked.size, "fresh: not asked again")
        }

    @Test
    fun theFirstRunIsAFullPassAndSetsTheCursor() =
        runTest {
            val s = Setup(mapOf(wosAlice to zaps), listOf(alice, bob), now)
            val source =
                object : ProfileSource {
                    val askedAuthors = mutableListOf<List<String>>()

                    override suspend fun changedSince(since: Long) = error("not on a first run")

                    override suspend fun byAuthors(pubkeys: List<String>): List<ProfileFields> {
                        askedAuthors += pubkeys
                        return listOf(profile(alice, """{"lud16":"alice@walletofsatoshi.com"}"""))
                    }
                }
            ResolverJob(Config.Mode.INCREMENTAL, s.graph, s.store, source, s.resolver).run(startedAt = now)
            assertEquals(listOf(listOf(alice, bob)), source.askedAuthors)
            assertEquals(now, s.store.cursorValue)
            assertEquals(wos, s.graph.zapsVia[alice])
        }

    @Test
    fun anIncompleteIncrementalWalkKeepsTheOldCursor() =
        runTest {
            val s = Setup(emptyMap(), listOf(alice), now)
            s.store.cursorValue = now - 86_400
            s.store.lastFullPassValue = now - 86_400
            val source =
                object : ProfileSource {
                    override suspend fun changedSince(since: Long) = ProfileSource.Changed(emptyList(), complete = false, end = "IDLE")

                    override suspend fun byAuthors(pubkeys: List<String>) = error("not on an incremental run")
                }
            ResolverJob(Config.Mode.INCREMENTAL, s.graph, s.store, source, s.resolver).run(startedAt = now)
            assertEquals(now - 86_400, s.store.cursorValue)
        }

    @Test
    fun aBatchWithManyNamesOnOneDomainCostsOneRequest() =
        runTest {
            val pubkeys = (0 until 25).map { it.toString(16).padStart(64, '0') }
            val names = pubkeys.withIndex().joinToString(",", prefix = """{"names":{""", postfix = "}}") { (i, pk) -> "\"user$i\":\"$pk\"" }
            val s = Setup(mapOf("https://bots.example/.well-known/nostr.json" to FetchResult.Ok(names)), pubkeys, now)

            s.resolver.process(pubkeys.mapIndexed { i, pk -> profile(pk, """{"nip05":"user$i@bots.example"}""") })

            assertEquals(listOf("https://bots.example/.well-known/nostr.json"), s.scripted.asked)
            pubkeys.forEach { assertEquals(Nip05Status.VERIFIED, s.graph.users[it]!!.nip05Status) }
        }

    private fun names(vararg entries: Pair<String, String>) =
        FetchResult.Ok(entries.joinToString(",", prefix = """{"names":{""", postfix = "}}") { (n, pk) -> "\"$n\":\"$pk\"" })

    @Test
    fun aNameListedToSomeoneElseIsSuspectedThenConfirmed() =
        runTest {
            val url = "https://example.com/.well-known/nostr.json?name=jack"
            val s = Setup(mapOf(url to names("jack" to alice)), listOf(bob), now)
            val p = profile(bob, """{"nip05":"jack@example.com"}""")

            s.resolver.process(listOf(p))
            assertEquals(Nip05Status.SUSPECTED, s.graph.users[bob]!!.nip05Status, "one answer is not enough")

            // The next night: SUSPECTED is not final, so the retry pass checks it again.
            val next = Setup(mapOf(url to names("jack" to alice)), emptyList(), now + 86_400)
            next.graph.users.putAll(s.graph.users)
            next.resolver.retryDue()
            assertEquals(Nip05Status.IMPERSONATION, next.graph.users[bob]!!.nip05Status)
            assertEquals(1, next.resolver.tally.nip05Impersonations)
        }

    @Test
    fun aSuspicionTheNextCheckContradictsIsCleared() =
        runTest {
            val url = "https://example.com/.well-known/nostr.json?name=jack"
            val s = Setup(mapOf(url to names("jack" to alice)), listOf(bob), now)
            s.resolver.process(listOf(profile(bob, """{"nip05":"jack@example.com"}""")))

            val next = Setup(mapOf(url to names("jack" to bob)), emptyList(), now + 86_400)
            next.graph.users.putAll(s.graph.users)
            next.resolver.retryDue()
            assertEquals(Nip05Status.VERIFIED, next.graph.users[bob]!!.nip05Status)
        }

    @Test
    fun aSuspicionOnAnotherNameDoesNotConfirm() =
        runTest {
            val jack = "https://example.com/.well-known/nostr.json?name=jack"
            val dorsey = "https://example.com/.well-known/nostr.json?name=dorsey"
            val s = Setup(mapOf(jack to names("jack" to alice), dorsey to names("dorsey" to alice)), listOf(bob), now)
            s.resolver.process(listOf(profile(bob, """{"nip05":"jack@example.com"}""", createdAt = 1)))
            s.resolver.process(listOf(profile(bob, """{"nip05":"dorsey@example.com"}""", createdAt = 2)))
            assertEquals(Nip05Status.SUSPECTED, s.graph.users[bob]!!.nip05Status)
        }

    @Test
    fun aDomainNamingSomeoneElseForMostOfItsNamesIsDoubted() =
        runTest {
            val pubkeys = (0 until 12).map { (it + 100).toString(16).padStart(64, '0') }
            // The domain maps every name to one wrong key: a broken domain, not 12 impersonators.
            val answers = pubkeys.indices.associate { "https://broken.example/.well-known/nostr.json?name=user$it" to names("user$it" to alice) }
            val s = Setup(answers, pubkeys, now)
            s.resolver.process(pubkeys.mapIndexed { i, pk -> profile(pk, """{"nip05":"user$i@broken.example"}""") })

            pubkeys.forEach { assertEquals(Nip05Status.UNKNOWN, s.graph.users[it]!!.nip05Status) }
            assertEquals(1, s.resolver.tally.nip05DomainsDoubted)
        }

    @Test
    fun aSuspicionIsNotConfirmedByTheSameRunsRetryPass() =
        runTest {
            // Regression: SUSPECTED was always due, so the retry pass minutes later confirmed it.
            val url = "https://example.com/.well-known/nostr.json?name=jack"
            val s = Setup(mapOf(url to names("jack" to alice)), listOf(bob), now)
            s.resolver.process(listOf(profile(bob, """{"nip05":"jack@example.com"}""")))
            s.resolver.retryDue()
            assertEquals(Nip05Status.SUSPECTED, s.graph.users[bob]!!.nip05Status)
            assertEquals(1, s.scripted.asked.size, "not asked again within the gap")
            assertEquals(0, s.resolver.tally.nip05Impersonations)
        }

    @Test
    fun aDomainIsDoubtedAcrossBatchesNotJustWithinOne() =
        runTest {
            // A full pass walks by pubkey, so a broken domain's names arrive one or two per batch.
            val pubkeys = (0 until 12).map { (it + 200).toString(16).padStart(64, '0') }
            val answers = pubkeys.indices.associate { "https://broken.example/.well-known/nostr.json?name=user$it" to names("user$it" to alice) }
            val s = Setup(answers, pubkeys, now)
            pubkeys.forEachIndexed { i, pk -> s.resolver.process(listOf(profile(pk, """{"nip05":"user$i@broken.example"}"""))) }
            s.resolver.finish()

            pubkeys.forEach { assertEquals(Nip05Status.UNKNOWN, s.graph.users[it]!!.nip05Status) }
            assertEquals(1, s.resolver.tally.nip05DomainsDoubted)
        }

    @Test
    fun aFewSuspicionsOnADomainStand() =
        runTest {
            val url = "https://example.com/.well-known/nostr.json?name=jack"
            val s = Setup(mapOf(url to names("jack" to alice)), listOf(bob), now)
            s.resolver.process(listOf(profile(bob, """{"nip05":"jack@example.com"}""")))
            s.resolver.finish()
            assertEquals(Nip05Status.SUSPECTED, s.graph.users[bob]!!.nip05Status)
        }

    private fun source(
        changed: List<ProfileFields> = emptyList(),
        byAuthor: (String) -> ProfileFields? = { null },
    ) = object : ProfileSource {
        val askedAuthors = mutableListOf<List<String>>()
        var askedChanged = false

        override suspend fun changedSince(since: Long): ProfileSource.Changed {
            askedChanged = true
            return ProfileSource.Changed(changed, complete = true, end = "EOSE")
        }

        override suspend fun byAuthors(pubkeys: List<String>): List<ProfileFields> {
            askedAuthors += pubkeys
            return pubkeys.mapNotNull(byAuthor)
        }
    }

    @Test
    fun anUnfinishedFullPassResumesWhereItStopped() =
        runTest {
            val s = Setup(emptyMap(), listOf(alice, bob, stranger), now)
            s.store.cursorValue = now - 10 * 86_400
            s.store.fullPassValue = FullPass(startedAt = now - 86_400, after = alice)
            val src = source()
            ResolverJob(Config.Mode.INCREMENTAL, s.graph, s.store, src, s.resolver, batchSize = 1).run(startedAt = now)

            assertEquals(listOf(listOf(bob), listOf(stranger)), src.askedAuthors)
            assertNull(s.store.fullPassValue)
            assertEquals(now - 86_400, s.store.cursorValue, "the cursor is when the pass began, not when it ended")
            assertEquals(now - 86_400, s.store.lastFullPassValue)
        }

    @Test
    fun aFullPassSavesItsProgress() =
        runTest {
            val s = Setup(emptyMap(), listOf(alice, bob), now)
            val src =
                object : ProfileSource {
                    override suspend fun changedSince(since: Long) = error("full pass")

                    override suspend fun byAuthors(pubkeys: List<String>): List<ProfileFields> {
                        if (pubkeys == listOf(bob)) error("killed")
                        return emptyList()
                    }
                }
            runCatching { ResolverJob(Config.Mode.INCREMENTAL, s.graph, s.store, src, s.resolver, batchSize = 1).run(startedAt = now) }
            assertEquals(FullPass(now, alice), s.store.fullPassValue)
            assertNull(s.store.cursorValue)
        }

    @Test
    fun aWeeklyFullPassReadsUsersWhoseProfilesDidNotChange() =
        runTest {
            val s = Setup(mapOf(wosAlice to zaps), listOf(alice), now)
            s.store.cursorValue = now - 86_400
            s.store.lastFullPassValue = now - 8 * 86_400
            val src = source { pk -> profile(pk, """{"lud16":"alice@walletofsatoshi.com"}""") }
            ResolverJob(Config.Mode.INCREMENTAL, s.graph, s.store, src, s.resolver).run(startedAt = now)
            assertEquals(false, src.askedChanged)
            assertEquals(wos, s.graph.zapsVia[alice])
            assertEquals(now, s.store.lastFullPassValue)

            s.store.lastFullPassValue = now - 86_400
            val nextNight = source()
            ResolverJob(Config.Mode.INCREMENTAL, s.graph, s.store, nextNight, s.resolver).run(startedAt = now + 86_400)
            assertEquals(true, nextNight.askedChanged)
        }

    @Test
    fun theNewestProfileWinsAcrossTheWholeWalk() =
        runTest {
            val bobUrl = "https://walletofsatoshi.com/.well-known/lnurlp/bob"
            val s = Setup(mapOf(wosAlice to zaps, bobUrl to zaps), listOf(alice), now)
            s.store.cursorValue = now - 86_400
            s.store.lastFullPassValue = now - 86_400
            // Newest first, as the relay pages; one profile per batch.
            val walk =
                listOf(
                    profile(alice, """{"lud16":"alice@walletofsatoshi.com"}""", createdAt = 2),
                    profile(alice, """{"lud16":"bob@walletofsatoshi.com"}""", createdAt = 1),
                )
            ResolverJob(Config.Mode.INCREMENTAL, s.graph, s.store, source(walk), s.resolver, batchSize = 1).run(startedAt = now)
            assertEquals(wosAlice, s.graph.users[alice]!!.lnurl)
            assertEquals(listOf(wosAlice), s.scripted.asked)
        }
}
