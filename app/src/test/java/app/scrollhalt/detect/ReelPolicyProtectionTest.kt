package app.scrollhalt.detect

import app.scrollhalt.detect.PolicyInput.LeftApp
import app.scrollhalt.detect.PolicyInput.PagerScrolled
import app.scrollhalt.detect.PolicyInput.ProtectionPaused
import app.scrollhalt.detect.PolicyInput.ProtectionResumed
import app.scrollhalt.detect.PolicyInput.ScreenSeen
import app.scrollhalt.detect.PolicyInput.ViewClicked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pause and resume: what happens to Reels on screen when protection switches on or off. */
class ReelPolicyProtectionTest {
    private val reelsTab = Classification(Screen.REELS_TAB, tabBarVisible = true, selectedTab = "clips_tab")
    private val shared = Classification(Screen.SINGLE_REEL, sharedInDm = true)
    private val exploreReel = Classification(Screen.SINGLE_REEL, selectedTab = "search_tab")
    private val feedReel = Classification(Screen.SINGLE_REEL)
    private val grid = Classification(Screen.EXPLORE_GRID, tabBarVisible = true, selectedTab = "search_tab")
    private val feed = Classification(Screen.OTHER, tabBarVisible = true, selectedTab = "feed_tab")
    private val dm = Classification(Screen.DM)
    private val unknown = Classification(Screen.UNKNOWN)

    private val paused = PolicySettings(blockingEnabled = false)
    private val protecting = PolicySettings()

    private class Run(var settings: PolicySettings) {
        var policy: ReelPolicy = ReelPolicy()

        fun feed(input: PolicyInput): PolicyResult = policy.reduce(input, settings).also { policy = it.policy }
        fun seen(at: Long, classification: Classification): PolicyResult = feed(ScreenSeen(at, classification))
        fun scroll(at: Long, index: Int, pager: String = "w1:pager"): PolicyResult = feed(PagerScrolled(at, pager, index))
        fun click(at: Long, name: String): PolicyResult = feed(ViewClicked(at, ig(name)))

        fun resume(at: Long): PolicyResult {
            settings = settings.copy(blockingEnabled = true)
            return feed(ProtectionResumed(at))
        }

        fun pause(at: Long): PolicyResult {
            settings = settings.copy(blockingEnabled = false)
            return feed(ProtectionPaused(at))
        }
    }

    @Test
    fun `expiry while an explore reel plays lets it finish and the next swipe closes it`() {
        val run = Run(paused)
        run.seen(0, grid)
        assertEquals(Decision.NONE, run.seen(500, exploreReel).decision)
        run.scroll(600, 3)
        assertEquals(Decision.NONE, run.scroll(20_000, 4).decision)
        val resumed = run.resume(60_000)
        assertEquals(Decision.NONE, resumed.decision)
        assertTrue(resumed.reason.contains("finishes"))
        assertEquals(Decision.NONE, run.seen(60_100, exploreReel).decision)
        assertEquals(Decision.NONE, run.scroll(61_000, 4).decision)
        assertEquals(Decision.NONE, run.seen(62_000, exploreReel).decision)
        assertEquals(Decision.BACK, run.scroll(63_000, 5).decision)
    }

    @Test
    fun `expiry while viewing keeps the reel through unknown frames`() {
        val run = Run(paused)
        run.seen(0, grid)
        run.seen(500, exploreReel)
        run.seen(900, unknown)
        run.resume(1_000)
        assertEquals(Decision.NONE, run.seen(1_100, unknown).decision)
        assertEquals(Decision.NONE, run.seen(1_200, exploreReel).decision)
    }

    @Test
    fun `expiry finishes a dm reel even with shared reels off`() {
        val run = Run(paused.copy(allowSingleReel = false))
        run.seen(0, dm)
        run.seen(500, shared)
        run.scroll(600, 2)
        run.resume(30_000)
        assertEquals(Decision.NONE, run.seen(30_100, shared).decision)
        assertEquals(Decision.NONE, run.seen(31_000, shared).decision)
        assertEquals(Decision.BACK, run.scroll(33_000, 3).decision)
    }

    @Test
    fun `a fast swipe right after expiry on a single reel is not positioning`() {
        // Codex P2: the resumed session got no index, so a swipe within 300 ms was adopted.
        val run = Run(paused)
        run.seen(0, dm)
        run.seen(500, shared)
        run.scroll(600, 2)
        run.resume(20_000)
        assertEquals(Decision.BACK, run.scroll(20_100, 3).decision)
    }

    @Test
    fun `a swipe right after expiry closes even without a known index`() {
        val run = Run(paused)
        run.seen(0, feed)
        run.seen(500, feedReel)
        run.resume(20_000)
        assertEquals(Decision.BACK, run.scroll(20_100, 1).decision)
    }

    @Test
    fun `expiry on the reels tab lets the current reel finish`() {
        val run = Run(paused)
        assertEquals(Decision.NONE, run.seen(0, reelsTab).decision)
        run.scroll(100, 3)
        run.seen(5_000, reelsTab)
        assertEquals(Decision.NONE, run.resume(10_000).decision)
        assertEquals(Decision.NONE, run.seen(10_100, reelsTab).decision)
        assertEquals(Decision.NONE, run.scroll(11_000, 3).decision)
        assertEquals(Decision.NONE, run.seen(25_000, reelsTab).decision)
    }

    @Test
    fun `the first swipe on the reels tab after expiry goes home`() {
        val run = Run(paused)
        run.seen(0, reelsTab)
        run.scroll(100, 3)
        run.resume(10_000)
        run.seen(10_100, reelsTab)
        assertEquals(Decision.GO_HOME, run.scroll(15_000, 4).decision)
        assertEquals(Decision.NONE, run.seen(15_400, feed).decision)
        assertNull(run.policy.session)
    }

