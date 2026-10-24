package app.scrollhalt.stats

import app.scrollhalt.detect.Classification
import app.scrollhalt.detect.Screen
import app.scrollhalt.protect.ProtectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDateTime

class ReelsViewingTest {
    private val clock = TestClock()
    private val reelsTab = Classification(Screen.REELS_TAB, tabBarVisible = true, selectedTab = "clips_tab")
    private val reel = Classification(Screen.SINGLE_REEL)
    private val unknown = Classification(Screen.UNKNOWN)
    private val feed = Classification(Screen.OTHER, tabBarVisible = true, selectedTab = "feed_tab")
    private val paused = ProtectionMode.PAUSED
    private val protected = ProtectionMode.PROTECTED

    private fun page(n: Int): Classification = reel.copy(reelPage = "7/$n")

    @Test
    fun `a viewer session runs from the reels viewer to another screen`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), reelsTab, paused)
            .viewerSeen(clock.seconds(30), feed, paused)
        assertEquals(
            ReelsDay(pausedMillis = 30_000, reelsTabMillis = 30_000, sessions = 1, watched = 1, longestMillis = 30_000),
            stats.day(OCT_24).reels,
        )
        assertNull(stats.live.viewer)
    }

    @Test
    fun `repeated scans of the same viewer change nothing`() {
        val open = StatsState().viewerSeen(clock.now(), reelsTab, paused)
        assertSame(open, open.viewerSeen(clock.seconds(1), reelsTab, paused))
    }

    @Test
    fun `unknown frames do not end the viewer`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), reel, paused)
            .viewerSeen(clock.seconds(5), unknown, paused)
            .viewerSeen(clock.seconds(5), reel, paused)
            .viewerGone(clock.seconds(5))
        assertEquals(15_000L, stats.day(OCT_24).reels.singleReelMillis)
        assertEquals(1, stats.day(OCT_24).reels.sessions)
    }

    @Test
    fun `a viewer shorter than a second adds time but is no session`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), reelsTab, protected)
            .viewerSeen(clock.advance(400), feed, protected)
        assertEquals(ReelsDay(protectedMillis = 400, reelsTabMillis = 400, longestMillis = 400), stats.day(OCT_24).reels)
    }

    @Test
    fun `time goes to the protection mode it was spent in`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), reel, paused)
            .viewerMode(clock.seconds(20), protected)
            .viewerGone(clock.seconds(10))
        val reels = stats.day(OCT_24).reels
        assertEquals(20_000L, reels.pausedMillis)
        assertEquals(10_000L, reels.protectedMillis)
        assertEquals(30_000L, reels.totalMillis)
    }

    @Test
    fun `reels tab and single reels are split`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), reelsTab, ProtectionMode.DISABLED)
            .viewerSeen(clock.seconds(40), reel, ProtectionMode.DISABLED)
            .viewerGone(clock.seconds(20))
        val reels = stats.day(OCT_24).reels
        assertEquals(40_000L, reels.reelsTabMillis)
        assertEquals(20_000L, reels.singleReelMillis)
        assertEquals(60_000L, reels.disabledMillis)
        assertEquals(2, reels.sessions)
        assertEquals(40_000L, reels.longestMillis)
    }

    @Test
    fun `each new page index is another reel watched`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), reelsTab, paused)
            .viewerScrolled(clock.advance(200), "w:pager", 0)
            .viewerScrolled(clock.seconds(8), "w:pager", 1)
            .viewerScrolled(clock.advance(100), "w:pager", 1)
            .viewerScrolled(clock.seconds(8), "w:pager", 2)
            .viewerScrolled(clock.seconds(8), "w:pager", -1)
            .viewerGone(clock.seconds(1))
        assertEquals(3, stats.day(OCT_24).reels.watched)
    }

    @Test
    fun `the first scroll after the viewer settled is a swipe`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), reelsTab, paused)
            .viewerScrolled(clock.seconds(5), "w:pager", 1)
            .viewerGone(clock.seconds(1))
        assertEquals(2, stats.day(OCT_24).reels.watched)
    }

    @Test
    fun `a positioning scroll just before the viewer is its baseline`() {
        // Codex 8: the scroll came before the first classification and was dropped, so the same
        // index later counted as another reel.
        val stats = StatsState()
            .viewerScrolled(clock.now(), "w:pager", 0)
            .viewerSeen(clock.advance(100), reel, paused)
            .viewerScrolled(clock.seconds(5), "w:pager", 0)
            .viewerGone(clock.seconds(1))
        assertEquals(1, stats.day(OCT_24).reels.watched)
    }

    @Test
    fun `an old scroll is not the baseline of a new viewer`() {
        val stats = StatsState()
            .viewerScrolled(clock.now(), "w:pager", 0)
            .viewerSeen(clock.seconds(5), reel, paused)
            .viewerScrolled(clock.seconds(5), "w:pager", 0)
            .viewerGone(clock.seconds(1))
        assertEquals(2, stats.day(OCT_24).reels.watched)
    }

    @Test
    fun `another pager starts a new baseline`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), reelsTab, paused)
            .viewerScrolled(clock.advance(100), "w:front", 0)
            .viewerScrolled(clock.seconds(3), "w:other", 9)
            .viewerGone(clock.seconds(1))
        assertEquals(1, stats.day(OCT_24).reels.watched)
    }

    @Test
    fun `without indexes page changes count after the viewer settled`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), page(0), paused)
            .viewerSeen(clock.advance(400), page(1), paused)
            .viewerSeen(clock.seconds(5), page(2), paused)
            .viewerSeen(clock.seconds(5), page(2), paused)
            .viewerSeen(clock.seconds(5), page(3), paused)
            .viewerGone(clock.seconds(1))
        assertEquals(3, stats.day(OCT_24).reels.watched)
    }

    @Test
    fun `page changes do not count once the pager reports indexes`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), page(0), paused)
            .viewerScrolled(clock.advance(100), "w:pager", 0)
            .viewerScrolled(clock.seconds(5), "w:pager", 1)
            .viewerSeen(clock.advance(100), page(1), paused)
            .viewerGone(clock.seconds(1))
        assertEquals(2, stats.day(OCT_24).reels.watched)
    }

    @Test
    fun `a swipe while protected is blocked, not watched`() {
        val stats = StatsState()
            .viewerSeen(clock.now(), page(0), protected)
            .viewerScrolled(clock.advance(100), "w:pager", 4)
            .viewerScrolled(clock.seconds(5), "w:pager", 5)
            .viewerSeen(clock.advance(300), feed, protected)
        assertEquals(1, stats.day(OCT_24).reels.watched)
    }

    @Test
    fun `a viewer over midnight is split and counted once`() {
        val night = TestClock(LocalDateTime.of(2026, 10, 24, 23, 59))
        val stats = StatsState()
            .viewerSeen(night.now(), reelsTab, paused)
            .countViewer(night.seconds(30))
            .viewerGone(night.seconds(60))
        assertEquals(60_000L, stats.day(OCT_24).reels.pausedMillis)
        assertEquals(30_000L, stats.day(OCT_25).reels.pausedMillis)
        assertEquals(1, stats.day(OCT_24).reels.sessions)
        assertEquals(0, stats.day(OCT_25).reels.sessions)
        assertEquals(60_000L, stats.day(OCT_24).reels.longestMillis)
        assertEquals(30_000L, stats.day(OCT_25).reels.longestMillis)
    }
}
