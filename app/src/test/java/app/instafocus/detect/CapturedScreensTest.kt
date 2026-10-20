package app.instafocus.detect

import org.junit.Assert.assertEquals
import org.junit.Test

/** Classifier and policy together, on layouts captured from Instagram 451.0.0.53.80. */
class CapturedScreensTest {
    private fun decide(settings: PolicySettings, activity: String, vararg children: UiNode): Decision {
        val classification = ScreenClassifier.classify(root(*children), activity)
        return ReelPolicy().reduce(PolicyInput.ScreenSeen(0, classification), settings).decision
    }

    private val both = listOf(PolicySettings(), PolicySettings(allowSingleReel = false))

    @Test
    fun `main tab with reels tab selected goes home`() {
        assertEquals(Decision.GO_HOME, decide(PolicySettings(), MAIN_TAB, reelViewer(), tabBar(selected = "clips_tab")))
    }

    @Test
    fun `instagram main activity with reels tab selected goes home`() {
        // Captured on 451 via logcat: window-state className is InstagramMainActivity.
        assertEquals(Decision.GO_HOME, decide(PolicySettings(), INSTAGRAM_MAIN, reelViewer(), tabBar(selected = "clips_tab")))
    }

    @Test
    fun `device accessibility tree on the reels tab goes home`() {
        // As dumped on the phone: tab buttons under the root, clips_tab selected after the tap.
        assertEquals(Decision.GO_HOME, decide(PolicySettings(), INSTAGRAM_MAIN, reelViewer(), *tabButtons(selected = "clips_tab")))
    }

    @Test
    fun `device accessibility tree on the feed is left alone`() {
        both.forEach {
            assertEquals(Decision.NONE, decide(it, INSTAGRAM_MAIN, feed(), offscreenReelsPage(), *tabButtons(selected = "feed_tab")))
            assertEquals(Decision.NONE, decide(it, INSTAGRAM_MAIN, feed(), offscreenReelsPage(), *tabButtons()))
        }
    }

    @Test
    fun `dm thread with the reels tree behind it is left alone`() {
        both.forEach { assertEquals(Decision.NONE, decide(it, MAIN_TAB, reelViewer(), dmThread())) }
    }

    @Test
    fun `dm inbox with direct tab selected and the reels tree behind it is left alone`() {
        both.forEach {
            assertEquals(Decision.NONE, decide(it, MAIN_TAB, reelViewer(), dmInbox(), tabBar(selected = "direct_tab")))
        }
    }

    @Test
    fun `feed with the reels tree behind it is left alone`() {
        both.forEach { assertEquals(Decision.NONE, decide(it, MAIN_TAB, reelViewer(), feed(), tabBar())) }
    }

    @Test
    fun `explore reel in front of the reels tab pager is closed`() {
        val tree = arrayOf<UiNode>(
            reelViewer(bounds = Bounds(0, 0, 1080, 2186)),
            reelViewer(bounds = Bounds(0, 0, 1080, 2017)),
            tabBar(selected = "search_tab", visible = false),
        )
        both.forEach { assertEquals(Decision.BACK, decide(it, MAIN_TAB, *tree)) }
    }

    /** Classifies each tree in turn with one policy and returns the last decision. */
    private fun sequence(vararg screens: Array<UiNode>): Decision {
        var policy = ReelPolicy()
        var decision = Decision.NONE
        screens.forEachIndexed { i, children ->
            val result = policy.reduce(PolicyInput.ScreenSeen(i * 1_000L, ScreenClassifier.classify(root(*children), INSTAGRAM_MAIN)), PolicySettings())
            policy = result.policy
            decision = result.decision
        }
        return decision
    }

    @Test
    fun `explore grid then a reel is closed`() {
        val gridScreen = arrayOf<UiNode>(exploreGrid(), offscreenReelsPage(), *tabButtons("search_tab"))
        val reel = arrayOf<UiNode>(reelViewer(bounds = Bounds(0, 0, 1080, 2017)))
        assertEquals(Decision.BACK, sequence(gridScreen, reel))
    }

    @Test
    fun `search, profile, then a reel plays`() {
        val gridScreen = arrayOf<UiNode>(exploreGrid(), *tabButtons("search_tab"))
        val results = arrayOf<UiNode>(searchResults(), *tabButtons("search_tab"))
        val profileScreen = arrayOf<UiNode>(profilePage())
        val reel = arrayOf<UiNode>(reelViewer())
        assertEquals(Decision.NONE, sequence(gridScreen, results, profileScreen, reel))
    }

    @Test
    fun `search screen with the reels page off screen is left alone`() {
        val search = node("explore", bounds = SCREEN, children = arrayOf(node("action_bar_search_edit_text")))
        both.forEach {
            assertEquals(Decision.NONE, decide(it, INSTAGRAM_MAIN, search, offscreenReelsPage(), *tabButtons(selected = "search_tab")))
        }
    }

    @Test
    fun `shared reel in the modal activity is allowed, or closed with single reels off`() {
        assertEquals(Decision.NONE, decide(PolicySettings(), MODAL, sharedReelViewer()))
        assertEquals(Decision.BACK, decide(PolicySettings(allowSingleReel = false), MODAL, sharedReelViewer()))
    }
}
