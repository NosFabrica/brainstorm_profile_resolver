package com.nosfabrica.profileresolver

import com.nosfabrica.profileresolver.http.DomainGate
import com.nosfabrica.profileresolver.http.FencedHttp
import com.nosfabrica.profileresolver.lightning.LightningResolver
import com.nosfabrica.profileresolver.nip05.Nip05Verifier
import com.nosfabrica.profileresolver.profiles.SearchRelayProfiles
import com.nosfabrica.profileresolver.store.Neo4jGraphStore
import com.nosfabrica.profileresolver.store.RedisKeyValueStore
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/** One run, then exit: scheduled nightly by a Kubernetes CronJob. */
fun main() {
    val config = Config.fromEnv()
    val startedAt = System.currentTimeMillis() / 1000
    val ok =
        try {
            runBlocking { run(config, startedAt) }
            true
        } catch (e: Throwable) {
            LoggerFactory.getLogger("main").error("run failed", e)
            false
        }
    // Exit explicitly either way: the relay client's threads would otherwise keep the JVM — and
    // the CronJob's pod — alive, and a failure must show as one.
    exitProcess(if (ok) 0 else 1)
}

private suspend fun run(
    config: Config,
    startedAt: Long,
) {
    Neo4jGraphStore(config.neo4jUrl, config.neo4jUsername, config.neo4jPassword).use { graph ->
        RedisKeyValueStore(config.redisHost, config.redisPort).use { store ->
            SearchRelayProfiles(config.searchRelayUrl).use { source ->
                val http = FencedHttp()
                val gate = DomainGate(store, config.perDomainIntervalMs, config.maxConcurrentLookups)
                val resolver =
                    ProfileResolver(
                        graph = graph,
                        store = store,
                        lightning = LightningResolver(http, gate, store),
                        nip05 = Nip05Verifier(http, gate),
                        nip05RecheckSeconds = config.nip05RecheckDays * 86_400,
                    )
                ResolverJob(
                    config.mode,
                    graph,
                    store,
                    source,
                    resolver,
                    fullPassEverySeconds = config.fullPassEveryDays.takeIf { it > 0 }?.let { it * 86_400 },
                ).run(startedAt)
            }
        }
    }
}
