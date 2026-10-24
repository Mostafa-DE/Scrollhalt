package app.scrollhalt.detect

import app.scrollhalt.detect.PolicyEvent.Attempt
import app.scrollhalt.detect.PolicyEvent.Confirmed
import app.scrollhalt.detect.PolicyEvent.SentToLauncher
import app.scrollhalt.detect.PolicyInput.LeftApp
import app.scrollhalt.detect.PolicyInput.PagerScrolled
import app.scrollhalt.detect.PolicyInput.ScreenSeen
import app.scrollhalt.detect.PolicyInput.ViewClicked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The structured events behind the "Blocked" statistics: attempts, not retries. */
class ReelPolicyEventsTest {
    private val reelsTab = Classification(Screen.REELS_TAB, tabBarVisible = true, selectedTab = "clips_tab")
    private val shared = Classification(Screen.SINGLE_REEL, sharedInDm = true)
    private val feedReel = Classification(Screen.SINGLE_REEL)
    private val exploreReel = Classification(Screen.SINGLE_REEL, selectedTab = "search_tab")
    private val grid = Classification(Screen.EXPLORE_GRID, tabBarVisible = true, selectedTab = "search_tab")
    private val feed = Classification(Screen.OTHER, tabBarVisible = true, selectedTab = "feed_tab")
    private val dm = Classification(Screen.DM)
    private val unknown = Classification(Screen.UNKNOWN)

    private class Run(var settings: PolicySettings = PolicySettings()) {
        var policy: ReelPolicy = ReelPolicy()
        val events = mutableListOf<PolicyEvent>()

        fun feed(input: PolicyInput): PolicyResult = policy.reduce(input, settings).also {
            policy = it.policy
            events += it.events
        }

        fun seen(at: Long, classification: Classification): PolicyResult = feed(ScreenSeen(at, classification))
        fun click(at: Long, name: String): PolicyResult = feed(ViewClicked(at, ig(name)))
        fun scroll(at: Long, index: Int, pager: String = "w1:pager"): PolicyResult = feed(PagerScrolled(at, pager, index))
    }

    @Test
    fun `reels tab attempt is counted once across its ladder`() {
        val run = Run()
        assertEquals(listOf<PolicyEvent>(Attempt(BlockCategory.REELS_TAB)), run.seen(0, reelsTab).events)
        assertTrue(run.seen(300, reelsTab).events.isEmpty())
        assertTrue(run.seen(700, reelsTab).events.isEmpty())
        assertEquals(listOf<PolicyEvent>(SentToLauncher), run.seen(1_400, reelsTab).events)
        assertTrue(run.seen(2_100, reelsTab).events.isEmpty())
        assertEquals(listOf(Attempt(BlockCategory.REELS_TAB), SentToLauncher), run.events)
    }

    @Test
    fun `leaving the reels tab confirms the action`() {
        val run = Run()
        run.seen(0, reelsTab)
        assertEquals(listOf<PolicyEvent>(Confirmed(BlockCategory.REELS_TAB)), run.seen(300, feed).events)
        assertTrue(run.seen(600, feed).events.isEmpty())
    }

    @Test
    fun `ten reels tab entries are ten attempts and ten confirmations`() {
        val run = Run()
        (0 until 10).forEach { i ->
            run.seen(i * 1_000L, reelsTab)
            run.seen(i * 1_000L + 400, feed)
        }
        assertEquals(10, run.events.count { it == Attempt(BlockCategory.REELS_TAB) })
        assertEquals(10, run.events.count { it == Confirmed(BlockCategory.REELS_TAB) })
    }

    @Test
    fun `an attempt held back by the cooldown is counted when its retry acts`() {
        val run = Run()
        run.click(0, "clips_tab")
        run.seen(200, feed)
        val held = run.seen(400, reelsTab)
        assertEquals(Decision.NONE, held.decision)
        assertTrue(held.events.isEmpty())
        assertEquals(listOf<PolicyEvent>(Attempt(BlockCategory.REELS_TAB)), run.seen(700, reelsTab).events)
    }

    @Test
    fun `only acting results carry attempts during rapid taps`() {
        val run = Run()
        val results = mutableListOf(run.click(0, "clips_tab"))
        (1..10).forEach { i ->
            results += run.click(i * 200L, "clips_tab")
            results += run.seen(i * 200L + 100, reelsTab)
        }
        results.forEach { result ->
            val attempts = result.events.count { it is Attempt }
            assertEquals(if (result.decision == Decision.GO_HOME && result.policy.pending?.streak == 0) 1 else 0, attempts)
        }
    }

    @Test
    fun `go home landing on a reel viewer confirms the reels tab`() {
        val run = Run()
        run.seen(0, feed)
        run.seen(100, reelsTab)
        assertEquals(listOf<PolicyEvent>(Confirmed(BlockCategory.REELS_TAB)), run.seen(900, feedReel).events)
    }

