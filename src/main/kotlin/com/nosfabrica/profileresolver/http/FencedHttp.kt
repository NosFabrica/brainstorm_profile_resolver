package com.nosfabrica.profileresolver.http

import com.vitorpamplona.quartz.utils.okhttp.NonPublicAddressException
import com.vitorpamplona.quartz.utils.okhttp.publicAddressesOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What one GET came to, in the terms the resolvers act on. */
sealed interface FetchResult {
    /** 200, with a body within the cap. */
    data class Ok(
        val body: String,
    ) : FetchResult

    /** The server answered, but not for this address (404, other 4xx, a redirect). */
    data class AddressGone(
        val status: Int,
    ) : FetchResult

    /** The server could not answer: unreachable, timed out, TLS, 429, 408. Back off the domain. */
    data class DomainDown(
        val reason: String,
    ) : FetchResult

    /**
     * The server is up but failed this URL (a 5xx). Retry the URL later; don't back off the
     * domain — a 5xx can be one bad path, and a profile anyone writes can point at one.
     */
    data class ServerError(
        val status: Int,
    ) : FetchResult

    /** Never fetched and never will be: not https, a non-public host, an oversized body. */
    data class Refused(
        val reason: String,
    ) : FetchResult
}

/**
 * GETs URLs that come from profiles anyone can write: https only, public hosts on port 443 only,
 * no redirects, a capped body. Every failure is classified, never thrown.
 *
 * The address fence is Quartz's `publicAddressesOnly` on [defaultClient]: host names resolve
 * only to public addresses, OkHttp connects to exactly those, and IP-literal hosts (which OkHttp
 * never sends to the resolver) are checked on their own.
 */
open class FencedHttp(
    private val client: OkHttpClient = defaultClient(),
    private val requireHttps: Boolean = true,
    private val maxBodyBytes: Long = 64 * 1024,
) {
    open suspend fun get(
        url: String,
        accept: String = "application/json",
        /** Overrides the client's cap for this one request (a domain's whole NIP-05 list). */
        maxBytes: Long = maxBodyBytes,
    ): FetchResult {
        val httpUrl = url.toHttpUrlOrNull() ?: return FetchResult.Refused("not a URL")
        if (requireHttps && !httpUrl.isHttps) return FetchResult.Refused("not https")
        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return FetchResult.Refused("credentials in URL")

        val request =
            Request
                .Builder()
                .url(httpUrl)
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                .build()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 200 -> {
                            val source = response.body.source()
                            if (source.request(maxBytes + 1)) {
                                FetchResult.Refused("body over $maxBytes bytes")
                            } else {
                                FetchResult.Ok(source.buffer.readUtf8())
                            }
                        }
                        response.code == 429 || response.code == 408 -> FetchResult.DomainDown("HTTP ${response.code}")
                        response.code >= 500 -> FetchResult.ServerError(response.code)
                        else -> FetchResult.AddressGone(response.code)
                    }
                }
            } catch (e: NonPublicAddressException) {
                FetchResult.Refused(e.message ?: "non-public address")
            } catch (e: IOException) {
                FetchResult.DomainDown(e.javaClass.simpleName + ": " + e.message)
            }
        }
    }

    companion object {
        const val USER_AGENT = "brainstorm-profile-resolver (+https://brainstorm.world)"

        /**
         * Public addresses only, port 443 only, no proxy (through one only the proxy would be
         * resolved, and the target would go unchecked), no redirects. [allowedPorts] null and
         * [dns] are for tests.
         */
        fun defaultClient(
            dns: Dns = Dns.SYSTEM,
            allowedPorts: Set<Int>? = setOf(443),
        ): OkHttpClient =
            OkHttpClient
                .Builder()
                .publicAddressesOnly(direct = true, allowedPorts = allowedPorts, dns = dns)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
    }
}
