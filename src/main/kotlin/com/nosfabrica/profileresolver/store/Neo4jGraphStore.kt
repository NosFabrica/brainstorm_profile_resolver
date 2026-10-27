package com.nosfabrica.profileresolver.store

import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.Record
import org.neo4j.driver.Values

/**
 * `NostrUser` nodes carry what this job last settled for them:
 * `lnurl` / `lnurl_status`, plus a `ZAPS_VIA` edge to the signer when there is one, and
 * `nip05` / `nip05_status` / `nip05_checked_at`. Writes are managed transactions, so the driver
 * retries transient errors (the GrapeRank write-back locks the same nodes).
 */
class Neo4jGraphStore(
    private val driver: Driver,
) : GraphStore,
    AutoCloseable {
    constructor(url: String, username: String, password: String) :
        this(GraphDatabase.driver(url, AuthTokens.basic(username, password)))

    override fun close() = driver.close()

    override fun users(pubkeys: List<String>): Map<String, UserState> =
        read(
            """
            UNWIND ${'$'}pubkeys AS pk
            MATCH (u:NostrUser {pubkey: pk})
            RETURN u.pubkey AS pubkey, u.lnurl AS lnurl, u.lnurl_status AS lnurl_status,
                   u.nip05 AS nip05, u.nip05_status AS nip05_status, u.nip05_checked_at AS nip05_checked_at
            """,
            mapOf("pubkeys" to pubkeys),
        ).associate { it.pubkey to it }

    override fun pubkeysAfter(
        after: String,
        limit: Int,
    ): List<String> =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx
                    .run(
                        "MATCH (u:NostrUser) WHERE u.pubkey > \$after RETURN u.pubkey AS pubkey ORDER BY u.pubkey LIMIT \$limit",
                        Values.parameters("after", after, "limit", limit),
                    ).list { it["pubkey"].asString() }
            }
        }

    override fun pendingLightning(
        after: String,
        limit: Int,
    ): List<UserState> =
        read(
            """
            MATCH (u:NostrUser) WHERE u.lnurl_status = 'PENDING' AND u.pubkey > ${'$'}after
            RETURN u.pubkey AS pubkey, u.lnurl AS lnurl, u.lnurl_status AS lnurl_status,
                   u.nip05 AS nip05, u.nip05_status AS nip05_status, u.nip05_checked_at AS nip05_checked_at
            ORDER BY u.pubkey LIMIT ${'$'}limit
            """,
            mapOf("after" to after, "limit" to limit),
        )

    override fun nip05Due(
        checkedBefore: Long,
        after: String,
        limit: Int,
    ): List<UserState> =
        read(
            """
            MATCH (u:NostrUser) WHERE u.nip05 IS NOT NULL AND u.pubkey > ${'$'}after
              AND (u.nip05_status = 'PENDING' OR u.nip05_checked_at < ${'$'}checkedBefore)
            RETURN u.pubkey AS pubkey, u.lnurl AS lnurl, u.lnurl_status AS lnurl_status,
                   u.nip05 AS nip05, u.nip05_status AS nip05_status, u.nip05_checked_at AS nip05_checked_at
            ORDER BY u.pubkey LIMIT ${'$'}limit
            """,
            mapOf("checkedBefore" to checkedBefore, "after" to after, "limit" to limit),
        )

    override fun writeLightning(rows: List<LightningWrite>) {
        if (rows.isEmpty()) return
        write(
            """
            UNWIND ${'$'}rows AS r
            MATCH (u:NostrUser {pubkey: r.pubkey})
            SET u.lnurl = r.lnurl, u.lnurl_status = r.status
            WITH u, r
            OPTIONAL MATCH (u)-[old:ZAPS_VIA]->(s:NostrUser)
            WHERE r.signer IS NULL OR s.pubkey <> r.signer
            DELETE old
            WITH DISTINCT u, r
            WHERE r.signer IS NOT NULL
            MERGE (signer:NostrUser {pubkey: r.signer})
            MERGE (u)-[:ZAPS_VIA]->(signer)
            """,
            rows.map { mapOf("pubkey" to it.pubkey, "lnurl" to it.lnurl, "status" to it.status?.name, "signer" to it.signer) },
        )
    }

    override fun writeNip05(rows: List<Nip05Write>) {
        if (rows.isEmpty()) return
        write(
            """
            UNWIND ${'$'}rows AS r
            MATCH (u:NostrUser {pubkey: r.pubkey})
            SET u.nip05 = r.nip05, u.nip05_status = r.status, u.nip05_checked_at = r.checked_at
            """,
            rows.map { mapOf("pubkey" to it.pubkey, "nip05" to it.nip05, "status" to it.status?.name, "checked_at" to it.checkedAt) },
        )
    }

    private fun read(
        cypher: String,
        params: Map<String, Any?>,
    ): List<UserState> =
        driver.session().use { session ->
            session.executeRead { tx -> tx.run(cypher, params).list(::userState) }
        }

    private fun write(
        cypher: String,
        rows: List<Map<String, Any?>>,
    ) {
        driver.session().use { session ->
            session.executeWriteWithoutResult { tx -> tx.run(cypher, mapOf("rows" to rows)).consume() }
        }
    }

    private fun userState(r: Record) =
        UserState(
            pubkey = r["pubkey"].asString(),
            lnurl = r["lnurl"].takeUnless { it.isNull }?.asString(),
            lnurlStatus = r["lnurl_status"].takeUnless { it.isNull }?.asString()?.let { enumOrNull<LnStatus>(it) },
            nip05 = r["nip05"].takeUnless { it.isNull }?.asString(),
            nip05Status = r["nip05_status"].takeUnless { it.isNull }?.asString()?.let { enumOrNull<Nip05Status>(it) },
            nip05CheckedAt = r["nip05_checked_at"].takeUnless { it.isNull }?.asLong(),
        )

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}