    @Test
    fun `a reel closed onto the reels tab is confirmed once`() {
        // Codex 7: the Reels tab replaced the pending Back without confirming it, also when the
        // Reels tab scan was held back by the cooldown.
        val run = Run()
        run.seen(0, grid)
        run.seen(500, exploreReel)
        val held = run.seen(800, reelsTab)
        assertEquals(Decision.NONE, held.decision)
        assertEquals(listOf<PolicyEvent>(Confirmed(BlockCategory.EXPLORE_REEL)), held.events)
        assertEquals(listOf<PolicyEvent>(Attempt(BlockCategory.REELS_TAB)), run.seen(1_300, reelsTab).events)
        assertEquals(1, run.events.count { it is Confirmed })
    }

    @Test
    fun `explore reel counts once per closed viewer`() {
        val run = Run()
        run.seen(0, grid)
        assertEquals(listOf<PolicyEvent>(Attempt(BlockCategory.EXPLORE_REEL)), run.seen(500, exploreReel).events)
        assertTrue(run.seen(1_200, exploreReel).events.isEmpty())
        assertEquals(listOf<PolicyEvent>(Confirmed(BlockCategory.EXPLORE_REEL)), run.seen(1_500, grid).events)
        run.seen(3_000, exploreReel)
        assertEquals(2, run.events.count { it == Attempt(BlockCategory.EXPLORE_REEL) })
    }

    @Test
    fun `next reel swipe counts once and its retries do not`() {
        val run = Run()
        run.seen(0, dm)
        assertTrue(run.seen(500, shared).events.isEmpty())
        run.scroll(600, 4)
        assertEquals(listOf<PolicyEvent>(Attempt(BlockCategory.NEXT_REEL)), run.scroll(3_000, 5).events)
        assertTrue(run.scroll(3_100, 5).events.isEmpty())
        assertTrue(run.seen(3_700, shared).events.isEmpty())
        assertEquals(listOf<PolicyEvent>(Confirmed(BlockCategory.NEXT_REEL)), run.seen(4_000, dm).events)
    }

    @Test
    fun `instagram positioning the pager is never a swipe attempt`() {
        val run = Run()
        val pager = "5274:1568790"
        run.seen(19_748, unknown)
        run.seen(19_893, feedReel.copy(reelPage = "1568790/1596659"))
        run.scroll(19_894, 0, pager)
        run.scroll(20_240, 0, pager)
        run.seen(20_320, feedReel.copy(reelPage = "1568790/1586088"))
        assertTrue(run.events.isEmpty())
    }

    @Test
    fun `allowed single reels produce no events`() {
        val run = Run()
        run.seen(0, feed)
        run.seen(500, feedReel)
        run.scroll(600, 0)
        run.seen(5_000, feedReel)
        run.seen(9_000, feed)
        assertTrue(run.events.isEmpty())
    }

    @Test
    fun `nothing is blocked while blocking is off`() {
        val run = Run(PolicySettings(blockingEnabled = false))
        run.seen(0, reelsTab)
        run.click(100, "clips_tab")
        run.seen(1_000, grid)
        run.seen(1_500, exploreReel)
        run.scroll(1_600, 0)
        run.scroll(4_000, 1)
        run.seen(5_000, dm)
        assertTrue(run.events.isEmpty())
    }

    @Test
    fun `shared reel closed with shared reels off is not a counted category`() {
        val run = Run(PolicySettings(allowSingleReel = false))
        run.seen(0, dm)
        assertEquals(Decision.BACK, run.seen(500, shared).decision)
        run.seen(800, dm)
        assertTrue(run.events.isEmpty())
    }

    @Test
    fun `an attempt stuck through the detection pause counts once`() {
        // Codex 6: after the launcher failed 3 times the pause cleared the attempt, and the same
        // Reels tab screen became a new attempt (and a new launcher trip) every cycle.
        val run = Run()
        val decisions = (0L..30_000L step 700L).map { run.seen(it, reelsTab).decision }
        assertEquals(1, run.events.count { it is Attempt })
        assertEquals(1, run.events.count { it == SentToLauncher })
        assertTrue(decisions.count { it == Decision.GLOBAL_HOME } > 3)
    }

    @Test
    fun `leaving after the detection pause confirms the stuck attempt`() {
        val run = Run()
        (0L..3_500L step 700L).forEach { run.seen(it, reelsTab) }
        assertEquals(listOf<PolicyEvent>(Confirmed(BlockCategory.REELS_TAB)), run.seen(5_000, feed).events)
        assertEquals(listOf<PolicyEvent>(Attempt(BlockCategory.REELS_TAB)), run.seen(14_000, reelsTab).events)
    }

    @Test
    fun `a tap during the detection pause is a new attempt`() {
        val run = Run()
        (0L..3_500L step 700L).forEach { run.seen(it, reelsTab) }
        run.click(5_000, "clips_tab")
        assertEquals(listOf<PolicyEvent>(Attempt(BlockCategory.REELS_TAB)), run.seen(13_500, reelsTab).events)
    }

    @Test
    fun `leaving instagram after the launcher confirms`() {
        val run = Run()
        run.seen(0, reelsTab)
        run.seen(700, reelsTab)
        run.seen(1_400, reelsTab)
        assertEquals(listOf<PolicyEvent>(Confirmed(BlockCategory.REELS_TAB)), run.feed(LeftApp(1_500)).events)
    }
}
