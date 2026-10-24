package app.scrollhalt.stats

import app.scrollhalt.detect.BlockCategory
import app.scrollhalt.detect.Classification
import app.scrollhalt.detect.Decision
import app.scrollhalt.detect.PolicyEvent
import app.scrollhalt.detect.PolicyEvent.Attempt
import app.scrollhalt.detect.PolicyEvent.Confirmed
import app.scrollhalt.detect.PolicyInput
import app.scrollhalt.detect.PolicySettings
import app.scrollhalt.detect.ReelPolicy
import app.scrollhalt.detect.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BlockedAttemptsTest {
    private val clock = TestClock()

    private fun StatsState.events(vararg events: PolicyEvent): StatsState = policyEvents(clock.now(), events.toList())

    @Test
    fun `an attempt is detected, acted on and confirmed once`() {
        val stats = StatsState()
            .events(Attempt(BlockCategory.EXPLORE_REEL))
            .actionTaken(clock.advance(10), BlockCategory.EXPLORE_REEL)
            .actionTaken(clock.advance(700), BlockCategory.EXPLORE_REEL)
            .events(Confirmed(BlockCategory.EXPLORE_REEL))
            .events(Confirmed(BlockCategory.EXPLORE_REEL))
        assertEquals(Outcome(detected = 1, actionTaken = 1, confirmed = 1), stats.day(OCT_24).blocked.exploreReels)
        assertEquals(1, stats.day(OCT_24).blocked.total)
    }

    @Test
    fun `an action or confirmation without an attempt counts nothing`() {
        val none = StatsState()
        assertSame(none, none.actionTaken(clock.now(), BlockCategory.NEXT_REEL))
        assertSame(none, none.events(Confirmed(BlockCategory.NEXT_REEL)))
    }

    @Test
    fun `a failed action leaves the attempt detected only`() {
        val stats = StatsState().events(Attempt(BlockCategory.NEXT_REEL))
        assertEquals(Outcome(detected = 1), stats.day(OCT_24).blocked.nextReel)
    }

    @Test
    fun `cover taps within a second are one attempt`() {
        val stats = StatsState()
            .coverTapped(clock.now())
            .coverTapped(clock.advance(400))
            .coverTapped(clock.advance(900))
            .coverTapped(clock.advance(1_200))
        assertEquals(Outcome(detected = 2, actionTaken = 2, confirmed = 2), stats.day(OCT_24).blocked.reelsTab)
    }

    @Test
    fun `reels tab attempts from the policy merge with taps too`() {
        val stats = StatsState()
            .events(Attempt(BlockCategory.REELS_TAB))
            .also { clock.advance(700) }
            .events(Attempt(BlockCategory.REELS_TAB))
            .coverTapped(clock.advance(5_000))
        assertEquals(2, stats.day(OCT_24).blocked.reelsTab.detected)
    }

    @Test
    fun `explore attempts never merge`() {
        val stats = StatsState()
            .events(Attempt(BlockCategory.EXPLORE_REEL))
            .also { clock.advance(300) }
            .events(Attempt(BlockCategory.EXPLORE_REEL))
        assertEquals(2, stats.day(OCT_24).blocked.exploreReels.detected)
    }

    @Test
    fun `launcher escalations are counted apart`() {
        val stats = StatsState().events(Attempt(BlockCategory.REELS_TAB), PolicyEvent.SentToLauncher)
        assertEquals(1, stats.day(OCT_24).blocked.sentToLauncher)
        assertEquals(1, stats.day(OCT_24).blocked.total)
    }

    @Test
    fun `retries of the policy ladder are not attempts`() {
        // The Reels tab stays on screen: GO_HOME, GO_HOME, then the launcher, which works.
        val reelsTab = Classification(Screen.REELS_TAB, tabBarVisible = true, selectedTab = "clips_tab")
        var policy = ReelPolicy()
        var stats = StatsState()
        listOf(0L, 300L, 700L, 1_400L).forEach { at ->
            val result = policy.reduce(PolicyInput.ScreenSeen(at, reelsTab), PolicySettings())
            policy = result.policy
            stats = stats.policyEvents(clock.now(), result.events)
            if (result.decision != Decision.NONE) {
                stats = stats.actionTaken(clock.now(), requireNotNull(result.policy.pending?.category))
            }
        }
        stats = stats.policyEvents(clock.now(), policy.reduce(PolicyInput.LeftApp(1_500), PolicySettings()).events)
        assertEquals(Outcome(detected = 1, actionTaken = 1, confirmed = 1), stats.day(OCT_24).blocked.reelsTab)
        assertEquals(1, stats.day(OCT_24).blocked.sentToLauncher)
    }
}
