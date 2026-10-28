package com.nosfabrica.profileresolver.profiles

import com.vitorpamplona.quartz.lightning.Lud06
import com.vitorpamplona.quartz.nip05DnsIdentifiers.Nip05Id
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a kind-0 says that this job resolves: where its zaps go and who vouches for its name. */
data class ProfileFields(
    val pubkey: String,
    val createdAt: Long,
    /** The LNURL-pay endpoint the profile's `lud16` (else `lud06`) points at. */
    val lnurl: String?,
    val nip05: Nip05Id?,
) {
    companion object {
        // LUD-16: the username is a-z0-9-_.+ ; the domain a plain hostname.
        private val LUD16 = Regex("^([a-z0-9\\-_.+]+)@([a-z0-9\\-]+(?:\\.[a-z0-9\\-]+)+)$")

        fun parse(
            pubkey: String,
            createdAt: Long,
            content: String,
        ): ProfileFields {
            val json =
                try {
                    Json.parseToJsonElement(content) as? JsonObject
                } catch (e: Exception) {
                    null
                }
            fun field(name: String) = (json?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            return ProfileFields(
                pubkey = pubkey,
                createdAt = createdAt,
                lnurl = lnurlOf(field("lud16"), field("lud06")),
                nip05 = field("nip05")?.let { Nip05Id.parse(it) },
            )
        }

        /** `lud16` first, as NIP-57 reads it, else `lud06`. A `lud06` holding a
         * lightning address, a common mix-up, is read as one. */
        fun lnurlOf(
            lud16: String?,
            lud06: String?,
        ): String? =
            lud16?.let { lud16Url(it) }
                ?: lud06?.let { lud16Url(it) ?: lud06Url(it) }

        fun lud16Url(address: String): String? {
            val match = LUD16.matchEntire(address.trim().lowercase().removePrefix("lightning:")) ?: return null
            val (name, domain) = match.destructured
            return "https://$domain/.well-known/lnurlp/$name"
        }

        fun lud06Url(lnurl: String): String? {
            val url =
                try {
                    Lud06().toLnUrlp(lnurl.trim())
                } catch (e: Exception) {
                    null
                } ?: return null
            return url.takeIf { it.startsWith("https://", ignoreCase = true) }
        }
    }
}
