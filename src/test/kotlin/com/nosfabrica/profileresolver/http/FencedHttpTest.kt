package com.nosfabrica.profileresolver.http

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FencedHttpTest {
    private val server = MockWebServer()

    // Loopback is allowed here only so the test can reach its own server.
    private val http = FencedHttp(FencedHttp.defaultClient(PublicOnlyDns { true }), requireHttps = false, maxBodyBytes = 100)

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
    fun serverErrorsAndRateLimitsMeanTheDomainIsDown() =
        runTest {
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(MockResponse.Builder().code(429).build())
            assertIs<FetchResult.DomainDown>(http.get(url()))
            assertIs<FetchResult.DomainDown>(http.get(url()))
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
            assertIs<FetchResult.Refused>(FencedHttp(FencedHttp.defaultClient(), requireHttps = false).get(url()))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun anUnreachableHostMeansTheDomainIsDown() =
        runTest {
            val failing = FencedHttp(FencedHttp.defaultClient(Dns { throw java.net.UnknownHostException(it) }), requireHttps = false)
            assertIs<FetchResult.DomainDown>(failing.get("http://nowhere.example/x"))
        }
}
