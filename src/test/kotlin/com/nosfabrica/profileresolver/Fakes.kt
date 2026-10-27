package com.nosfabrica.profileresolver

import com.nosfabrica.profileresolver.http.FencedHttp
import com.nosfabrica.profileresolver.http.FetchResult
import com.nosfabrica.profileresolver.store.AddressAnswer
import com.nosfabrica.profileresolver.store.DomainHealth
import com.nosfabrica.profileresolver.store.GraphStore
import com.nosfabrica.profileresolver.store.KeyValueStore
import com.nosfabrica.profileresolver.store.LightningWrite
import com.nosfabrica.profileresolver.store.LnStatus
import com.nosfabrica.profileresolver.store.Nip05Status
import com.nosfabrica.profileresolver.store.Nip05Write
import com.nosfabrica.profileresolver.store.UserState
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient

class FakeStore : KeyValueStore {
    val answers = mutableMapOf<String, AddressAnswer>()
    val domains = mutableMapOf<String, DomainHealth>()
    val zapsVia = mutableMapOf<String, String>()
    var cursorValue: Long? = null

    override fun addressAnswer(url: String) = answers[url]

    override fun putAddressAnswer(
        url: String,
        answer: AddressAnswer,
    ) {
        answers[url] = answer
    }

    override fun domainHealth(domain: String) = domains[domain]

    override fun putDomainHealth(
        domain: String,
        health: DomainHealth?,
    ) {
        if (health == null) domains.remove(domain) else domains[domain] = health
    }

    override fun cursor() = cursorValue

    override fun putCursor(epochSeconds: Long) {
        cursorValue = epochSeconds
    }

    override fun setZapsVia(
        pubkey: String,
        signer: String?,
    ) {
        if (signer == null) zapsVia.remove(pubkey) else zapsVia[pubkey] = signer
    }
}

/** The graph as nodes with this job's fields and `ZAPS_VIA` targets. */
class FakeGraph(
    pubkeys: Collection<String> = emptyList(),
) : GraphStore {
    val users = pubkeys.associateWith { UserState(it) }.toMutableMap()
    val zapsVia = mutableMapOf<String, String>()
    val lightningWrites = mutableListOf<LightningWrite>()

    override fun users(pubkeys: List<String>) = pubkeys.mapNotNull { users[it] }.associateBy { it.pubkey }

    override fun pubkeysAfter(
        after: String,
        limit: Int,
    ) = users.keys.sorted().filter { it > after }.take(limit)

    override fun pendingLightning(
        after: String,
        limit: Int,
    ) = users.values.filter { it.lnurlStatus == LnStatus.PENDING && it.pubkey > after }.sortedBy { it.pubkey }.take(limit)

    override fun nip05Due(
        checkedBefore: Long,
        after: String,
        limit: Int,
    ) = users.values
        .filter { it.nip05 != null && it.pubkey > after && (it.nip05Status == Nip05Status.PENDING || it.nip05Status == Nip05Status.SUSPECTED || (it.nip05CheckedAt ?: 0) < checkedBefore) }
        .sortedBy { it.pubkey }
        .take(limit)

    override fun writeLightning(rows: List<LightningWrite>) {
        lightningWrites += rows
        rows.forEach { r ->
            users[r.pubkey]?.let { users[r.pubkey] = it.copy(lnurl = r.lnurl, lnurlStatus = r.status) } ?: return@forEach
            if (r.signer == null) zapsVia.remove(r.pubkey) else zapsVia[r.pubkey] = r.signer!!
        }
    }

    override fun writeNip05(rows: List<Nip05Write>) {
        rows.forEach { r ->
            users[r.pubkey]?.let { users[r.pubkey] = it.copy(nip05 = r.nip05, nip05Status = r.status, nip05CheckedAt = r.checkedAt) }
        }
    }
}

/** Answers GETs from a map of URL to result, counting each URL asked. */
class ScriptedHttp(
    private val answers: Map<String, FetchResult>,
    /** When set, every GET waits for it: lets a test have callers overlap. */
    private val hold: CompletableDeferred<Unit>? = null,
) {
    val asked = mutableListOf<String>()
    val maxBytesAsked = mutableMapOf<String, Long>()

    val http =
        object : FencedHttp(OkHttpClient()) {
            override suspend fun get(
                url: String,
                accept: String,
                maxBytes: Long,
            ): FetchResult {
                asked += url
                maxBytesAsked[url] = maxBytes
                hold?.await()
                return answers[url] ?: FetchResult.AddressGone(404)
            }
        }
}
