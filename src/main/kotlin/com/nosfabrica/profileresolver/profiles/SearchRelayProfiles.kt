package com.nosfabrica.profileresolver.profiles

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.PagedFetchResult
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAllPagesResuming
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Where profiles come from. */
interface ProfileSource {
    /** Every profile changed since [since]; `complete` only if the relay said it had no more. */
    suspend fun changedSince(since: Long): Changed

    /** The latest profile of each of [pubkeys] the relay holds. */
    suspend fun byAuthors(pubkeys: List<String>): List<ProfileFields>

    data class Changed(
        val profiles: List<ProfileFields>,
        val complete: Boolean,
        val end: String,
    )
}

/**
 * Our search relay over a websocket, with Quartz's paging. Reads are unranked
 * (`include:spam`): these are lookups, not searches, and without a ranking observer the relay
 * refuses anonymous reads. An extension-only search pages newest first, so Quartz walks it, and
 * resumes the walk from where it got to when the connection drops or a page goes silent, instead
 * of ending it (and leaving the cursor to re-read the whole window next night).
 * No signature checks — we run this relay, and it verifies what it stores.
 */
class SearchRelayProfiles(
    relayUrl: String,
    private val client: INostrClient = NostrClient(BasicOkHttpWebSocket.Builder { relayHttp }),
    private val pageSize: Int = 500,
) : ProfileSource,
    AutoCloseable {
    private val relay = RelayUrlNormalizer.normalize(relayUrl)

    override suspend fun changedSince(since: Long): ProfileSource.Changed {
        val profiles = mutableListOf<ProfileFields>()
        val result =
            client.fetchAllPagesResuming(
                relay = relay,
                filters = listOf(Filter(kinds = listOf(0), since = since, search = UNRANKED)),
                pageSize = pageSize,
            ) { event -> fieldsOf(event)?.let { profiles.add(it) } }
        return ProfileSource.Changed(profiles, result.end == PagedFetchResult.End.DRAINED, result.end.name + (result.message?.let { ": $it" } ?: ""))
    }

    override suspend fun byAuthors(pubkeys: List<String>): List<ProfileFields> {
        if (pubkeys.isEmpty()) return emptyList()
        val profiles = mutableListOf<ProfileFields>()
        client.fetchAllPagesResuming(
            relay = relay,
            filters = listOf(Filter(kinds = listOf(0), authors = pubkeys, search = UNRANKED)),
            pageSize = pageSize,
        ) { event -> fieldsOf(event)?.let { profiles.add(it) } }
        return profiles
    }

    override fun close() = client.close()

    private fun fieldsOf(event: Event): ProfileFields? = if (event.kind == 0) ProfileFields.parse(event.pubKey, event.createdAt, event.content) else null

    companion object {
        const val UNRANKED = "include:spam"

        private val relayHttp =
            OkHttpClient
                .Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
