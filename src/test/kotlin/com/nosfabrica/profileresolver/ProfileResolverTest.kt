package com.nosfabrica.profileresolver

import com.nosfabrica.profileresolver.http.DomainGate
import com.nosfabrica.profileresolver.http.FetchResult
import com.nosfabrica.profileresolver.lightning.LightningResolver
import com.nosfabrica.profileresolver.nip05.Nip05Verifier
import com.nosfabrica.profileresolver.profiles.ProfileFields
import com.nosfabrica.profileresolver.profiles.ProfileSource
import com.nosfabrica.profileresolver.store.LnStatus
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
}
