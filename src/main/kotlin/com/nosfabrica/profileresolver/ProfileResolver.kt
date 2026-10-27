package com.nosfabrica.profileresolver

import com.nosfabrica.profileresolver.lightning.LightningResolver
import com.nosfabrica.profileresolver.nip05.Nip05Verifier
import com.nosfabrica.profileresolver.profiles.ProfileFields
import com.nosfabrica.profileresolver.store.GraphStore
import com.nosfabrica.profileresolver.store.KeyValueStore
import com.nosfabrica.profileresolver.store.LightningWrite
import com.nosfabrica.profileresolver.store.LnStatus
import com.nosfabrica.profileresolver.store.Nip05Write
import com.nosfabrica.profileresolver.store.UserState
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Id
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Counts for the run's log line. */
data class Tally(
    var profiles: Int = 0,
    var notInGraph: Int = 0,
    var lightningResolved: Int = 0,
    var lightningCleared: Int = 0,
    var nip05Checked: Int = 0,
    var nip05Cleared: Int = 0,
) {
    override fun toString() =
        "profiles=$profiles notInGraph=$notInGraph lightningResolved=$lightningResolved " +
            "lightningCleared=$lightningCleared nip05Checked=$nip05Checked nip05Cleared=$nip05Cleared"
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
) {
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
            val nip05Jobs =
                users.values.mapNotNull { user ->
                    val wanted = latest.getValue(user.pubkey).nip05
                    when {
                        wanted == null && user.nip05 == null -> null
                        wanted == null -> async { Nip05Write(user.pubkey, null, null, null) }
                        wanted.toValue() == user.nip05 && !nip05Due(user) -> null
                        else -> async { verifyNip05(user.pubkey, wanted) }
                    }
                }
            writeLightning(lightningJobs.awaitAll())
            writeNip05(nip05Jobs.awaitAll())
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
                val jobs =
                    page.mapNotNull { user ->
                        val id = user.nip05?.let { Nip05Id.parse(it) } ?: return@mapNotNull null
                        async { verifyNip05(user.pubkey, id) }
                    }
                writeNip05(jobs.awaitAll())
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

    private suspend fun verifyNip05(
        pubkey: String,
        id: Nip05Id,
    ): Nip05Write {
        val status = nip05.verify(id, pubkey)
        return Nip05Write(pubkey, id.toValue(), status, nowSeconds())
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
