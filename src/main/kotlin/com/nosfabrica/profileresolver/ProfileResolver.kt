package com.nosfabrica.profileresolver

import com.nosfabrica.profileresolver.lightning.LightningResolver
import com.nosfabrica.profileresolver.nip05.Nip05Check
import com.nosfabrica.profileresolver.nip05.Nip05Verifier
import com.nosfabrica.profileresolver.profiles.ProfileFields
import com.nosfabrica.profileresolver.store.GraphStore
import com.nosfabrica.profileresolver.store.KeyValueStore
import com.nosfabrica.profileresolver.store.LightningWrite
import com.nosfabrica.profileresolver.store.LnStatus
import com.nosfabrica.profileresolver.store.Nip05Status
import com.nosfabrica.profileresolver.store.Nip05Write
import com.nosfabrica.profileresolver.store.UserState
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Id
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

/** Counts for the run's log line. */
data class Tally(
    var profiles: Int = 0,
    var notInGraph: Int = 0,
    var lightningResolved: Int = 0,
    var lightningCleared: Int = 0,
    var nip05Checked: Int = 0,
    var nip05Cleared: Int = 0,
    var nip05Impersonations: Int = 0,
    var nip05DomainsDoubted: Int = 0,
) {
    override fun toString() =
        "profiles=$profiles notInGraph=$notInGraph lightningResolved=$lightningResolved " +
            "lightningCleared=$lightningCleared nip05Checked=$nip05Checked nip05Cleared=$nip05Cleared " +
            "nip05Impersonations=$nip05Impersonations nip05DomainsDoubted=$nip05DomainsDoubted"
}

/**
 * Turns profiles into what the graph holds about them: a `ZAPS_VIA` edge (+ `zaps_via:` set)
 * to the key that signs each user's zap receipts, and each user's NIP-05 verification.
 *
 * Only users already in the graph are touched, and only what changed is looked up: an
 * address the graph already settled for that user is not fetched again.
 */
