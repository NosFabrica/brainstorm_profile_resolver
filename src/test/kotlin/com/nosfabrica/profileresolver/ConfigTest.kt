package com.nosfabrica.profileresolver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ConfigTest {
    private val base =
        mapOf(
            "NEO4J_URL" to "neo4j://neo4j:7687",
            "NEO4J_USERNAME" to "neo4j",
            "NEO4J_PASSWORD" to "x",
            "REDIS_HOST" to "redis",
            "REDIS_PORT" to "6379",
        )
    private val scorecard =
        mapOf(
            "SCORECARD_DB_HOST" to "scorecard-postgres",
            "SCORECARD_DB_PORT" to "5432",
            "SCORECARD_DB_NAME" to "scorecard",
            "SCORECARD_DB_USER" to "postgres",
            "SCORECARD_DB_PASSWORD" to "pw",
        )

    @Test
    fun idsAreOffUnlessTurnedOn() {
        assertNull(Config.fromEnv(base).scorecardDb)
        // The connection alone does not turn them on: the chart passes it before ids are enabled.
        assertNull(Config.fromEnv(base + scorecard).scorecardDb)
        assertNull(Config.fromEnv(base + scorecard + ("USER_IDS_ENABLED" to "false")).scorecardDb)
    }

    @Test
    fun idsOnReadTheScorecardConnection() {
        val db = Config.fromEnv(base + scorecard + ("USER_IDS_ENABLED" to "true")).scorecardDb!!
        assertEquals("jdbc:postgresql://scorecard-postgres:5432/scorecard", db.jdbcUrl)
        assertEquals("postgres" to "pw", db.user to db.password)
    }

    @Test
    fun idsOnWithoutAConnectionRefusesToStart() {
        for (missing in scorecard.keys) {
            val error = assertFailsWith<IllegalStateException> { Config.fromEnv(base + (scorecard - missing) + ("USER_IDS_ENABLED" to "true")) }
            assertEquals("$missing is not set", error.message)
        }
    }
}
