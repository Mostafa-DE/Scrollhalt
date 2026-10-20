package app.instafocus.detect

import app.instafocus.detect.PolicyInput.PagerScrolled
import app.instafocus.detect.PolicyInput.ScreenSeen
import app.instafocus.detect.PolicyInput.ViewClicked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelPolicyTest {
    private val viewer = Classification(Screen.REEL_VIEWER, reelsTabSelected = false)
    private val reelsTab = Classification(Screen.REEL_VIEWER, reelsTabSelected = true)
    private val dm = Classification(Screen.DM, reelsTabSelected = false)
    private val explore = Classification(Screen.EXPLORE, reelsTabSelected = false)
    private val feed = Classification(Screen.OTHER, reelsTabSelected = false)
    private val unknown = Classification(Screen.UNKNOWN, reelsTabSelected = false)

    /** Feeds inputs one by one and keeps every result. */
    private class Run(var settings: PolicySettings = PolicySettings()) {
        var policy: ReelPolicy = ReelPolicy()
        val results: MutableList<PolicyResult> = mutableListOf()

        fun feed(input: PolicyInput): PolicyResult {
            val result = policy.reduce(input, settings)
            policy = result.policy
            results += result
            return result
        }

        fun seen(at: Long, classification: Classification): PolicyResult = feed(ScreenSeen(at, classification))
        fun click(at: Long, name: String): PolicyResult = feed(ViewClicked(at, ig(name)))
        fun scroll(at: Long, index: Int): PolicyResult = feed(PagerScrolled(at, index))
    }

    @Test
    fun `reels tab click then viewer goes home`() {
        val run = Run()
        run.seen(0, feed)
        run.click(1_000, "clips_tab")
        assertEquals(Decision.GO_HOME, run.seen(1_300, viewer).decision)
    }

    @Test
    fun `old reels tab click does not count`() {
        val run = Run()
        run.seen(0, feed)
        run.click(1_000, "clips_tab")
        assertEquals(Decision.NONE, run.seen(3_100, viewer).decision)
    }

    @Test
    fun `reels tab selected goes home even with unknown origin`() {
        assertEquals(Decision.GO_HOME, Run().seen(0, reelsTab).decision)
    }

    @Test
    fun `reels tab selected wins over a dm origin`() {
        val run = Run()
        run.seen(0, dm)
        assertEquals(Decision.GO_HOME, run.seen(500, reelsTab).decision)
    }

    @Test
    fun `dm then viewer allows one reel and snaps back on swipe`() {
        val run = Run()
        run.seen(0, dm)
        assertEquals(Decision.NONE, run.seen(500, viewer).decision)
        run.scroll(600, 4)
        assertEquals(Decision.NONE, run.scroll(3_000, 4).decision)
        val swipe = run.scroll(3_200, 5)
        assertEquals(Decision.SNAP_BACK, swipe.decision)
        assertEquals(SnapDirection.BACKWARD, swipe.snapDirection)
    }

    @Test
    fun `swipe upwards snaps forward`() {
        val run = Run()
        run.seen(0, dm)
        run.seen(500, viewer)
        run.scroll(700, 4)
        val swipe = run.scroll(3_000, 3)
        assertEquals(Decision.SNAP_BACK, swipe.decision)
        assertEquals(SnapDirection.FORWARD, swipe.snapDirection)
    }

    @Test
    fun `scroll just before the viewer is classified sets the start index`() {
        val run = Run()
        run.seen(0, dm)
        run.scroll(450, 2)
        run.seen(500, viewer)
        assertEquals(Decision.NONE, run.scroll(3_000, 2).decision)
        assertEquals(Decision.SNAP_BACK, run.scroll(3_500, 3).decision)
    }

    @Test
    fun `last index in the settle window wins`() {
        val run = Run()
        run.seen(0, dm)
        run.seen(500, viewer)
        run.scroll(600, 0)
        run.scroll(900, 7)
        assertEquals(Decision.NONE, run.scroll(3_000, 7).decision)
    }

    @Test
    fun `start index adopted late when none was seen`() {
        val run = Run()
        run.seen(0, dm)
        run.seen(500, viewer)
        assertEquals(Decision.NONE, run.scroll(5_000, 3).decision)
        assertEquals(Decision.SNAP_BACK, run.scroll(5_300, 4).decision)
    }

    @Test
    fun `negative scroll index is ignored`() {
        val run = Run()
        run.seen(0, dm)
        run.seen(500, viewer)
        run.scroll(600, 1)
        assertEquals(Decision.NONE, run.scroll(3_000, -1).decision)
    }

    @Test
    fun `pager scroll outside a viewer session does nothing`() {
        val run = Run()
        run.seen(0, feed)
        assertEquals(Decision.NONE, run.scroll(100, 3).decision)
    }

    @Test
    fun `explore then viewer allows one reel and snaps back on swipe`() {
        val run = Run()
        run.seen(0, explore)
        assertEquals(Decision.NONE, run.seen(500, viewer).decision)
        run.scroll(600, 0)
        assertEquals(Decision.SNAP_BACK, run.scroll(4_000, 1).decision)
    }

    @Test
    fun `feed then viewer allows one reel`() {
        val run = Run()
        run.seen(0, feed)
        val result = run.seen(500, viewer)
        assertEquals(Decision.NONE, result.decision)
        assertTrue(result.reason.contains("OTHER"))
    }

    @Test
    fun `dm overlay on viewer counts as dm origin`() {
        val run = Run()
        val result = run.seen(0, viewer.copy(dmOverlay = true))
        assertEquals(Decision.NONE, result.decision)
        assertTrue(result.reason.contains("DM"))
    }

    @Test
    fun `allow single reel off presses back`() {
        val run = Run(PolicySettings(allowSingleReel = false))
        run.seen(0, dm)
        assertEquals(Decision.BACK, run.seen(500, viewer).decision)
    }

    @Test
    fun `viewer stays allowed while the session lasts`() {
        val run = Run()
        run.seen(0, dm)
        run.seen(500, viewer)
        assertEquals(Decision.NONE, run.seen(800, viewer).decision)
        assertEquals(Decision.NONE, run.seen(9_000, viewer).decision)
    }

    @Test
    fun `unknown screens do not end the session or change state`() {
        val run = Run()
        run.seen(0, dm)
        run.seen(500, viewer)
        run.scroll(600, 2)
        val before = run.policy
        assertEquals(Decision.NONE, run.seen(2_000, unknown).decision)
        assertEquals(before, run.policy)
        assertEquals(Decision.SNAP_BACK, run.scroll(3_000, 3).decision)
    }

    @Test
    fun `leaving the viewer ends the session`() {
        val run = Run()
        run.seen(0, dm)
        run.seen(500, viewer)
        run.scroll(600, 2)
        run.seen(2_000, dm)
        assertEquals(Decision.NONE, run.scroll(3_000, 5).decision)
    }

    @Test
    fun `dm screen is never blocked`() {
        val run = Run()
        run.click(0, "clips_tab")
        (0L..20L).forEach { i -> assertEquals(Decision.NONE, run.seen(100 + i * 50, dm).decision) }
    }

    @Test
    fun `blocking disabled never acts`() {
        val run = Run(PolicySettings(blockingEnabled = false))
        run.seen(0, feed)
        run.click(100, "clips_tab")
        assertEquals(Decision.NONE, run.seen(300, reelsTab).decision)
        run.seen(1_000, dm)
        run.seen(1_500, viewer)
        run.scroll(1_600, 0)
        assertEquals(Decision.NONE, run.scroll(4_000, 1).decision)
    }

    @Test
    fun `cooldown suppresses a second action within 700 ms`() {
        val run = Run()
        assertEquals(Decision.GO_HOME, run.seen(0, reelsTab).decision)
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

    @Test
    fun `clicks on other views are ignored`() {
        val run = Run()
        run.seen(0, feed)
        run.click(100, "feed_tab")
        run.feed(ViewClicked(150, null))
        assertEquals(Decision.NONE, run.seen(300, viewer).decision)
    }

    @Test
    fun `back decision is retried after cooldown while viewer stays`() {
        val run = Run(PolicySettings(allowSingleReel = false))
        run.seen(0, dm)
        assertEquals(Decision.BACK, run.seen(500, viewer).decision)
        assertEquals(Decision.NONE, run.seen(700, viewer).decision)
        assertEquals(Decision.BACK, run.seen(1_300, viewer).decision)
    }
}
