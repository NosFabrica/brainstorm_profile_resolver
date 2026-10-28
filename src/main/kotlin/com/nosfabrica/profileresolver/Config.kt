package com.nosfabrica.profileresolver

/** Everything the job reads from the environment. Names match the GrapeRank worker's. */
data class Config(
    val neo4jUrl: String,
    val neo4jUsername: String,
    val neo4jPassword: String,
    val redisHost: String,
    val redisPort: Int,
    /** Our search relay: it mirrors every kind-0 and verifies what it stores. */
    val searchRelayUrl: String = "wss://search.brainstorm.world",
    val mode: Mode = Mode.INCREMENTAL,
    /** A verified NIP-05 is re-checked after this long: domains drop names. */
    val nip05RecheckDays: Long = 30,
    /** At most one request per domain per this many milliseconds. */
    val perDomainIntervalMs: Long = 500,
    /** At most this many outbound lookups in flight across all domains. */
    val maxConcurrentLookups: Int = 16,
    /** A full pass this often picks up users new to the graph; 0 turns it off. */
    val fullPassEveryDays: Long = 7,
) {
    enum class Mode {
        /** Profiles changed since the last run's cursor; the first run, and one a week, is FULL. */
        INCREMENTAL,

        /** Every graph user's profile, read by author. */
        FULL,
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() } ?: error("$name is not set")
            return Config(
                neo4jUrl = required("NEO4J_URL"),
                neo4jUsername = required("NEO4J_USERNAME"),
                neo4jPassword = required("NEO4J_PASSWORD"),
                redisHost = required("REDIS_HOST"),
                redisPort = required("REDIS_PORT").toInt(),
                searchRelayUrl = env["SEARCH_RELAY_URL"]?.takeIf { it.isNotBlank() } ?: "wss://search.brainstorm.world",
                mode = env["RESOLVER_MODE"]?.takeIf { it.isNotBlank() }?.let { Mode.valueOf(it.uppercase()) } ?: Mode.INCREMENTAL,
                nip05RecheckDays = env["NIP05_RECHECK_DAYS"]?.toLong() ?: 30,
                perDomainIntervalMs = env["PER_DOMAIN_INTERVAL_MS"]?.toLong() ?: 500,
                maxConcurrentLookups = env["MAX_CONCURRENT_LOOKUPS"]?.toInt() ?: 16,
                fullPassEveryDays = env["FULL_PASS_EVERY_DAYS"]?.toLong() ?: 7,
            )
        }
    }
}
