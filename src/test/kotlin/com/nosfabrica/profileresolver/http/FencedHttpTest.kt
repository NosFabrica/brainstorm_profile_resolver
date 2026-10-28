package com.nosfabrica.profileresolver.http

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Proxy
import java.net.UnknownHostException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FencedHttpTest {
    private val server = MockWebServer()

    // No address fence, plain http and any port, only so the test can reach its own server.
    private val http =
        FencedHttp(
            OkHttpClient
                .Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
            requireHttps = false,
            maxBodyBytes = 100,
        )

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun url(path: String = "/x") = server.url(path).toString()

    @Test
    fun okBody() =
        runTest {
            server.enqueue(MockResponse.Builder().body("""{"a":1}""").build())
            assertEquals(FetchResult.Ok("""{"a":1}"""), http.get(url()))
        }

    @Test
    fun notFoundIsTheAddressNotTheDomain() =
        runTest {
            server.enqueue(MockResponse.Builder().code(404).build())
            assertEquals(FetchResult.AddressGone(404), http.get(url()))
        }

    @Test
    fun rateLimitsMeanTheDomainIsDown() =
        runTest {
            server.enqueue(MockResponse.Builder().code(429).build())
            server.enqueue(MockResponse.Builder().code(408).build())
            assertIs<FetchResult.DomainDown>(http.get(url()))
            assertIs<FetchResult.DomainDown>(http.get(url()))
        }

    @Test
    fun aServerErrorIsThatUrlsNotTheDomains() =
        runTest {
            // A profile anyone writes can point at a path that 500s; that must not back off
            // the whole domain for everyone else on it.
            server.enqueue(MockResponse.Builder().code(503).build())
            assertEquals(FetchResult.ServerError(503), http.get(url()))
        }

    @Test
    fun redirectsAreNotFollowed() =
        runTest {
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://169.254.169.254/").build())
            assertEquals(FetchResult.AddressGone(302), http.get(url()))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun anOversizedBodyIsRefused() =
        runTest {
            server.enqueue(MockResponse.Builder().body("x".repeat(101)).build())
            assertIs<FetchResult.Refused>(http.get(url()))
        }

    @Test
    fun theDefaultFenceRefusesLoopbackAndPlainHttp() =
        runTest {
            val fenced = FencedHttp()
            assertIs<FetchResult.Refused>(fenced.get(url()))
            assertIs<FetchResult.Refused>(FencedHttp(FencedHttp.defaultClient(allowedPorts = null), requireHttps = false).get(url()))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun onlyTheHttpsPortIsAllowed() =
        runTest {
            assertIs<FetchResult.Refused>(FencedHttp().get("https://example.com:8443/x"))
        }

    @Test
    fun anUnreachableHostMeansTheDomainIsDown() =
        runTest {
            val failing =
                FencedHttp(FencedHttp.defaultClient(dns = Dns { throw UnknownHostException(it) }, allowedPorts = null), requireHttps = false)
            assertIs<FetchResult.DomainDown>(failing.get("http://nowhere.example/x"))
        }

    @Test
    fun anIpLiteralHostIsFencedToo() =
        runTest {
            // Regression: OkHttp never asks the Dns for an IP-literal host, so the fence must
            // check those itself. The server is up; the fetch must still be refused.
            server.enqueue(MockResponse.Builder().body("{}").build())
            val fenced = FencedHttp(FencedHttp.defaultClient(allowedPorts = null), requireHttps = false)
            assertIs<FetchResult.Refused>(fenced.get(server.url("/x").toString().replace("localhost", "127.0.0.1")))
            assertIs<FetchResult.Refused>(FencedHttp().get("https://[::1]/x"))
            assertIs<FetchResult.Refused>(FencedHttp().get("https://169.254.169.254/latest"))
            assertEquals(0, server.requestCount)
        }
}
