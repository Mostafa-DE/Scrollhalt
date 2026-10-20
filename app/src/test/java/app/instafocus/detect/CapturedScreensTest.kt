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
    fun `explore reel in front of the reels tab pager is allowed`() {
        val tree = arrayOf<UiNode>(
            reelViewer(bounds = Bounds(0, 0, 1080, 2186)),
            reelViewer(bounds = Bounds(0, 0, 1080, 2017)),
            tabBar(selected = "search_tab", visible = false),
        )
        both.forEach { assertEquals(Decision.NONE, decide(it, MAIN_TAB, *tree)) }
    }

    @Test
    fun `shared reel in the modal activity is allowed, or closed with single reels off`() {
        assertEquals(Decision.NONE, decide(PolicySettings(), MODAL, sharedReelViewer()))
        assertEquals(Decision.BACK, decide(PolicySettings(allowSingleReel = false), MODAL, sharedReelViewer()))
    }
}
