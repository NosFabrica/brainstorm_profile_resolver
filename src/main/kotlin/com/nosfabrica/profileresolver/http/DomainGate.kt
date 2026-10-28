package com.nosfabrica.profileresolver.http

import com.nosfabrica.profileresolver.store.DomainHealth
import com.nosfabrica.profileresolver.store.KeyValueStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The only way a lookup reaches a domain. A domain that is down is not asked again until its
 * backoff ends, by any address on it; one that is up is asked at most once per
 * [perDomainIntervalMs]; and no more than [maxConcurrent] requests are in flight overall.
 */
class DomainGate(
    private val store: KeyValueStore,
    private val perDomainIntervalMs: Long,
    maxConcurrent: Int,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val permits = Semaphore(maxConcurrent)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastRequestAt = ConcurrentHashMap<String, Long>()

    /** Health as this run sees it: the store once, then this run's own updates. */
    private val health = ConcurrentHashMap<String, DomainHealth>()
    private val healthy = ConcurrentHashMap.newKeySet<String>()

    fun isDown(domain: String): Boolean {
        if (domain in healthy) return false
        val known = health[domain] ?: store.domainHealth(domain)?.also { health[domain] = it } ?: return false
        return known.retryAt > nowSeconds()
    }

    /** Runs [fetch] against [domain], or returns null without asking it while it is down. */
    suspend fun <T : FetchResult> call(
        domain: String,
        fetch: suspend () -> T,
    ): T? {
        if (isDown(domain)) return null
        // The domain's turn first, then a global slot: a slot is never held while waiting on one
        // busy domain's spacing, so other domains keep moving.
        return locks.computeIfAbsent(domain) { Mutex() }.withLock {
            // Another lookup may have found it down while this one waited.
            if (isDown(domain)) return@withLock null
            val wait = (lastRequestAt[domain] ?: 0) + perDomainIntervalMs - nowMillis()
            if (wait > 0) delay(wait)
            permits.withPermit {
                lastRequestAt[domain] = nowMillis()
                fetch().also { record(domain, it) }
            }
        }
    }

    private fun record(
        domain: String,
        result: FetchResult,
    ) {
        if (result is FetchResult.DomainDown) {
            val failures = (health[domain] ?: store.domainHealth(domain))?.failures?.plus(1) ?: 1
            val next = DomainHealth(failures, nowSeconds() + backoffSeconds(failures))
            health[domain] = next
            healthy.remove(domain)
            store.putDomainHealth(domain, next)
        } else if (domain !in healthy) {
            // Any answer at all means the server is up.
            healthy.add(domain)
            if ((health.remove(domain) ?: store.domainHealth(domain)) != null) store.putDomainHealth(domain, null)
        }
    }

    companion object {
        private val BACKOFF_HOURS = longArrayOf(1, 6, 24, 72, 168)

        /** 1 h, 6 h, 1 day, 3 days, then weekly. */
        fun backoffSeconds(failures: Int): Long = BACKOFF_HOURS[(failures - 1).coerceIn(0, BACKOFF_HOURS.lastIndex)] * 3600
    }
}

/** The gate key for a URL: its host as OkHttp will dial it, lowercased, without a trailing dot. */
fun domainOf(url: String): String? = url.toHttpUrlOrNull()?.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
