package app.instafocus.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenClassifierTest {
    private fun classify(activity: String?, vararg children: UiNode): Classification =
        ScreenClassifier.classify(root(*children), activity)

    @Test
    fun `reels tab selected with tab bar on screen is the reels tab`() {
        val result = classify(MAIN_TAB, reelViewer(), tabBar(selected = "clips_tab"))
        assertEquals(Screen.REELS_TAB, result.screen)
        assertEquals("clips_tab", result.selectedTab)
    }

    @Test
    fun `reels tab with unknown activity is the reels tab`() {
        assertEquals(Screen.REELS_TAB, classify(null, reelViewer(), tabBar(selected = "clips_tab")).screen)
    }

    @Test
    fun `reels tab selected flag inside another activity is not the reels tab`() {
        assertEquals(Screen.OTHER, classify(MODAL, tabBar(selected = "clips_tab")).screen)
    }

    @Test
    fun `hidden tab bar with reels selected is not the reels tab`() {
        val result = classify(MAIN_TAB, feed(), tabBar(selected = "clips_tab", visible = false))
        assertEquals(Screen.OTHER, result.screen)
        assertNull(result.selectedTab)
    }

    @Test
    fun `feed with the reels tree alive behind it is other`() {
        val result = classify(MAIN_TAB, reelViewer(), feed(), tabBar(selected = "feed_tab"))
        assertEquals(Screen.OTHER, result.screen)
        assertEquals("feed_tab", result.selectedTab)
    }

    @Test
    fun `dm inbox with the reels tree behind it is a dm`() {
        assertEquals(Screen.DM, classify(MAIN_TAB, reelViewer(), dmInbox(), tabBar(selected = "direct_tab")).screen)
    }

    @Test
    fun `dm thread with the reels tree behind it and no tab bar is a dm`() {
        // Captured on 451: inside a thread tab_bar is gone and the Reels viewer stays "visible" behind.
        val result = classify(MAIN_TAB, reelViewer(), dmThread())
        assertEquals(Screen.DM, result.screen)
        assertFalse(result.sharedInDm)
    }

    @Test
    fun `reel shared in a dm opens in the modal activity`() {
        val result = classify(MODAL, sharedReelViewer())
        assertEquals(Screen.SINGLE_REEL, result.screen)
        assertTrue(result.sharedInDm)
        assertEquals(MODAL, result.activity)
    }

    @Test
    fun `sender markers alone mark a shared reel`() {
        assertTrue(classify(null, sharedReelViewer()).sharedInDm)
    }

    @Test
    fun `modal activity alone marks a shared reel`() {
        val result = classify(MODAL, reelViewer())
        assertEquals(Screen.SINGLE_REEL, result.screen)
        assertTrue(result.sharedInDm)
    }

    @Test
    fun `explore reel in front of the reels tab pager is a single reel`() {
        // Captured on 451: two pagers, tab bar gone, search_tab selected but hidden.
        val result = classify(
            MAIN_TAB,
            reelViewer(bounds = Bounds(0, 0, 1080, 2186)),
            reelViewer(bounds = Bounds(0, 0, 1080, 2017), withUi = true, pagerName = "clips_viewer_view_pager", node("comment_composer_inner_layout")),
            tabBar(selected = "search_tab", visible = false),
        )
        assertEquals(Screen.SINGLE_REEL, result.screen)
        assertFalse(result.sharedInDm)
    }

    @Test
    fun `alternate pager id is recognised`() {
        assertEquals(Screen.SINGLE_REEL, classify(MAIN_TAB, reelViewer(pagerName = "clips_view_pager")).screen)
    }

    @Test
    fun `pager not visible is other`() {
        val hidden = node("clips_viewer_view_pager", visible = false, bounds = SCREEN, children = arrayOf(node("clips_ufi_component", visible = false)))
        assertEquals(Screen.OTHER, classify(MAIN_TAB, hidden).screen)
    }

    @Test
    fun `pager with empty bounds is other`() {
        val empty = Bounds(0, 0, 0, 0)
        val hidden = node("clips_viewer_view_pager", bounds = empty, children = arrayOf(node("clips_ufi_component", bounds = empty)))
        assertEquals(Screen.OTHER, classify(MAIN_TAB, hidden).screen)
    }

    @Test
    fun `pager without reel controls is unknown`() {
        assertEquals(Screen.UNKNOWN, classify(MAIN_TAB, reelViewer(withUi = false)).screen)
    }

    @Test
    fun `on screen ids skip hidden nodes and duplicates`() {
        val tree = root(feed(), node("feed_tab"), node("feed_tab"), node("clips_viewer_view_pager", visible = false))
        assertEquals(listOf(ig("main_feed"), ig("row_feed_photo_profile_name"), ig("feed_tab")), tree.onScreenIds())
    }
}
