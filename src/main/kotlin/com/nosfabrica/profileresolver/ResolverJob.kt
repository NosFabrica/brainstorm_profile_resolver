package com.nosfabrica.profileresolver

import com.nosfabrica.profileresolver.profiles.ProfileSource
import com.nosfabrica.profileresolver.store.FullPass
import com.nosfabrica.profileresolver.store.GraphStore
import com.nosfabrica.profileresolver.store.KeyValueStore
import org.slf4j.LoggerFactory

/**
 * One nightly run:
 * 1. profiles — a full pass over every graph user's profile, or only those changed since the
 *    last complete pass;
 * 2. retries — lightning lookups a down domain left pending, and NIP-05s due a re-check.
 *
 * A full pass runs on the first run, in FULL mode, and every [fullPassEverySeconds]: the
 * incremental walk sees only changed profiles, so a user who joins the graph with an old profile
 * is first read by a full pass. A full pass that outlives its run resumes where it stopped.
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
    /** Null: only the first run and FULL mode do a full pass. */
    private val fullPassEverySeconds: Long? = 7 * 86_400,
) {
    private val log = LoggerFactory.getLogger(ResolverJob::class.java)

    suspend fun run(startedAt: Long) {
        val cursor = store.cursor()
        val unfinished = store.fullPass()
        val lastFull = store.lastFullPassAt()
        val fullDue = fullPassEverySeconds != null && (lastFull == null || startedAt - lastFull >= fullPassEverySeconds)
        if (unfinished != null || mode == Config.Mode.FULL || cursor == null || fullDue) {
            val pass = unfinished ?: FullPass(startedAt, "")
            log.info("full pass over every graph user (cursor={}, started={}, after='{}')", cursor, pass.startedAt, pass.after)
            fullPass(pass)
        } else {
            val changed = source.changedSince(cursor - overlapSeconds)
            // The walk is newest-first and can hold an author twice; keep each author's newest
            // across the whole walk, or an older profile in a later batch would undo a newer one.
            val latest = changed.profiles.groupBy { it.pubkey }.map { (_, p) -> p.maxBy { it.createdAt } }
            log.info("{} profiles changed since {} (walk ended {})", latest.size, cursor - overlapSeconds, changed.end)
            latest.chunked(batchSize).forEach { resolver.process(it) }
            // Only a walk the relay drained proves nothing older is missing; otherwise the
            // next run reads the same window again.
            if (changed.complete) store.putCursor(startedAt)
        }
        resolver.retryDue(batchSize)
        resolver.finish()
        log.info("done: {}", resolver.tally)
    }

    private suspend fun fullPass(pass: FullPass) {
        store.putFullPass(pass)
        var after = pass.after
        while (true) {
            val page = graph.pubkeysAfter(after, batchSize)
            if (page.isEmpty()) break
            resolver.process(source.byAuthors(page))
            after = page.last()
            store.putFullPass(pass.copy(after = after))
        }
        store.putFullPass(null)
        store.putLastFullPassAt(pass.startedAt)
        // Profiles that changed behind the pass after it began are read by the next incremental run.
        store.putCursor(pass.startedAt)
    }
}
