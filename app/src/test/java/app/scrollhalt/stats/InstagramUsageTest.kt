package app.scrollhalt.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class InstagramUsageTest {
    private val clock = TestClock()

    @Test
    fun `instagram coming to the front is one opening and one session`() {
        val stats = StatsState().instagramInFront(clock.now())
        assertEquals(InstagramDay(openings = 1, sessions = 1), stats.day(OCT_24).instagram)
    }

    @Test
    fun `screens inside instagram never count as openings`() {
        val first = StatsState().instagramInFront(clock.now())
        val again = (1..50).fold(first) { stats, _ -> stats.instagramInFront(clock.seconds(1)) }
        assertSame(first, again)
        assertEquals(1, again.day(OCT_24).instagram.openings)
    }

    @Test
    fun `time counts until another app comes to the front`() {
        val stats = StatsState()
            .instagramInFront(clock.now())
            .instagramAway(clock.minutes(5))
            .instagramInFront(clock.minutes(1))
            .instagramAway(clock.minutes(2))
        assertEquals(InstagramDay(openings = 2, sessions = 2, timeMillis = 7 * MINUTE, longestMillis = 5 * MINUTE), stats.day(OCT_24).instagram)
    }

    @Test
    fun `a return within the grace gap continues the session without the time away`() {
        val stats = StatsState()
            .instagramInFront(clock.now())
            .instagramAway(clock.minutes(5))
            .instagramInFront(clock.seconds(10))
            .instagramAway(clock.minutes(2))
        assertEquals(InstagramDay(openings = 1, sessions = 1, timeMillis = 7 * MINUTE, longestMillis = 7 * MINUTE), stats.day(OCT_24).instagram)
    }

    @Test
    fun `a return after the grace gap is a new opening`() {
        val stats = StatsState()
            .instagramInFront(clock.now())
            .instagramAway(clock.minutes(1))
            .instagramInFront(clock.seconds(11))
        assertEquals(2, stats.day(OCT_24).instagram.openings)
    }

    @Test
    fun `counting twice does not count twice`() {
        val start = StatsState().instagramInFront(clock.now())
        val later = clock.minutes(3)
        val once = start.countInstagram(later)
        assertEquals(once.days, once.countInstagram(later).days)
        assertEquals(3 * MINUTE, once.instagramAway(later).day(OCT_24).instagram.timeMillis)
    }

    @Test
    fun `leaving again while away changes nothing`() {
        val away = StatsState().instagramInFront(clock.now()).instagramAway(clock.minutes(1))
        assertSame(away, away.instagramAway(clock.minutes(1)))
        val empty = StatsState()
        assertSame(empty, empty.instagramAway(clock.now()))
    }

    @Test
    fun `a session over midnight is split and opens on its first day`() {
        val night = TestClock(LocalDateTime.of(2026, 10, 24, 23, 55))
        val stats = StatsState().instagramInFront(night.now()).instagramAway(night.minutes(15))
        assertEquals(InstagramDay(openings = 1, sessions = 1, timeMillis = 5 * MINUTE, longestMillis = 5 * MINUTE), stats.day(OCT_24).instagram)
        assertEquals(InstagramDay(openings = 0, sessions = 1, timeMillis = 10 * MINUTE, longestMillis = 10 * MINUTE), stats.day(OCT_25).instagram)
    }

    @Test
    fun `a session counted in steps over midnight keeps one session per day`() {
        val night = TestClock(LocalDateTime.of(2026, 10, 24, 23, 50))
        val stats = StatsState()
            .instagramInFront(night.now())
            .countInstagram(night.minutes(5))
            .countInstagram(night.minutes(10))
            .countInstagram(night.minutes(10))
            .instagramAway(night.minutes(5))
        assertEquals(10 * MINUTE, stats.day(OCT_24).instagram.timeMillis)
        assertEquals(InstagramDay(openings = 0, sessions = 1, timeMillis = 20 * MINUTE, longestMillis = 20 * MINUTE), stats.day(OCT_25).instagram)
    }

    @Test
    fun `longest is the longest session of the day`() {
        val stats = StatsState()
            .instagramInFront(clock.now())
            .instagramAway(clock.minutes(12))
            .instagramInFront(clock.minutes(30))
            .instagramAway(clock.minutes(4))
        assertEquals(12 * MINUTE, stats.day(OCT_24).instagram.longestMillis)
    }

    @Test
    fun `a time zone change keeps the total and files new time under the new zone`() {
        val travel = TestClock(LocalDateTime.of(2026, 10, 24, 23, 50))
        val started = StatsState().instagramInFront(travel.now()).countInstagram(travel.minutes(5))
        travel.zone = ZoneId.of("Asia/Tokyo")
        val stats = started.instagramAway(travel.minutes(20))
        assertEquals(5 * MINUTE, stats.day(OCT_24).instagram.timeMillis)
        assertEquals(20 * MINUTE, stats.day(OCT_25).instagram.timeMillis)
        assertEquals(25 * MINUTE, stats.days.values.sumOf { it.instagram.timeMillis })
    }

    @Test
    fun `a time zone change back and forth keeps one session per day`() {
        // Codex 9: going 25th -> 24th -> 25th counted the session twice on the 25th and
        // restarted its length there.
        val travel = TestClock(LocalDateTime.of(2026, 10, 25, 0, 10))
        val first = StatsState().instagramInFront(travel.now()).countInstagram(travel.minutes(5))
        travel.zone = ZoneOffset.UTC
        val back = first.countInstagram(travel.minutes(5))
        travel.zone = AMMAN
        val stats = back.instagramAway(travel.minutes(5))
        assertEquals(InstagramDay(openings = 1, sessions = 1, timeMillis = 10 * MINUTE, longestMillis = 10 * MINUTE), stats.day(OCT_25).instagram)
        assertEquals(InstagramDay(sessions = 1, timeMillis = 5 * MINUTE, longestMillis = 5 * MINUTE), stats.day(OCT_24).instagram)
    }

    @Test
    fun `away time is never counted, even across a wall clock jump`() {
        val away = StatsState().instagramInFront(clock.now()).instagramAway(clock.minutes(2))
        clock.wall -= 3_600_000
        val back = away.instagramInFront(clock.seconds(5))
        assertNull(back.live.instagram?.awaySince)
        assertEquals(3 * MINUTE, back.instagramAway(clock.minutes(1)).day(OCT_24).instagram.timeMillis)
    }
}
