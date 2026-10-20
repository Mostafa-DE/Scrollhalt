package app.instafocus.detect

import app.instafocus.detect.PolicyInput.PagerScrolled
import app.instafocus.detect.PolicyInput.ScreenSeen
import app.instafocus.detect.PolicyInput.ViewClicked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelPolicyTest {
    private val reelsTab = Classification(Screen.REELS_TAB, tabBarVisible = true, selectedTab = "clips_tab")
    private val shared = Classification(Screen.SINGLE_REEL, sharedInDm = true)
    private val feedReel = Classification(Screen.SINGLE_REEL)
    private val exploreReel = Classification(Screen.SINGLE_REEL, selectedTab = "search_tab")
    private val dm = Classification(Screen.DM)
    private val feed = Classification(Screen.OTHER, tabBarVisible = true, selectedTab = "feed_tab")
    private val searchScreen = Classification(Screen.OTHER, tabBarVisible = true, selectedTab = "search_tab")
    private val unknown = Classification(Screen.UNKNOWN)

    /** Feeds inputs one by one and keeps the latest policy. */
    private class Run(var settings: PolicySettings = PolicySettings()) {
        var policy: ReelPolicy = ReelPolicy()

        fun feed(input: PolicyInput): PolicyResult = policy.reduce(input, settings).also { policy = it.policy }
        fun seen(at: Long, classification: Classification): PolicyResult = feed(ScreenSeen(at, classification))
        fun click(at: Long, name: String): PolicyResult = feed(ViewClicked(at, ig(name)))
        fun scroll(at: Long, index: Int, pager: String = "w1:pager"): PolicyResult = feed(PagerScrolled(at, pager, index))
    }

    // Reels tab

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
        assertNull(run.policy.pausedUntil)
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
        assertEquals(Decision.GO_HOME, run.seen(13_500, reelsTab).decision)
    }

    @Test
    fun `reopening instagram after the launcher worked never counts as a failure`() {
        // Review lead 2: user keeps reopening Instagram on the Reels tab. Each launcher trip is
        // followed by LeftApp, so the ladder restarts and the pause is never reached.
        val run = Run()
        var at = 0L
        repeat(10) {
            run.seen(at, reelsTab)
            run.seen(at + 700, reelsTab)
            assertEquals(Decision.GLOBAL_HOME, run.seen(at + 1_400, reelsTab).decision)
            run.feed(PolicyInput.LeftApp(at + 1_500))
            at += 3_000
        }
        assertNull(run.policy.pausedUntil)
        assertEquals(Decision.GO_HOME, run.seen(at, reelsTab).decision)
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
        assertNull(run.seen(900, unknown).retryAtMillis)
        assertNull(run.click(950, "feed_tab").retryAtMillis)
    }

    @Test
    fun `go home landing on a reel viewer counts as done`() {
        val run = Run()
        run.seen(0, reelsTab)
        val result = run.seen(800, feedReel)
        assertEquals(Decision.NONE, result.decision)
        assertNull(result.policy.pending)
    }

    // Single reels: first plays, swipe closes

    @Test
    fun `dm reel plays then a swipe closes it`() {
        val run = Run()
        run.seen(0, dm)
        assertEquals(Decision.NONE, run.seen(500, shared).decision)
        run.scroll(600, 4)
        assertEquals(Decision.NONE, run.scroll(3_000, 4).decision)
        val swipe = run.scroll(3_200, 5)
        assertEquals(Decision.BACK, swipe.decision)
        assertEquals(Decision.NONE, run.seen(3_500, dm).decision)
        assertNull(run.policy.session)
    }

    @Test
    fun `feed reel plays then a swipe closes it`() {
        val run = Run()
        run.seen(0, feed)
        assertEquals(Decision.NONE, run.seen(500, feedReel).decision)
        run.scroll(600, 0)
        assertEquals(Decision.BACK, run.scroll(3_000, 1).decision)
    }

    @Test
    fun `swipe upwards closes too`() {
        val run = Run()
        run.seen(0, shared)
        run.scroll(700, 4)
        assertEquals(Decision.BACK, run.scroll(3_000, 3).decision)
    }

    @Test
    fun `back that does not close the viewer escalates to the launcher`() {
        val run = Run()
        run.seen(0, shared)
        run.scroll(300, 4)
        assertEquals(Decision.BACK, run.scroll(3_000, 5).decision)
        assertEquals(Decision.BACK, run.seen(3_700, shared).decision)
        assertEquals(Decision.GLOBAL_HOME, run.seen(4_400, shared).decision)
    }

    @Test
    fun `swipe held back by the cooldown is enforced by the retry scan`() {
        // Review lead 1: a swipe whose BACK is held back by the cooldown must not be forgotten.
        val run = Run()
        run.seen(0, shared)
        run.scroll(100, 2)
        // An unrelated action just happened (e.g. GO_HOME from a stray Reels tab click).
        run.policy = run.policy.copy(lastActionAt = 2_900)
        val held = run.scroll(3_000, 3)
        assertEquals(Decision.NONE, held.decision)
        assertEquals(3_600L, held.retryAtMillis)
        assertEquals(Decision.BACK, run.seen(3_600, shared).decision)
    }

    @Test
    fun `scroll just before the viewer is classified sets the start index`() {
        val run = Run()
        run.scroll(450, 2)
        run.seen(500, feedReel)
        assertEquals(Decision.NONE, run.scroll(3_000, 2).decision)
        assertEquals(Decision.BACK, run.scroll(3_500, 3).decision)
    }

    @Test
    fun `last index in the settle window wins`() {
        val run = Run()
        run.seen(500, feedReel)
        run.scroll(600, 0)
        run.scroll(900, 7)
        assertEquals(Decision.NONE, run.scroll(3_000, 7).decision)
    }

    @Test
    fun `start index adopted late when none was seen`() {
        val run = Run()
        run.seen(500, feedReel)
        assertEquals(Decision.NONE, run.scroll(5_000, 3).decision)
        assertEquals(Decision.BACK, run.scroll(5_300, 4).decision)
    }

    @Test
    fun `scrolls of another pager restart tracking instead of closing`() {
        val run = Run()
        run.seen(0, feedReel)
        run.scroll(300, 0, pager = "w1:front")
        assertEquals(Decision.NONE, run.scroll(3_000, 9, pager = "w1:other").decision)
        assertEquals(Decision.BACK, run.scroll(4_000, 10, pager = "w1:other").decision)
    }

    @Test
    fun `negative scroll index is ignored`() {
        val run = Run()
        run.seen(500, feedReel)
        run.scroll(600, 1)
        assertEquals(Decision.NONE, run.scroll(3_000, -1).decision)
    }

    @Test
    fun `pager scroll outside a reel viewer does nothing`() {
        val run = Run()
        run.seen(0, feed)
        assertEquals(Decision.NONE, run.scroll(100, 3).decision)
    }

    @Test
    fun `first reel stays allowed while the viewer lasts`() {
        val run = Run()
        run.seen(500, feedReel)
        val again = run.seen(9_000, feedReel)
        assertEquals(Decision.NONE, again.decision)
        assertTrue(again.reason.contains("OTHER"))
    }

    @Test
    fun `unknown screens do not change state`() {
        val run = Run()
        run.seen(500, shared)
        run.scroll(600, 2)
        val before = run.policy
        assertEquals(Decision.NONE, run.seen(2_000, unknown).decision)
        assertEquals(before, run.policy)
        assertEquals(Decision.BACK, run.scroll(3_000, 3).decision)
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
    fun `single reels off closes a dm reel at once and escalates`() {
        val run = Run(PolicySettings(allowSingleReel = false))
        run.seen(0, dm)
        assertEquals(Decision.BACK, run.seen(500, shared).decision)
        assertEquals(Decision.BACK, run.seen(1_200, shared).decision)
        assertEquals(Decision.GLOBAL_HOME, run.seen(1_900, shared).decision)
    }

    @Test
    fun `single reels off still lets a feed reel play once`() {
        val run = Run(PolicySettings(allowSingleReel = false))
        assertEquals(Decision.NONE, run.seen(500, feedReel).decision)
        run.scroll(600, 0)
        assertEquals(Decision.BACK, run.scroll(3_000, 1).decision)
    }

    @Test
    fun `turning single reels off applies to an open dm reel`() {
        // Review lead 6.
        val run = Run()
        assertEquals(Decision.NONE, run.seen(0, shared).decision)
        run.settings = PolicySettings(allowSingleReel = false)
        assertEquals(Decision.BACK, run.seen(2_000, shared).decision)
    }

    // Explore

    @Test
    fun `explore reel is closed at once`() {
        val run = Run()
        run.seen(0, searchScreen)
        assertEquals(Decision.BACK, run.seen(500, exploreReel).decision)
        assertEquals(Decision.NONE, run.seen(800, searchScreen).decision)
        assertNull(run.policy.pending)
    }

    @Test
    fun `explore reel without a selected tab uses the last visible tab`() {
        // If the hidden tab bar is missing from the tree, the tab seen last decides.
        val run = Run()
        run.seen(0, searchScreen)
        assertEquals(Decision.BACK, run.seen(500, feedReel).decision)
    }

    @Test
    fun `explore reel back ineffective twice goes to the launcher`() {
        val run = Run()
        assertEquals(Decision.BACK, run.seen(0, exploreReel).decision)
        assertEquals(Decision.BACK, run.seen(700, exploreReel).decision)
        assertEquals(Decision.GLOBAL_HOME, run.seen(1_400, exploreReel).decision)
    }

    @Test
    fun `search screen without a reel in front is left alone`() {
        val run = Run()
        (0L..10L).forEach { i -> assertEquals(Decision.NONE, run.seen(i * 100, searchScreen).decision) }
    }

    @Test
    fun `feed reel after leaving search is a first reel again`() {
        val run = Run()
        run.seen(0, searchScreen)
        run.seen(500, feed)
        assertEquals(Decision.NONE, run.seen(1_000, feedReel).decision)
    }

    @Test
    fun `dm reel wins over a remembered search tab`() {
        val run = Run()
        run.seen(0, searchScreen)
        assertEquals(Decision.NONE, run.seen(500, shared).decision)
    }

    // Safety

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
        assertEquals(Decision.NONE, run.seen(1_500, exploreReel).decision)
        run.scroll(1_600, 0)
        assertEquals(Decision.NONE, run.scroll(4_000, 1).decision)
    }

    @Test
    fun `left app clears the session and pending action`() {
        val run = Run()
        run.seen(0, shared)
        run.scroll(100, 1)
        run.feed(PolicyInput.LeftApp(500))
        assertNull(run.policy.session)
        assertNull(run.policy.pending)
    }
}
