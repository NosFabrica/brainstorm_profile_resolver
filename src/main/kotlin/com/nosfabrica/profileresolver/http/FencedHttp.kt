package com.nosfabrica.profileresolver.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
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

    /** The server could not answer: unreachable, timed out, TLS, 5xx, 429. Back off the domain. */
    data class DomainDown(
        val reason: String,
    ) : FetchResult

    /** Never fetched and never will be: not https, a non-public host, an oversized body. */
    data class Refused(
        val reason: String,
    ) : FetchResult
}

/** Thrown by [PublicOnlyDns]: the host resolves, but not to anything we may connect to. */
class NonPublicHostException(
    host: String,
) : UnknownHostException("$host resolves to no public address")

/**
 * Resolves only to public addresses, and OkHttp connects to exactly what this returns —
 * so a host cannot pass the check and then rebind to an internal address.
 */
class PublicOnlyDns(
    private val allowed: (InetAddress) -> Boolean = PublicAddress::isPublic,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (addresses.any { !allowed(it) }) throw NonPublicHostException(hostname)
        return addresses
    }
}

object PublicAddress {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val b = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> isPublicV4(b)
            is Inet6Address -> isPublicV6(b)
            else -> false
        }
    }

    private fun isPublicV4(b: List<Int>): Boolean =
        !(
            b[0] == 0 || // "this" network
                (b[0] == 100 && b[1] in 64..127) || // carrier-grade NAT
                (b[0] == 192 && b[1] == 0 && b[2] == 0) || // IETF protocol assignments
                (b[0] == 192 && b[1] == 0 && b[2] == 2) || // documentation
                (b[0] == 198 && b[1] in 18..19) || // benchmarking
                (b[0] == 198 && b[1] == 51 && b[2] == 100) || // documentation
                (b[0] == 203 && b[1] == 0 && b[2] == 113) || // documentation
                b[0] >= 240 // reserved and broadcast
        )

    private fun isPublicV6(b: List<Int>): Boolean {
        if (b[0] and 0xfe == 0xfc) return false // unique local fc00::/7
        if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) return false // documentation
        // IPv4-mapped (::ffff:a.b.c.d) and NAT64 (64:ff9b::a.b.c.d) carry an IPv4 address.
        val mapped = (0..9).all { b[it] == 0 } && b[10] == 0xff && b[11] == 0xff
        val nat64 = b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b && (4..11).all { b[it] == 0 }
        if (mapped || nat64) return isPublic(InetAddress.getByAddress(b.takeLast(4).map { it.toByte() }.toByteArray()))
        return true
    }
}

/**
 * GETs URLs that come from profiles anyone can write: https only, public hosts only,
 * no redirects, a capped body. Every failure is classified, never thrown.
 */
open class FencedHttp(
    private val client: OkHttpClient = defaultClient(),
    private val requireHttps: Boolean = true,
    private val maxBodyBytes: Long = 64 * 1024,
) {
    open suspend fun get(
        url: String,
        accept: String = "application/json",
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
                            if (source.request(maxBodyBytes + 1)) {
                                FetchResult.Refused("body over $maxBodyBytes bytes")
                            } else {
                                FetchResult.Ok(source.buffer.readUtf8())
                            }
                        }
                        response.code == 429 || response.code == 408 || response.code >= 500 ->
                            FetchResult.DomainDown("HTTP ${response.code}")
                        else -> FetchResult.AddressGone(response.code)
                    }
                }
            } catch (e: NonPublicHostException) {
                FetchResult.Refused(e.message ?: "non-public host")
            } catch (e: IOException) {
                FetchResult.DomainDown(e.javaClass.simpleName + ": " + e.message)
            }
        }
    }

    companion object {
        const val USER_AGENT = "brainstorm-profile-resolver (+https://brainstorm.world)"

        fun defaultClient(dns: Dns = PublicOnlyDns()): OkHttpClient =
            OkHttpClient
                .Builder()
                // Direct only: through a proxy OkHttp resolves the proxy, never the target,
                // and the public-address check would be skipped.
                .proxy(Proxy.NO_PROXY)
                .dns(dns)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .build()
    }
}
