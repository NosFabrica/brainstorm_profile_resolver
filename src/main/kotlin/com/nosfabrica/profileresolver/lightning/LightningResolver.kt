package com.nosfabrica.profileresolver.lightning

import com.nosfabrica.profileresolver.http.DomainGate
import com.nosfabrica.profileresolver.http.FencedHttp
import com.nosfabrica.profileresolver.http.FetchResult
import com.nosfabrica.profileresolver.http.domainOf
import com.nosfabrica.profileresolver.store.AddressAnswer
import com.nosfabrica.profileresolver.store.KeyValueStore
import com.nosfabrica.profileresolver.store.LnStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the key that signs an address's zap receipts (NIP-57 `nostrPubkey`).
 *
 * Every address is fetched once: users who share it share the one answer, inside a run and
 * across runs, and only an address that changes is fetched again. A domain that is down is
 * left alone until its backoff ends, and its addresses stay [LnStatus.PENDING] meanwhile.
 */
class LightningResolver(
    private val http: FencedHttp,
    private val gate: DomainGate,
    private val store: KeyValueStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<AddressAnswer>>()

    suspend fun resolve(url: String): AddressAnswer {
        store.addressAnswer(url)?.let { if (it.status.isFinal) return it }

        val mine = CompletableDeferred<AddressAnswer>()
        val running = inFlight.putIfAbsent(url, mine)
        if (running != null) return running.await()
        return try {
            fetch(url).also { answer ->
                if (answer.status.isFinal) store.putAddressAnswer(url, answer)
                mine.complete(answer)
            }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(url)
        }
    }

    private suspend fun fetch(url: String): AddressAnswer {
        val domain = domainOf(url) ?: return answer(LnStatus.INVALID)
        return when (val result = gate.call(domain) { http.get(url) }) {
            null, is FetchResult.DomainDown, is FetchResult.ServerError -> answer(LnStatus.PENDING)
            is FetchResult.Refused -> answer(LnStatus.INVALID)
            // Only a 404 or 410 says the address doesn't exist; a 403, a redirect or another 4xx
            // is usually a firewall or a misconfiguration, so it is retried, not settled.
            is FetchResult.AddressGone ->
                answer(if (result.status == 404 || result.status == 410) LnStatus.NOT_FOUND else LnStatus.UNKNOWN)
            is FetchResult.Ok -> parse(result.body)?.let { answer(LnStatus.OK, it) } ?: answer(LnStatus.NO_ZAPS)
        }
    }

    private fun answer(
        status: LnStatus,
        signer: String? = null,
    ) = AddressAnswer(status, signer, nowSeconds())

    companion object {
        private val HEX_PUBKEY = Regex("^[0-9a-f]{64}$")

        /** `nostrPubkey` from an LNURL-pay answer that `allowsNostr`, or null. */
        fun parse(body: String): String? {
            val json =
                try {
                    Json.parseToJsonElement(body) as? JsonObject
                } catch (e: Exception) {
                    null
                } ?: return null
            // A JSON `true`, not the string "true" (which booleanOrNull alone would accept).
            val allowsNostr = json["allowsNostr"] as? JsonPrimitive
            if (allowsNostr == null || allowsNostr.isString || allowsNostr.booleanOrNull != true) return null
            val key = (json["nostrPubkey"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() ?: return null
            return key.takeIf { HEX_PUBKEY.matches(it) }
        }
    }
}