    @Test
    fun `a fast swipe on the reels tab right after expiry goes home`() {
        val run = Run(paused)
        run.seen(0, reelsTab)
        run.scroll(100, 3)
        run.resume(10_000)
        assertEquals(Decision.GO_HOME, run.scroll(10_100, 4).decision)
    }

    @Test
    fun `a swipe held back on the reels tab is enforced by the next scan`() {
        val run = Run(paused)
        run.seen(0, reelsTab)
        run.scroll(100, 3)
        run.resume(10_000)
        run.policy = run.policy.copy(lastActionAt = 9_900)
        assertEquals(Decision.NONE, run.scroll(10_100, 4).decision)
        assertEquals(Decision.GO_HOME, run.seen(10_600, reelsTab).decision)
    }

    @Test
    fun `after expiry on the reels tab a new entry is blocked normally`() {
        val run = Run(paused)
        run.seen(0, reelsTab)
        run.resume(10_000)
        assertEquals(Decision.NONE, run.seen(10_100, feed).decision)
        assertEquals(Decision.GO_HOME, run.click(12_000, "clips_tab").decision)
        assertEquals(Decision.NONE, run.seen(12_300, feed).decision)
        assertEquals(Decision.GO_HOME, run.seen(14_000, reelsTab).decision)
    }

    @Test
    fun `the resume path never sends to the launcher`() {
        val run = Run(protecting)
        val decisions = mutableListOf<Decision>()
        var at = 0L
        repeat(5) { i ->
            run.pause(at)
            decisions += run.seen(at + 100, reelsTab).decision
            decisions += run.scroll(at + 200, i).decision
            decisions += run.resume(at + 60_000).decision
            decisions += run.seen(at + 60_100, reelsTab).decision
            decisions += run.scroll(at + 61_000, i + 1).decision
            decisions += run.seen(at + 61_300, feed).decision
            at += 100_000
        }
        assertEquals(5, decisions.count { it == Decision.GO_HOME })
        assertTrue(decisions.none { it == Decision.GLOBAL_HOME || it == Decision.BACK })
    }

    @Test
    fun `expiry with no reel on screen keeps explore blocking`() {
        val run = Run(paused)
        run.seen(0, grid)
        run.resume(5_000)
        assertEquals(Decision.BACK, run.seen(5_500, exploreReel).decision)
    }

    @Test
    fun `resuming after leaving instagram finishes nothing`() {
        val run = Run(paused)
        run.seen(0, grid)
        run.seen(500, exploreReel)
        run.feed(LeftApp(1_000))
        run.resume(2_000)
        assertEquals(Decision.BACK, run.seen(9_000, exploreReel).decision)
    }

    @Test
    fun `the finished reel is a one off`() {
        val run = Run(paused)
        run.seen(0, grid)
        run.seen(500, exploreReel)
        run.resume(1_000)
        run.seen(1_100, exploreReel)
        run.seen(2_000, grid)
        assertEquals(Decision.BACK, run.seen(3_000, exploreReel).decision)
    }

    @Test
    fun `starting a pause clears pending escalation`() {
        val run = Run(protecting)
        assertEquals(Decision.GO_HOME, run.seen(0, reelsTab).decision)
        assertEquals(Decision.GO_HOME, run.seen(700, reelsTab).decision)
        run.pause(800)
        assertNull(run.policy.pending)
        assertTrue(run.policy.globalHomeFailures.isEmpty())
        // During the pause the Reels tab is tapped (blocking off keeps no new pending action).
        assertEquals(Decision.NONE, run.feed(ViewClicked(5_000, ig("clips_tab"))).decision)
        run.resume(60_000)
        // The Reels tab reel on screen finishes; the first swipe starts a fresh ladder.
        assertEquals(Decision.NONE, run.seen(60_100, reelsTab).decision)
        val first = run.scroll(61_000, 5)
        assertEquals(Decision.GO_HOME, first.decision)
        assertEquals(0, first.policy.pending?.streak)
    }

    @Test
    fun `pause and resume transitions never send to the launcher`() {
        val run = Run(protecting)
        val decisions = mutableListOf<Decision>()
        var at = 0L
        repeat(5) {
            decisions += run.seen(at, reelsTab).decision
            decisions += run.seen(at + 700, reelsTab).decision
            decisions += run.pause(at + 800).decision
            decisions += run.seen(at + 900, reelsTab).decision
            decisions += run.resume(at + 2_000).decision
            at += 3_000
        }
        assertTrue(decisions.none { it == Decision.GLOBAL_HOME })
    }

    @Test
    fun `pausing ends a reel session and clears the detection pause`() {
        val run = Run(protecting)
        run.seen(0, feed)
        run.seen(500, shared)
        run.policy = run.policy.copy(pausedUntil = 9_000)
        run.pause(1_000)
        assertNull(run.policy.session)
        assertNull(run.policy.pausedUntil)
    }

    @Test
    fun `dm screens stay untouched across a pause`() {
        val run = Run(protecting)
        run.pause(0)
        (1L..5L).forEach { assertEquals(Decision.NONE, run.seen(it * 100, dm).decision) }
        run.resume(1_000)
        (11L..15L).forEach { assertEquals(Decision.NONE, run.seen(it * 100, dm).decision) }
    }

    @Test
    fun `allowed shared reel still plays once after a pause`() {
        val run = Run(protecting)
        run.pause(0)
        run.resume(1_000)
        run.seen(2_000, dm)
        assertEquals(Decision.NONE, run.seen(2_500, shared).decision)
        run.scroll(2_600, 4)
        assertEquals(Decision.BACK, run.scroll(5_000, 5).decision)
    }
}
