package app.scrollhalt.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardTest {
    private val today = OCT_24

    @Test
    fun `the week ends today and marks days without statistics as not tracked`() {
        val day = DaySummary(
            instagram = InstagramDay(openings = 4, sessions = 5, timeMillis = 30 * MINUTE),
            reels = ReelsDay(protectedMillis = MINUTE, pausedMillis = 2 * MINUTE),
            blocked = BlockedDay(reelsTab = Outcome(detected = 3), nextReel = Outcome(detected = 1)),
        )
        val state = StatsState(days = mapOf(Days.key(today) to day, Days.key(today.minusDays(2)) to DaySummary()))
        val board = dashboard(state, today)
        assertEquals(day, board.today)
        assertEquals((6L downTo 0L).map { today.minusDays(it) }, board.week.map { it.date })
        assertEquals(listOf(false, false, false, false, true, false, true), board.week.map { it.tracked })
        assertEquals(DayBar(today, tracked = true, instagramMillis = 30 * MINUTE, opens = 4, reelsMillis = 3 * MINUTE, blocked = 4), board.week.last())
    }

    @Test
    fun `no statistics today`() {
        assertNull(dashboard(StatsState(), today).today)
    }

    @Test
    fun `chart metrics read their value`() {
        val bar = DayBar(today, true, instagramMillis = 5, opens = 6, reelsMillis = 7, blocked = 8)
        assertEquals(listOf(5L, 6L, 7L, 8L), ChartMetric.entries.map { it.of(bar) })
    }

    @Test
    fun `average session length`() {
        assertEquals(6 * MINUTE, InstagramDay(sessions = 5, timeMillis = 30 * MINUTE).averageMillis())
        assertEquals(0L, InstagramDay().averageMillis())
    }

    @Test
    fun `hours and minutes round down`() {
        assertEquals(HoursMinutes(1, 12), hoursMinutes(72 * MINUTE + 59_999))
        assertEquals(HoursMinutes(0, 0), hoursMinutes(59_999))
        assertEquals(HoursMinutes(0, 0), hoursMinutes(-5))
    }
}
