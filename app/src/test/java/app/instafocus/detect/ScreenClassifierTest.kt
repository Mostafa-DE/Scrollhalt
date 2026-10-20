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
    fun `tab buttons without a tab bar node count as the tab bar`() {
        // Device dump on 451: feed_tab, clips_tab, ... are direct children of the root.
        val result = classify(INSTAGRAM_MAIN, reelViewer(), *tabButtons(selected = "clips_tab"))
        assertEquals(Screen.REELS_TAB, result.screen)
        assertTrue(result.tabBarVisible)
    }

    @Test
    fun `no tab selected and the reels pager on screen is the reels tab`() {
        // After a cold start no tab reports isSelected; the Reels page being on screen decides.
        assertEquals(Screen.REELS_TAB, classify(INSTAGRAM_MAIN, reelViewer(), *tabButtons()).screen)
    }

    @Test
    fun `no tab selected and the reels page off screen is other`() {
        val result = classify(INSTAGRAM_MAIN, feed(), offscreenReelsPage(), *tabButtons())
        assertEquals(Screen.OTHER, result.screen)
        assertNull(result.selectedTab)
    }

    @Test
    fun `feed tab selected wins over a visible reels tree`() {
        assertEquals(Screen.OTHER, classify(INSTAGRAM_MAIN, reelViewer(), feed(), *tabButtons(selected = "feed_tab")).screen)
    }

    @Test
    fun `explore grid with search tab selected is the explore grid`() {
        assertEquals(Screen.EXPLORE_GRID, classify(INSTAGRAM_MAIN, exploreGrid(), offscreenReelsPage(), *tabButtons("search_tab")).screen)
    }

    @Test
    fun `fewer than three grid cells is not the explore grid`() {
        assertEquals(Screen.OTHER, classify(INSTAGRAM_MAIN, exploreGrid(cells = 2), *tabButtons("search_tab")).screen)
    }

    @Test
    fun `grid cells outside the search tab are not the explore grid`() {
        assertEquals(Screen.OTHER, classify(INSTAGRAM_MAIN, exploreGrid(), *tabButtons("feed_tab")).screen)
    }

    @Test
    fun `search results are other`() {
        assertEquals(Screen.OTHER, classify(INSTAGRAM_MAIN, searchResults(), *tabButtons("search_tab")).screen)
    }

    @Test
    fun `profile page is a profile`() {
        assertEquals(Screen.PROFILE, classify(INSTAGRAM_MAIN, profilePage()).screen)
    }

    @Test
    fun `own profile tab is a profile`() {
        assertEquals(Screen.PROFILE, classify(INSTAGRAM_MAIN, profilePage(), *tabButtons("profile_tab")).screen)
    }

    @Test
    fun `reel in front of a profile is a reel`() {
        assertEquals(Screen.SINGLE_REEL, classify(INSTAGRAM_MAIN, profilePage(), reelViewer()).screen)
    }

    private fun pagerWithPages(onScreenPage: Int): FakeNode {
        val pages = (0..2).map { i ->
            val bounds = if (i == onScreenPage) SCREEN else Bounds(1080, 0, 1080, 2400)
            FakeNode(null, isVisibleToUser = i == onScreenPage, bounds = bounds, key = 100 + i, children = listOf(node("clips_ufi_component", visible = i == onScreenPage)))
        }
        return FakeNode(ig("clips_viewer_view_pager"), bounds = SCREEN, key = 7, children = pages)
    }

    @Test
    fun `reel page identity is the on-screen page of the front pager`() {
        assertEquals("7/100", classify(INSTAGRAM_MAIN, pagerWithPages(0)).reelPage)
        assertEquals("7/101", classify(INSTAGRAM_MAIN, pagerWithPages(1)).reelPage)
    }

    @Test
    fun `half-swiped pager reports the page with the larger visible area`() {
        val top = FakeNode(null, bounds = Bounds(0, 0, 1080, 600), key = 1, children = listOf(node("clips_ufi_component")))
        val bottom = FakeNode(null, bounds = Bounds(0, 600, 1080, 2400), key = 2)
        val pager = FakeNode(ig("clips_viewer_view_pager"), bounds = SCREEN, key = 9, children = listOf(top, bottom))
        assertEquals("9/2", classify(INSTAGRAM_MAIN, pager).reelPage)
    }

    @Test
    fun `no reel page without node identities or outside a reel`() {
        assertNull(classify(INSTAGRAM_MAIN, reelViewer()).reelPage)
        assertNull(classify(INSTAGRAM_MAIN, feed(), *tabButtons("feed_tab")).reelPage)
    }

    @Test
    fun `reels tab with unknown activity is the reels tab`() {
        assertEquals(Screen.REELS_TAB, classify(null, reelViewer(), tabBar(selected = "clips_tab")).screen)
    }

    @Test
    fun `reels tab is detected in any activity`() {
        // Window-state events name InstagramMainActivity, not MainTabActivity.
        listOf(INSTAGRAM_MAIN, MODAL, "com.instagram.SomeFutureActivity").forEach { activity ->
            assertEquals(activity, Screen.REELS_TAB, classify(activity, reelViewer(), tabBar(selected = "clips_tab")).screen)
        }
    }

    @Test
    fun `hidden tab bar with reels selected is not the reels tab`() {
        val result = classify(MAIN_TAB, feed(), tabBar(selected = "clips_tab", visible = false))
        assertEquals(Screen.OTHER, result.screen)
        assertFalse(result.tabBarVisible)
        assertNull(result.reelsTabBounds)
    }

    @Test
    fun `selected tab is read from hidden tab buttons`() {
        // Explore-opened reel on 451: tab bar gone, search_tab still selected.
        val result = classify(MAIN_TAB, reelViewer(), tabBar(selected = "search_tab", visible = false))
        assertEquals(Screen.SINGLE_REEL, result.screen)
        assertEquals("search_tab", result.selectedTab)
    }

    @Test
    fun `visible reels tab reports its bounds for the cover`() {
        val result = classify(INSTAGRAM_MAIN, feed(), *tabButtons(selected = "feed_tab"))
        assertEquals(Screen.OTHER, result.screen)
        assertEquals(Bounds(0, 2200, 200, 2400), result.reelsTabBounds)
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
