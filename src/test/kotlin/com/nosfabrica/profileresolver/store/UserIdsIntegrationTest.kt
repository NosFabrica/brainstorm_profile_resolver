package com.nosfabrica.profileresolver.store

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import java.sql.DriverManager
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The id rule against a real Neo4j and a real Scorecard database (README, "Tests"). Runs only
 * with `RESOLVER_IT=1`; the Postgres must already hold brainstorm_server's `nostr_user` table.
 */
class UserIdsIntegrationTest {
    private val enabled = System.getenv("RESOLVER_IT") == "1"
    private val neo4jUrl = System.getenv("RESOLVER_IT_NEO4J") ?: "bolt://127.0.0.1:7699"
    private val neo4jPassword = System.getenv("RESOLVER_IT_NEO4J_PASSWORD") ?: "testpassword"
    private val pgUrl = System.getenv("RESOLVER_IT_PG") ?: "jdbc:postgresql://127.0.0.1:54339/scorecard"
    private val pgUser = System.getenv("RESOLVER_IT_PG_USER") ?: "postgres"
    private val pgPassword = System.getenv("RESOLVER_IT_PG_PASSWORD") ?: "test"

    private fun pubkey() = Random.nextBytes(32).joinToString("") { "%02x".format(it) }

    private val ids by lazy { PostgresUserIds(pgUrl, pgUser, pgPassword) }
    private val driver by lazy { GraphDatabase.driver(neo4jUrl, AuthTokens.basic("neo4j", neo4jPassword)) }
    private val graph by lazy { Neo4jGraphStore(driver, ids) }

    @BeforeTest
    fun onlyWhenAsked() = assumeTrue(enabled, "set RESOLVER_IT=1 to run")

    private fun user(pubkey: String) = driver.session().use { it.run("CREATE (:NostrUser {pubkey: \$p})", mapOf("p" to pubkey)).consume() }

    private fun userId(pubkey: String): Int? =
        driver.session().use { s ->
            s
                .run("MATCH (u:NostrUser {pubkey: \$p}) RETURN u.user_id AS id", mapOf("p" to pubkey))
                .list { r -> r["id"].takeUnless { it.isNull }?.asInt() }
                .singleOrNull()
        }

    private fun signerOf(pubkey: String): String? =
        driver.session().use { s ->
            s
                .run("MATCH (:NostrUser {pubkey: \$p})-[:ZAPS_VIA]->(x) RETURN x.pubkey AS pk", mapOf("p" to pubkey))
                .list { it["pk"].asString() }
                .singleOrNull()
        }

    private fun sequence(): Long =
        DriverManager.getConnection(pgUrl, pgUser, pgPassword).use { c ->
            c.createStatement().executeQuery("SELECT last_value FROM nostr_user_id_seq").use { it.next(); it.getLong(1) }
        }

    private fun write(
        user: String,
        signer: String?,
    ) = graph.writeLightning(listOf(LightningWrite(user, "https://ln.example/.well-known/lnurlp/a", LnStatus.OK, signer)))

    @Test
    fun aSignerNewToTheGraphGetsItsId() {
        val (alice, signer) = pubkey() to pubkey()
        user(alice)
        write(alice, signer)
        assertEquals(signer, signerOf(alice))
        assertEquals(ids.idsFor(listOf(signer))[signer], userId(signer))
        // Only the signer is created here; the user itself is the server's to number.
        assertNull(userId(alice))
    }

    @Test
    fun aKnownSignerKeepsItsIdAndAReplayChangesNothing() {
        val (alice, bob, signer) = Triple(pubkey(), pubkey(), pubkey())
        listOf(alice, bob).forEach(::user)
        write(alice, signer)
        val first = userId(signer)
        val drawn = sequence()

        write(alice, signer)
        write(bob, signer)
        assertEquals(first, userId(signer))
        assertEquals(signer, signerOf(bob))
        assertEquals(drawn, sequence(), "a known pubkey must not use up an id")
    }

    @Test
    fun aSignerAlreadyInTheGraphWithoutAnIdIsGivenOne() {
        val (alice, signer) = pubkey() to pubkey()
        listOf(alice, signer).forEach(::user)
        write(alice, signer)
        assertEquals(ids.idsFor(listOf(signer))[signer], userId(signer))
    }

    @Test
    fun aSignerHoldingAnotherIdFailsTheBatchAndKeepsItsId() {
        val (alice, signer) = pubkey() to pubkey()
        user(alice)
        driver.session().use { it.run("CREATE (:NostrUser {pubkey: \$p, user_id: -7})", mapOf("p" to signer)).consume() }
        assertFailsWith<IllegalStateException> { write(alice, signer) }
        assertEquals(-7, userId(signer))
        assertNull(signerOf(alice))
    }

    @Test
    fun clearingASignerNeedsNoId() {
        val (alice, signer) = pubkey() to pubkey()
        user(alice)
        write(alice, signer)
        write(alice, null)
        assertNull(signerOf(alice))
    }

    @Test
    fun callersRacingOnTheSameNewPubkeysAgree() {
        val pubkeys = List(200) { pubkey() }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results =
                pool
                    .invokeAll(List(8) { i -> Callable { ids.idsFor(if (i % 2 == 0) pubkeys else pubkeys.reversed()) } })
                    .map { it.get() }
            assertTrue(results.all { it == results.first() })
            assertEquals(200, results.first().values.toSet().size)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun aMalformedPubkeyNeverReachesTheTable() {
        assertFailsWith<IllegalStateException> { ids.idsFor(listOf("AB".repeat(32))) }
    }
}
