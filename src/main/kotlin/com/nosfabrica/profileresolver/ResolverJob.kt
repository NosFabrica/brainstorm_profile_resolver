package com.nosfabrica.profileresolver

import com.nosfabrica.profileresolver.profiles.ProfileSource
import com.nosfabrica.profileresolver.store.GraphStore
import com.nosfabrica.profileresolver.store.KeyValueStore
import org.slf4j.LoggerFactory

/**
 * One nightly run:
 * 1. profiles — every graph user's on the first run (or in FULL mode), else only those changed
 *    since the last complete run;
 * 2. retries — lightning lookups a down domain left pending, and NIP-05s due a re-check.
 */
class ResolverJob(
    private val mode: Config.Mode,
    private val graph: GraphStore,
    private val store: KeyValueStore,
    private val source: ProfileSource,
    private val resolver: ProfileResolver,
    private val batchSize: Int = 500,
    /** Re-read this much before the cursor: a profile stored late can carry an earlier timestamp. */
    private val overlapSeconds: Long = 3600,
) {
    private val log = LoggerFactory.getLogger(ResolverJob::class.java)

    suspend fun run(startedAt: Long) {
        val cursor = store.cursor()
        if (mode == Config.Mode.FULL || cursor == null) {
            log.info("full pass over every graph user (cursor={})", cursor)
            fullPass()
            store.putCursor(startedAt)
        } else {
            val changed = source.changedSince(cursor - overlapSeconds)
            log.info("{} profiles changed since {} (walk ended {})", changed.profiles.size, cursor - overlapSeconds, changed.end)
            changed.profiles.chunked(batchSize).forEach { resolver.process(it) }
            // Only a walk the relay drained proves nothing older is missing; otherwise the
            // next run reads the same window again.
            if (changed.complete) store.putCursor(startedAt)
        }
        resolver.retryDue(batchSize)
        log.info("done: {}", resolver.tally)
    }

    private suspend fun fullPass() {
        var after = ""
        while (true) {
            val page = graph.pubkeysAfter(after, batchSize)
            if (page.isEmpty()) return
            resolver.process(source.byAuthors(page))
            after = page.last()
        }
    }
}
