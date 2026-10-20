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
    fun `cooldown suppresses a second action within 700 ms`() {
        val run = Run()
        assertEquals(Decision.GO_HOME, run.click(0, "clips_tab").decision)
        val second = run.seen(300, reelsTab)
        assertEquals(Decision.NONE, second.decision)
        assertTrue(second.reason.contains("cooldown"))
        assertEquals(Decision.GO_HOME, run.seen(800, reelsTab).decision)
    }

    @Test
    fun `loop guard pauses after three actions in five seconds`() {
        val run = Run()
        assertEquals(Decision.GO_HOME, run.seen(0, reelsTab).decision)
        assertEquals(Decision.GO_HOME, run.seen(1_000, reelsTab).decision)
        assertEquals(Decision.GO_HOME, run.seen(2_000, reelsTab).decision)
        val tripped = run.seen(3_000, reelsTab)
        assertEquals(Decision.NONE, tripped.decision)
        assertTrue(tripped.reason.contains("loop guard"))
        assertEquals(Decision.NONE, run.seen(12_000, reelsTab).decision)
        assertEquals(Decision.GO_HOME, run.seen(13_100, reelsTab).decision)
    }

    @Test
    fun `actions spread over more than five seconds do not trip the guard`() {
        val run = Run()
        listOf(0L, 3_000L, 6_000L, 9_000L, 12_000L).forEach { at ->
            assertEquals(Decision.GO_HOME, run.seen(at, reelsTab).decision)
        }
    }
}
