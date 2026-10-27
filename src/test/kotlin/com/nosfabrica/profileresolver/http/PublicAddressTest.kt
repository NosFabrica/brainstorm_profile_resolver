package com.nosfabrica.profileresolver.http

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicAddressTest {
    private fun public(ip: String) = PublicAddress.isPublic(InetAddress.getByName(ip))

    @Test
    fun internalAddressesAreNotPublic() {
        listOf(
            "127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "198.18.0.1", "240.0.0.1", "::1", "fc00::1", "fe80::1",
            "::ffff:10.0.0.1", "64:ff9b::a00:1",
        ).forEach { assertFalse(public(it), it) }
    }

    @Test
    fun publicAddressesArePublic() {
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "::ffff:1.1.1.1").forEach { assertTrue(public(it), it) }
    }
}
