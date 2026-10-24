package app.scrollhalt.stats

import app.scrollhalt.detect.BlockCategory
import app.scrollhalt.detect.Classification
import app.scrollhalt.detect.PolicyEvent
import app.scrollhalt.detect.Screen
import app.scrollhalt.protect.ProtectionMode
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsJsonTest {
    private val clock = TestClock()

    @Test
    fun `statistics survive a round trip`() {
        val start = clock.now()
        val state = StatsState()
            .serviceStarted(start)
            .instagramInFront(start)
            .viewerSeen(clock.now(), Classification(Screen.REELS_TAB, tabBarVisible = true), ProtectionMode.PAUSED)
            .policyEvents(clock.seconds(3), listOf(PolicyEvent.Attempt(BlockCategory.EXPLORE_REEL)))
            .pauseStarted(clock.now(), PauseSpan(clock.wall, clock.wall + 5 * MINUTE))
            .countAll(clock.minutes(2))
        assertEquals(state, StatsJson.decode(StatsJson.encode(state)))
    }

    @Test
    fun `the schema version is written`() {
        assertTrue(StatsJson.encode(StatsState()).contains("\"schemaVersion\":1"))
    }

    @Test
    fun `fields from a newer version are ignored`() {
        val text = """{"schemaVersion":2,"generation":7,"future":{"x":1},"days":{"2026-10-24":{"pausesStarted":3,"newMetric":9}}}"""
        val state = StatsJson.decode(text)
        assertEquals(StatsState.SCHEMA_VERSION, state.schemaVersion)
        assertEquals(7L, state.generation)
        assertEquals(3, state.day(OCT_24).pausesStarted)
    }

    @Test(expected = SerializationException::class)
    fun `text that is not statistics is rejected`() {
        StatsJson.decode("not json")
    }
}
