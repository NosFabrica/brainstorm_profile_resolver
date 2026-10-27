package com.nosfabrica.profileresolver.profiles

import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.fetchAllPages
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertTrue

/** Against the real search relay; skipped unless LIVE_SEARCH_RELAY is set. */
class SearchRelayLiveTest {
    private val enabled = System.getenv("LIVE_SEARCH_RELAY") != null
    private val relayUrl = "wss://search.brainstorm.world"

    @Test
    fun readsRecentProfilesAndProfilesByAuthor() {
        if (!enabled) return
        runBlocking {
            SearchRelayProfiles(relayUrl).use { relay ->
                val changed = relay.changedSince(System.currentTimeMillis() / 1000 - 600)
                println("changed in the last 10 min: ${changed.profiles.size} (end=${changed.end})")
                assertTrue(changed.profiles.isNotEmpty())

                val authors = changed.profiles.map { it.pubkey }.distinct().take(50)
                val byAuthor = relay.byAuthors(authors)
                println("by author: ${byAuthor.size}/${authors.size}; with lnurl ${byAuthor.count { it.lnurl != null }}, nip05 ${byAuthor.count { it.nip05 != null }}")
                assertTrue(byAuthor.isNotEmpty())
            }
            System.getenv("LIVE_AUTHORS")?.split(",")?.let { authors ->
                SearchRelayProfiles(relayUrl).use { relay ->
                    val found = relay.byAuthors(authors)
                    println("known lud16 authors: ${found.size}/${authors.size} found; lnurl ${found.count { it.lnurl != null }}, nip05 ${found.count { it.nip05 != null }}")
                    found.take(3).forEach { println("  ${it.pubkey.take(8)} ${it.lnurl} ${it.nip05?.toValue()}") }
                }
            }
            if (System.getenv("LIVE_DUMP") != null) dumpRaw()
        }
    }

    private suspend fun dumpRaw() {
        val client = NostrClient(BasicOkHttpWebSocket.Builder { OkHttpClient() })
        var shown = 0
        client.fetchAllPages(
            RelayUrlNormalizer.normalize(relayUrl),
            listOf(Filter(kinds = listOf(0), since = System.currentTimeMillis() / 1000 - 600, search = "include:spam")),
        ) { event ->
            if (shown++ < 5) println("RAW ${event::class.simpleName} content=${event.content.take(200)}")
        }
        client.close()
    }
}
