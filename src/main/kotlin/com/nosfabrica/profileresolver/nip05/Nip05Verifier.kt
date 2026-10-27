package com.nosfabrica.profileresolver.nip05

import com.nosfabrica.profileresolver.http.DomainGate
import com.nosfabrica.profileresolver.http.FencedHttp
import com.nosfabrica.profileresolver.http.FetchResult
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Id
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks that a NIP-05 domain maps the name to the pubkey (`names` in its `nostr.json`).
 *
 * Unlike a lightning answer this is never final for good — a domain can drop a name — so the
 * caller re-checks verified names on a schedule.
 *
 * Per name (`?name=`) is the path that works everywhere. A domain's whole list (the bare
 * `nostr.json`) is a shortcut that works on some: a survey of 40 domains (2026-10-27) found
 * complete lists on a few (21.gifts, stacker.news), empty ones on the biggest (primal.net,
 * fountain.fm), refusals (getalby.com 404, iris.to 418), a partial list (coinos.io) and lists in
 * the megabytes (the mostr bridges). So a list only ever answers names it holds; a name missing
 * from one is asked for itself.
 */
/** What one check observed. [Nip05Check.LISTED_TO_OTHER] becomes an impersonation only once confirmed. */
enum class Nip05Check {
    VERIFIED,

    /** The domain maps the name to a different pubkey. */
    LISTED_TO_OTHER,

    /** The domain answered without backing the claim: the name missing from its `names`, or a 404. */
    NOT_LISTED,

    /** No usable answer, and not the user's doing: another 4xx, a redirect, an unreadable body, a refused URL. */
    UNKNOWN,

    /** The domain was down or backing off. */
    PENDING,
}

class Nip05Verifier(
    private val http: FencedHttp,
    private val gate: DomainGate,
    /** A domain's whole list is fetched only for a batch with at least this many of its names. */
    private val prefetchThreshold: Int = 20,
    private val maxListBytes: Long = 2L * 1024 * 1024,
) {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<FetchResult?>>()

    /** Per domain, the `names` of a `nostr.json` that listed more than one: name → pubkey. */
    private val lists = ConcurrentHashMap<String, Map<String, String>>()

    /** Domains whose bare list this run already asked for, whatever came back. */
    private val listTried = ConcurrentHashMap.newKeySet<String>()

    /**
     * Fetches the whole list of every domain with at least [prefetchThreshold] of [ids], once
     * per run. An empty, refused, oversized or failed list changes nothing: those names are
     * asked for one by one, as without it.
     */
    suspend fun prefetch(ids: Collection<Nip05Id>) {
        val busy =
            ids
                .groupingBy { it.domain }
                .eachCount()
                .filter { (domain, count) -> count >= prefetchThreshold && !lists.containsKey(domain) && listTried.add(domain) }
                .keys
        coroutineScope {
            busy
                .map { domain ->
                    async {
                        val result = gate.call(domain) { http.get(Nip05Id.domainUrl(domain), maxBytes = maxListBytes) }
                        if (result is FetchResult.Ok) namesOf(result.body)?.takeIf { it.isNotEmpty() }?.let { lists.putIfAbsent(domain, it) }
                    }
                }.awaitAll()
        }
    }

    suspend fun verify(
        id: Nip05Id,
        pubkey: String,
    ): Nip05Check {
        lists[id.domain]?.get(id.name)?.let { return judge(it, pubkey) }
        return when (val result = fetch(id)) {
            null, is FetchResult.DomainDown -> Nip05Check.PENDING
            is FetchResult.Refused -> Nip05Check.UNKNOWN
            // A 404 is the domain saying it has no such name; a 403, a redirect or another 4xx
            // is more often a firewall or a misconfiguration than an answer about the user.
            is FetchResult.AddressGone -> if (result.status == 404) Nip05Check.NOT_LISTED else Nip05Check.UNKNOWN
            is FetchResult.Ok -> {
                val names = namesOf(result.body) ?: return Nip05Check.UNKNOWN
                // Many servers ignore `?name=` and answer with every name they hold.
                if (names.size > 1) lists.putIfAbsent(id.domain, names)
                names[id.name]?.let { judge(it, pubkey) } ?: Nip05Check.NOT_LISTED
            }
        }
    }

    private fun judge(
        listed: String,
        pubkey: String,
    ) = if (listed == pubkey) Nip05Check.VERIFIED else Nip05Check.LISTED_TO_OTHER

    /** One request per name per run: the same name asked twice is answered from here. */
    private suspend fun fetch(id: Nip05Id): FetchResult? {
        val url = id.toUserUrl()
        val mine = CompletableDeferred<FetchResult?>()
        val running = inFlight.putIfAbsent(url, mine)
        if (running != null) return running.await()
        return try {
            gate.call(id.domain) { http.get(url) }.also { mine.complete(it) }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        }
    }

    companion object {
        /** A `nostr.json`'s `names`, keys lowercased like [Nip05Id.name], values lowercased hex; null if it has none. */
        fun namesOf(json: String): Map<String, String>? {
            val names =
                try {
                    (Json.parseToJsonElement(json) as? JsonObject)?.get("names") as? JsonObject
                } catch (e: Exception) {
                    null
                } ?: return null
            return names.entries
                .mapNotNull { (name, key) -> (key as? JsonPrimitive)?.takeIf { it.isString }?.let { name.lowercase() to it.content.lowercase() } }
                .toMap()
        }
    }
}
