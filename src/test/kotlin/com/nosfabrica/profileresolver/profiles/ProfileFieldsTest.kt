package com.nosfabrica.profileresolver.profiles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileFieldsTest {
    // LUD-01's own example.
    private val lud01 =
        "LNURL1DP68GURN8GHJ7UM9WFMXJCM99E3K7MF0V9CXJ0M385EKVCENXC6R2C35XVUKXEFCV5MKVV34" +
            "X5EKZD3EV56NYD3HXQURZEPEXEJXXEPNXSCRVWFNV9NXZCN9XQ6XYEFHVGCXXCMYXYMNSERXFQ5FNS"
    private val lud01Url = "https://service.com/api?q=3fc3645b439ce8e7f2553a69e5267081d96dcd340693afabe04be7b0ccd178df"

    private fun parse(content: String) = ProfileFields.parse("a".repeat(64), 1, content)

    @Test
    fun lud16MapsToTheWellKnownLnurlpEndpoint() {
        assertEquals("https://getalby.com/.well-known/lnurlp/alice", parse("""{"lud16":"Alice@GetAlby.com"}""").lnurl)
    }

    @Test
    fun lud16WinsOverLud06() {
        assertEquals(
            "https://getalby.com/.well-known/lnurlp/alice",
            parse("""{"lud16":"alice@getalby.com","lud06":"$lud01"}""").lnurl,
        )
    }

    @Test
    fun lud06IsDecoded() {
        assertEquals(lud01Url, parse("""{"lud06":"$lud01"}""").lnurl)
    }

    @Test
    fun aLightningAddressInLud06IsReadAsOne() {
        assertEquals("https://walletofsatoshi.com/.well-known/lnurlp/bob", parse("""{"lud06":"bob@walletofsatoshi.com"}""").lnurl)
    }

    @Test
    fun punycodeDomainsAreAccepted() {
        assertEquals("https://xn--e1afmkfd.xn--p1ai/.well-known/lnurlp/bob", parse("""{"lud16":"bob@xn--e1afmkfd.xn--p1ai"}""").lnurl)
    }

    @Test
    fun garbageMeansNothing() {
        val fields = parse("""{"lud16":"not an address","lud06":42,"nip05":{}}""")
        assertNull(fields.lnurl)
        assertNull(fields.nip05)
        assertNull(parse("not json").lnurl)
        assertNull(parse("").nip05)
    }

    @Test
    fun nip05IsParsedLowercase() {
        val id = parse("""{"nip05":"Bob@Example.COM"}""").nip05!!
        assertEquals("bob", id.name)
        assertEquals("example.com", id.domain)
    }
}