class ProfileResolver(
    private val graph: GraphStore,
    private val store: KeyValueStore,
    private val lightning: LightningResolver,
    private val nip05: Nip05Verifier,
    private val nip05RecheckSeconds: Long,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /** A domain with at least this many "listed to another key" answers in a batch... */
    private val doubtMinimum: Int = 10,
    /** ...making up at least this share of its checks there, is doubted rather than believed. */
    private val doubtShare: Double = 0.5,
) {
    private val log = LoggerFactory.getLogger(ProfileResolver::class.java)

    val tally = Tally()

    /** One batch of profiles, newest per author kept. */
    suspend fun process(profiles: List<ProfileFields>) {
        val latest = profiles.groupBy { it.pubkey }.mapValues { (_, p) -> p.maxBy { it.createdAt } }
        val users = graph.users(latest.keys.toList())
        tally.profiles += latest.size
        tally.notInGraph += latest.size - users.size

        coroutineScope {
            val lightningJobs =
                users.values.mapNotNull { user ->
                    val wanted = latest.getValue(user.pubkey).lnurl
                    when {
                        wanted == null && user.lnurl == null -> null
                        wanted == null -> async { LightningWrite(user.pubkey, null, null, null) }
                        wanted == user.lnurl && user.lnurlStatus?.isFinal == true -> null
                        else -> async { resolveLightning(user.pubkey, wanted) }
                    }
                }
            val nip05Cleared = mutableListOf<Nip05Write>()
            val toVerify = mutableListOf<Pair<UserState, Nip05Id>>()
            for (user in users.values) {
                val wanted = latest.getValue(user.pubkey).nip05
                when {
                    wanted == null && user.nip05 == null -> {}
                    wanted == null -> nip05Cleared += Nip05Write(user.pubkey, null, null, null)
                    wanted.toValue() == user.nip05 && !nip05Due(user) -> {}
                    else -> toVerify += user to wanted
                }
            }
            // A domain with many names in this batch may answer them all with one list.
            nip05.prefetch(toVerify.map { it.second })
            val nip05Jobs = toVerify.map { (user, id) -> async { id to nip05.verify(id, user.pubkey) } }
            writeLightning(lightningJobs.awaitAll())
            writeNip05(nip05Cleared + settle(toVerify.map { it.first }.zip(nip05Jobs.awaitAll())))
        }
    }

    /** Lightning lookups a down domain left pending, and NIP-05s due a re-check. */
    suspend fun retryDue(pageSize: Int = 500) {
        var after = ""
        while (true) {
            val page = graph.pendingLightning(after, pageSize)
            if (page.isEmpty()) break
            coroutineScope {
                writeLightning(page.map { user -> async { resolveLightning(user.pubkey, user.lnurl!!) } }.awaitAll())
            }
            after = page.last().pubkey
        }
        after = ""
        val checkedBefore = nowSeconds() - nip05RecheckSeconds
        while (true) {
            val page = graph.nip05Due(checkedBefore, after, pageSize)
            if (page.isEmpty()) break
            coroutineScope {
                val due = page.mapNotNull { user -> user.nip05?.let { Nip05Id.parse(it) }?.let { user to it } }
                nip05.prefetch(due.map { it.second })
                val checks = due.map { (user, id) -> async { id to nip05.verify(id, user.pubkey) } }.awaitAll()
                writeNip05(settle(due.map { it.first }.zip(checks)))
            }
            after = page.last().pubkey
        }
    }

    private suspend fun resolveLightning(
        pubkey: String,
        lnurl: String,
    ): LightningWrite {
        val answer = lightning.resolve(lnurl)
        // A user's own key signing their receipts says nothing about anyone else.
        val signer = answer.signer?.takeIf { answer.status == LnStatus.OK && it != pubkey }
        return LightningWrite(pubkey, lnurl, answer.status, signer)
    }

    /**
     * Checks into statuses. A name listed to another key is only [Nip05Status.SUSPECTED] the first
     * time, and becomes [Nip05Status.IMPERSONATION] when the next check agrees — one bad answer
     * from a domain must not zero anyone. A domain answering "someone else's" for many of its
     * names at once is doubted instead: that is a domain fault, not a crowd of impersonators.
     */
    private fun settle(checks: List<Pair<UserState, Pair<Nip05Id, Nip05Check>>>): List<Nip05Write> {
        val doubted =
            checks
                .groupBy({ it.second.first.domain }, { it.second.second })
                .filterValues { results ->
                    val others = results.count { it == Nip05Check.LISTED_TO_OTHER }
                    others >= doubtMinimum && others >= doubtShare * results.size
                }.keys
        doubted.forEach { log.warn("NIP-05 domain {} named someone else for most of its names in one batch; not penalising them", it) }
        tally.nip05DomainsDoubted += doubted.size

        val now = nowSeconds()
        return checks.map { (user, check) ->
            val (id, result) = check
            val status =
                when (result) {
                    Nip05Check.VERIFIED -> Nip05Status.VERIFIED
                    Nip05Check.NOT_LISTED -> Nip05Status.NOT_LISTED
                    Nip05Check.UNKNOWN -> Nip05Status.UNKNOWN
                    Nip05Check.PENDING -> Nip05Status.PENDING
                    Nip05Check.LISTED_TO_OTHER ->
                        when {
                            id.domain in doubted -> Nip05Status.UNKNOWN
                            user.nip05 == id.toValue() &&
                                (user.nip05Status == Nip05Status.SUSPECTED || user.nip05Status == Nip05Status.IMPERSONATION) ->
                                Nip05Status.IMPERSONATION
                            else -> Nip05Status.SUSPECTED
                        }
                }
            if (status == Nip05Status.IMPERSONATION) tally.nip05Impersonations++
            Nip05Write(user.pubkey, id.toValue(), status, now)
        }
    }

    /** Pending, or settled longer ago than the re-check window: domains add and drop names. */
    private fun nip05Due(user: UserState): Boolean =
        user.nip05Status?.isFinal != true || (user.nip05CheckedAt ?: 0) < nowSeconds() - nip05RecheckSeconds

    private fun writeLightning(rows: List<LightningWrite>) {
        if (rows.isEmpty()) return
        // The worker's set first, then Neo4j: the graph is what marks a user settled, so a
        // crash between leaves the user unsettled and the next run writes both again.
        rows.forEach { store.setZapsVia(it.pubkey, it.signer) }
        graph.writeLightning(rows)
        tally.lightningResolved += rows.count { it.lnurl != null }
        tally.lightningCleared += rows.count { it.lnurl == null }
    }

    private fun writeNip05(rows: List<Nip05Write>) {
        if (rows.isEmpty()) return
        graph.writeNip05(rows)
        tally.nip05Checked += rows.count { it.nip05 != null }
        tally.nip05Cleared += rows.count { it.nip05 == null }
    }
}
