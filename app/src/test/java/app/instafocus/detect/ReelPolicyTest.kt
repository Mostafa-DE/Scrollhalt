package app.instafocus.detect

import app.instafocus.detect.PolicyInput.PagerScrolled
import app.instafocus.detect.PolicyInput.ScreenSeen
import app.instafocus.detect.PolicyInput.ViewClicked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelPolicyTest {
    private val reelsTab = Classification(Screen.REELS_TAB, selectedTab = "clips_tab")
    private val shared = Classification(Screen.SINGLE_REEL, sharedInDm = true)
    private val single = Classification(Screen.SINGLE_REEL)
    private val dm = Classification(Screen.DM)
    private val feed = Classification(Screen.OTHER, selectedTab = "feed_tab")
    private val unknown = Classification(Screen.UNKNOWN)

    /** Feeds inputs one by one and keeps the latest policy. */
    private class Run(val settings: PolicySettings = PolicySettings()) {
        var policy: ReelPolicy = ReelPolicy()

        fun feed(input: PolicyInput): PolicyResult = policy.reduce(input, settings).also { policy = it.policy }
        fun seen(at: Long, classification: Classification): PolicyResult = feed(ScreenSeen(at, classification))
        fun click(at: Long, name: String): PolicyResult = feed(ViewClicked(at, ig(name)))
        fun scroll(at: Long, index: Int, pager: String = "w1:pager"): PolicyResult = feed(PagerScrolled(at, pager, index))
    }

    @Test
    fun `reels tab goes home`() {
        assertEquals(Decision.GO_HOME, Run().seen(0, reelsTab).decision)
    }

    @Test
    fun `reels tab click goes home`() {
        val run = Run()
        run.seen(0, feed)
        assertEquals(Decision.GO_HOME, run.click(100, "clips_tab").decision)
    }

    @Test
    fun `clicks on other views are ignored`() {
        val run = Run()
        assertEquals(Decision.NONE, run.click(100, "feed_tab").decision)
        assertEquals(Decision.NONE, run.feed(ViewClicked(150, null)).decision)
    }

    @Test
    fun `shared reel is allowed then a swipe snaps back`() {
        val run = Run()
        run.seen(0, dm)
        assertEquals(Decision.NONE, run.seen(500, shared).decision)
        run.scroll(600, 4)
        assertEquals(Decision.NONE, run.scroll(3_000, 4).decision)
        val swipe = run.scroll(3_200, 5)
        assertEquals(Decision.SNAP_BACK, swipe.decision)
        assertEquals(SnapDirection.BACKWARD, swipe.snapDirection)
    }

    @Test
    fun `swipe upwards snaps forward`() {
        val run = Run()
        run.seen(0, shared)
        run.scroll(700, 4)
        val swipe = run.scroll(3_000, 3)
        assertEquals(Decision.SNAP_BACK, swipe.decision)
        assertEquals(SnapDirection.FORWARD, swipe.snapDirection)
    }

    @Test
    fun `scroll just before the viewer is classified sets the start index`() {
        val run = Run()
        run.scroll(450, 2)
        run.seen(500, single)
        assertEquals(Decision.NONE, run.scroll(3_000, 2).decision)
        assertEquals(Decision.SNAP_BACK, run.scroll(3_500, 3).decision)
    }

    @Test
    fun `last index in the settle window wins`() {
        val run = Run()
        run.seen(500, single)
        run.scroll(600, 0)
        run.scroll(900, 7)
        assertEquals(Decision.NONE, run.scroll(3_000, 7).decision)
    }

    @Test
    fun `start index adopted late when none was seen`() {
        val run = Run()
        run.seen(500, single)
        assertEquals(Decision.NONE, run.scroll(5_000, 3).decision)
        assertEquals(Decision.SNAP_BACK, run.scroll(5_300, 4).decision)
    }

    @Test
    fun `explore reel tracks the pager that scrolls`() {
        // Two pagers exist; only the Explore one in front scrolls. A scroll from another
        // pager restarts tracking instead of snapping.
        val run = Run()
        run.seen(0, single)
        run.scroll(300, 0, pager = "w1:explore")
        assertEquals(Decision.NONE, run.scroll(3_000, 9, pager = "w1:reelsTab").decision)
        assertEquals(Decision.NONE, run.scroll(3_200, 9, pager = "w1:reelsTab").decision)
        assertEquals(Decision.SNAP_BACK, run.scroll(4_000, 10, pager = "w1:reelsTab").decision)
    }

    @Test
    fun `negative scroll index is ignored`() {
        val run = Run()
        run.seen(500, single)
        run.scroll(600, 1)
        assertEquals(Decision.NONE, run.scroll(3_000, -1).decision)
    }

    @Test
    fun `pager scroll outside a single reel does nothing`() {
        val run = Run()
        run.seen(0, feed)
        assertEquals(Decision.NONE, run.scroll(100, 3).decision)
    }

    @Test
    fun `single reel stays allowed while the session lasts`() {
        val run = Run()
        run.seen(500, single)
        val again = run.seen(9_000, single)
        assertEquals(Decision.NONE, again.decision)
        assertTrue(again.reason.contains("Explore/feed/profile"))
    }

    @Test
    fun `unknown screens do not change state`() {
        val run = Run()
        run.seen(500, shared)
        run.scroll(600, 2)
        val before = run.policy
        assertEquals(Decision.NONE, run.seen(2_000, unknown).decision)
        assertEquals(before, run.policy)
        assertEquals(Decision.SNAP_BACK, run.scroll(3_000, 3).decision)
    }

    @Test
    fun `leaving the reel ends the session`() {
        val run = Run()
        run.seen(500, shared)
        run.scroll(600, 2)
        run.seen(2_000, dm)
        assertEquals(Decision.NONE, run.scroll(3_000, 5).decision)
    }

    @Test
    fun `single reels off presses back on a shared reel`() {
        val run = Run(PolicySettings(allowSingleReel = false))
        run.seen(0, dm)
        assertEquals(Decision.BACK, run.seen(500, shared).decision)
    }

    @Test
    fun `single reels off retries back after cooldown while the shared reel stays`() {
        val run = Run(PolicySettings(allowSingleReel = false))
        assertEquals(Decision.BACK, run.seen(500, shared).decision)
        assertEquals(Decision.NONE, run.seen(700, shared).decision)
        assertEquals(Decision.BACK, run.seen(1_300, shared).decision)
    }

    @Test
    fun `single reels off without a dm signal waits for a swipe then goes back`() {
        val run = Run(PolicySettings(allowSingleReel = false))
        assertEquals(Decision.NONE, run.seen(500, single).decision)
        run.scroll(600, 0)
        assertEquals(Decision.BACK, run.scroll(3_000, 1).decision)
    }

    @Test
    fun `dm screens are never acted on`() {
        listOf(PolicySettings(), PolicySettings(allowSingleReel = false)).forEach { settings ->
            val run = Run(settings)
            (0L..20L).forEach { i -> assertEquals(Decision.NONE, run.seen(i * 50, dm).decision) }
        }
    }

    @Test
    fun `blocking disabled never acts`() {
        val run = Run(PolicySettings(blockingEnabled = false))
        assertEquals(Decision.NONE, run.seen(0, reelsTab).decision)
        assertEquals(Decision.NONE, run.click(100, "clips_tab").decision)
        assertEquals(Decision.NONE, run.seen(1_000, shared).decision)
        run.scroll(1_100, 0)
        assertEquals(Decision.NONE, run.scroll(4_000, 1).decision)
    }

    @Test
    fun `cooldown holds a second action back and asks for a retry`() {
        val run = Run()
        val first = run.click(0, "clips_tab")
        assertEquals(Decision.GO_HOME, first.decision)
        assertEquals(700L, first.retryAtMillis)
        val second = run.seen(300, reelsTab)
        assertEquals(Decision.NONE, second.decision)
        assertTrue(second.reason.contains("cooldown"))
        assertEquals(700L, second.retryAtMillis)
        assertEquals(Decision.GO_HOME, run.seen(800, reelsTab).decision)
    }

    @Test
    fun `fast re-entry during the cooldown is blocked on the retry`() {
        val run = Run()
        assertEquals(Decision.GO_HOME, run.seen(0, reelsTab).decision)
        run.seen(200, feed)
        val early = run.seen(400, reelsTab)
        assertEquals(Decision.NONE, early.decision)
        assertEquals(700L, early.retryAtMillis)
        val retried = run.seen(700, reelsTab)
        assertEquals(Decision.GO_HOME, retried.decision)
        assertTrue(!retried.reason.contains("ineffective"))
    }

    @Test
    fun `re-entering the reels tab ten times is blocked every time`() {
        val run = Run()
        (0 until 10).forEach { i ->
            val at = i * 1_000L
            assertEquals("attempt $i", Decision.GO_HOME, run.seen(at, reelsTab).decision)
            assertEquals(Decision.NONE, run.seen(at + 400, feed).decision)
        }
    }

    @Test
    fun `rapid reels taps during the cooldown never escalate`() {
        // Device test: 10 taps 200 ms apart. Each tap lands after our Home tap, before any
        // scan sees the feed; they are new attempts, so every retry goes Home again.
        val run = Run()
        val decisions = mutableListOf<Decision>()
        decisions += run.click(0, "clips_tab").decision
        (1..10).forEach { i ->
            val at = i * 200L
            decisions += run.click(at, "clips_tab").decision
            decisions += run.seen(at + 100, reelsTab).decision
        }
        decisions += run.seen(2_800, reelsTab).decision
        assertTrue(decisions.none { it == Decision.GLOBAL_HOME || it == Decision.BACK })
        assertTrue(decisions.count { it == Decision.GO_HOME } >= 3)
        assertEquals(Decision.NONE, run.seen(3_000, feed).decision)
    }

    @Test
    fun `reels tab click after a successful go home starts fresh`() {
        val run = Run()
        run.seen(0, reelsTab)
        run.seen(300, feed)
        val click = run.click(1_000, "clips_tab")
        assertEquals(Decision.GO_HOME, click.decision)
        assertEquals(0, click.policy.pending?.streak)
    }

    @Test
    fun `effective actions never pause, however many`() {
        val run = Run()
        (0 until 50).forEach { i ->
            val at = i * 800L
            assertEquals("attempt $i", Decision.GO_HOME, run.seen(at, reelsTab).decision)
            run.seen(at + 150, feed)
        }
        assertEquals(null, run.policy.pausedUntil)
    }

    @Test
    fun `go home ineffective twice escalates to the launcher`() {
        val run = Run()
        assertEquals(Decision.GO_HOME, run.seen(0, reelsTab).decision)
        assertEquals(Decision.NONE, run.seen(300, reelsTab).decision)
        val second = run.seen(700, reelsTab)
        assertEquals(Decision.GO_HOME, second.decision)
        assertTrue(second.reason.contains("ineffective"))
        assertEquals(Decision.GLOBAL_HOME, run.seen(1_400, reelsTab).decision)
    }

    @Test
    fun `launcher failing three times in ten seconds pauses and says so`() {
        val run = Run()
        run.seen(0, reelsTab)
        run.seen(700, reelsTab)
        assertEquals(Decision.GLOBAL_HOME, run.seen(1_400, reelsTab).decision)
        assertEquals(Decision.GLOBAL_HOME, run.seen(2_100, reelsTab).decision)
        assertEquals(Decision.GLOBAL_HOME, run.seen(2_800, reelsTab).decision)
        val broken = run.seen(3_500, reelsTab)
        assertEquals(Decision.NONE, broken.decision)
        assertTrue(broken.reason.contains("detection may be broken"))
        assertEquals(13_500L, broken.retryAtMillis)
        assertEquals(Decision.NONE, run.seen(10_000, reelsTab).decision)
        val resumed = run.seen(13_500, reelsTab)
        assertEquals(Decision.GO_HOME, resumed.decision)
    }

    @Test
    fun `leaving instagram after the launcher counts as success`() {
        val run = Run()
        run.seen(0, reelsTab)
        run.seen(700, reelsTab)
        assertEquals(Decision.GLOBAL_HOME, run.seen(1_400, reelsTab).decision)
        run.feed(PolicyInput.LeftApp(2_100))
        val reopened = run.seen(5_000, reelsTab)
        assertEquals(Decision.GO_HOME, reopened.decision)
        assertEquals(0, reopened.policy.pending?.streak)
    }

    @Test
    fun `snap back that does not take escalates to back then the launcher`() {
        val run = Run()
        run.seen(0, shared)
        run.scroll(300, 4)
        assertEquals(Decision.SNAP_BACK, run.scroll(3_000, 5).decision)
        assertEquals(Decision.SNAP_BACK, run.seen(3_700, shared).decision)
        assertEquals(Decision.BACK, run.seen(4_400, shared).decision)
        assertEquals(Decision.BACK, run.seen(5_100, shared).decision)
        assertEquals(Decision.GLOBAL_HOME, run.seen(5_800, shared).decision)
    }

    @Test
    fun `snap back that takes resets the streak`() {
        val run = Run()
        run.seen(0, shared)
        run.scroll(300, 4)
        assertEquals(Decision.SNAP_BACK, run.scroll(3_000, 5).decision)
        run.scroll(3_300, 4)
        assertEquals(Decision.NONE, run.seen(3_800, shared).decision)
        val again = run.scroll(6_000, 5)
        assertEquals(Decision.SNAP_BACK, again.decision)
        assertEquals(0, again.policy.pending?.streak)
    }

    @Test
    fun `snap back seen working by a scan clears the pending action`() {
        val run = Run()
        run.seen(0, shared)
        run.scroll(300, 4)
        run.scroll(3_000, 5)
        run.scroll(3_200, 4)
        val check = run.seen(3_800, shared)
        assertEquals(Decision.NONE, check.decision)
        assertEquals(null, check.policy.pending)
    }

    @Test
    fun `single reels off back on a shared reel escalates to the launcher`() {
        val run = Run(PolicySettings(allowSingleReel = false))
        assertEquals(Decision.BACK, run.seen(0, shared).decision)
        assertEquals(Decision.BACK, run.seen(700, shared).decision)
        assertEquals(Decision.GLOBAL_HOME, run.seen(1_400, shared).decision)
    }

    @Test
    fun `unknown screen keeps the retry for a pending action`() {
        val run = Run()
        run.seen(0, reelsTab)
        assertEquals(700L, run.seen(200, unknown).retryAtMillis)
    }

    @Test
    fun `no retry is requested once the cooldown is over`() {
        // A retry time in the past would make the service rescan in a busy loop.
        val run = Run()
        run.seen(0, reelsTab)
        assertEquals(null, run.seen(900, unknown).retryAtMillis)
        assertEquals(null, run.click(950, "feed_tab").retryAtMillis)
    }

    @Test
    fun `go home landing on a single reel counts as done`() {
        val run = Run()
        run.seen(0, reelsTab)
        val result = run.seen(800, single)
        assertEquals(Decision.NONE, result.decision)
        assertEquals(null, result.policy.pending)
    }
}
