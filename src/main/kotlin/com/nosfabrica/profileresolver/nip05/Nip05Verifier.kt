package com.nosfabrica.profileresolver.nip05

import com.nosfabrica.profileresolver.http.DomainGate
import com.nosfabrica.profileresolver.http.FencedHttp
import com.nosfabrica.profileresolver.http.FetchResult
import com.nosfabrica.profileresolver.store.Nip05Status
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Id
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Parser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Checks that a NIP-05 domain maps the name to the pubkey (`names` in its `nostr.json`).
 *
 * Unlike a lightning answer this is never final for good — a domain can drop a name — so the
 * caller re-checks verified names on a schedule. Within one run, a domain that answered with its
 * whole `names` list (many ignore `?name=`) answers its other names from that list.
 */
class Nip05Verifier(
    private val http: FencedHttp,
    private val gate: DomainGate,
) {
    private val parser = Nip05Parser()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<FetchResult?>>()

    /** Per domain, a `nostr.json` that listed more than one name. */
    private val fullLists = ConcurrentHashMap<String, String>()

    suspend fun verify(
        id: Nip05Id,
        pubkey: String,
    ): Nip05Status {
        fullLists[id.domain]?.let { list ->
            keyIn(id, list)?.let { return if (it == pubkey) Nip05Status.VERIFIED else Nip05Status.MISMATCH }
            // Not in the list: it may not have been the whole list after all, so ask.
        }
        return when (val result = fetch(id)) {
            null, is FetchResult.DomainDown -> Nip05Status.PENDING
            is FetchResult.Refused -> Nip05Status.INVALID
            is FetchResult.AddressGone -> Nip05Status.NOT_FOUND
            is FetchResult.Ok -> {
                if (namesIn(result.body) > 1) fullLists.putIfAbsent(id.domain, result.body)
                when (keyIn(id, result.body)) {
                    null -> Nip05Status.MISMATCH
                    pubkey -> Nip05Status.VERIFIED
                    else -> Nip05Status.MISMATCH
                }
            }
        }
    }

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
        // Kept for the run: the same name asked twice is answered from here.
    }

    private fun keyIn(
        id: Nip05Id,
        json: String,
    ): String? =
        try {
            parser.parseHexKey(id, json)?.lowercase()
        } catch (e: Exception) {
            null
        }

    private fun namesIn(json: String): Int =
        try {
            ((Json.parseToJsonElement(json) as? JsonObject)?.get("names") as? JsonObject)?.size ?: 0
        } catch (e: Exception) {
            0
        }
}
