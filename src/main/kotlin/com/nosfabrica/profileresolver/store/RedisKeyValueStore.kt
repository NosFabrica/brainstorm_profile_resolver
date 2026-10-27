package com.nosfabrica.profileresolver.store

import redis.clients.jedis.JedisPool
import java.security.MessageDigest

/**
 * Keys:
 * - `zaps_via:<pubkey>` — SET of the one key that signs that user's zap receipts. The forward set
 *   the GrapeRank worker reads, beside `designates:<pubkey>`.
 * - `lnurl:<sha256(url)>` — HASH of an address's answer, so it is fetched once.
 * - `resolver_domain:<domain>` — HASH of a domain's failures and when to ask it again.
 * - `profile_resolver:cursor` — when the last complete incremental pass started.
 */
class RedisKeyValueStore(
    private val pool: JedisPool,
) : KeyValueStore,
    AutoCloseable {
    constructor(host: String, port: Int) : this(JedisPool(host, port))

    override fun close() = pool.close()

    override fun addressAnswer(url: String): AddressAnswer? =
        pool.resource.use { redis ->
            val h = redis.hgetAll(addressKey(url))
            val status = h["status"]?.let { s -> LnStatus.entries.firstOrNull { it.name == s } } ?: return null
            AddressAnswer(status, h["signer"], h["checked_at"]?.toLongOrNull() ?: 0)
        }

    override fun putAddressAnswer(
        url: String,
        answer: AddressAnswer,
    ) {
        pool.resource.use { redis ->
            val fields = mutableMapOf("url" to url, "status" to answer.status.name, "checked_at" to answer.checkedAt.toString())
            answer.signer?.let { fields["signer"] = it }
            val key = addressKey(url)
            redis.multi().apply {
                del(key)
                hset(key, fields)
                exec()
            }
        }
    }

    override fun domainHealth(domain: String): DomainHealth? =
        pool.resource.use { redis ->
            val h = redis.hgetAll(DOMAIN_PREFIX + domain)
            val failures = h["failures"]?.toIntOrNull() ?: return null
            DomainHealth(failures, h["retry_at"]?.toLongOrNull() ?: 0)
        }

    override fun putDomainHealth(
        domain: String,
        health: DomainHealth?,
    ) {
        pool.resource.use { redis ->
            if (health == null) {
                redis.del(DOMAIN_PREFIX + domain)
            } else {
                redis.hset(DOMAIN_PREFIX + domain, mapOf("failures" to health.failures.toString(), "retry_at" to health.retryAt.toString()))
            }
        }
    }

    override fun cursor(): Long? = pool.resource.use { it.get(CURSOR_KEY)?.toLongOrNull() }

    override fun putCursor(epochSeconds: Long) {
        pool.resource.use { it.set(CURSOR_KEY, epochSeconds.toString()) }
    }

    override fun setZapsVia(
        pubkey: String,
        signer: String?,
    ) {
        pool.resource.use { redis ->
            val key = ZAPS_VIA_PREFIX + pubkey
            // Replaced whole, atomically: the worker never reads half of a change.
            redis.multi().apply {
                del(key)
                if (signer != null) sadd(key, signer)
                exec()
            }
        }
    }

    companion object {
        const val ZAPS_VIA_PREFIX = "zaps_via:"
        const val DOMAIN_PREFIX = "resolver_domain:"
        const val CURSOR_KEY = "profile_resolver:cursor"

        /** A `lud06` URL can be long; its hash keeps the key small. */
        fun addressKey(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            return "lnurl:" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
