package app.instafocus.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenClassifierTest {
    private fun classify(vararg children: UiNode): Classification = ScreenClassifier.classify(root(*children))

    @Test
    fun `visible viewer with reel controls is a reel viewer`() {
        val result = classify(reelViewer())
        assertEquals(Screen.REEL_VIEWER, result.screen)
        assertFalse(result.reelsTabSelected)
    }

    @Test
    fun `alternate pager id is a reel viewer`() {
        assertEquals(Screen.REEL_VIEWER, classify(reelViewer(pagerName = "clips_view_pager")).screen)
    }

    @Test
    fun `viewer present but not visible is not a reel viewer`() {
        // New Instagram layout keeps the Reels tab loaded behind the feed.
        val result = classify(feed(), reelViewer(viewerVisible = false), tabBar())
        assertEquals(Screen.OTHER, result.screen)
    }

    @Test
    fun `viewer with empty bounds is not on screen`() {
        val hidden = FakeNode(
            ig("clips_viewer_view_pager"), bounds = Bounds(0, 0, 0, 0),
            children = listOf(node("clips_ufi_component", bounds = Bounds(0, 0, 0, 0))),
        )
        assertEquals(Screen.OTHER, classify(hidden).screen)
    }

    @Test
    fun `viewer without reel controls is unknown`() {
        assertEquals(Screen.UNKNOWN, classify(reelViewer(withUi = false)).screen)
    }

    @Test
    fun `reels tab selected and visible is reported`() {
        val result = classify(reelViewer(), tabBar(reelsSelected = true))
        assertEquals(Screen.REEL_VIEWER, result.screen)
        assertTrue(result.reelsTabSelected)
    }

    @Test
    fun `selected reels tab that is not visible is ignored`() {
        assertFalse(classify(feed(), tabBar(reelsSelected = true, visible = false)).reelsTabSelected)
    }

    @Test
    fun `dm thread is a dm`() {
        assertEquals(Screen.DM, classify(dmThread()).screen)
    }

    @Test
    fun `dm inbox is a dm`() {
        assertEquals(Screen.DM, classify(node("direct_inbox_recycler_view", bounds = SCREEN)).screen)
    }

    @Test
    fun `hidden dm markers do not count`() {
        assertEquals(Screen.OTHER, classify(feed(), node("direct_thread_toolbar", visible = false)).screen)
    }

    @Test
    fun `explore search bar is explore`() {
        assertEquals(Screen.EXPLORE, classify(exploreGrid(), tabBar()).screen)
    }

    @Test
    fun `feed is other`() {
        assertEquals(Screen.OTHER, classify(feed(), tabBar()).screen)
    }

    @Test
    fun `reply bar over the viewer marks a dm overlay`() {
        val result = classify(reelViewer(), node("reply_bar_container_scroll_view"))
        assertEquals(Screen.REEL_VIEWER, result.screen)
        assertTrue(result.dmOverlay)
    }

    @Test
    fun `on screen ids skip hidden nodes and duplicates`() {
        val tree = root(feed(), node("feed_tab"), node("feed_tab"), node("clips_viewer_view_pager", visible = false))
        assertEquals(listOf(ig("main_feed"), ig("row_feed_photo_profile_name"), ig("feed_tab")), tree.onScreenIds())
    }
}
