package app.scrollhalt.stats

import app.scrollhalt.detect.BlockCategory
import app.scrollhalt.detect.Classification
import app.scrollhalt.detect.PolicyEvent
import app.scrollhalt.detect.Screen
import app.scrollhalt.protect.Moment
import app.scrollhalt.protect.ProtectionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

private const val PAUSE_ID: Long = 1_792_000_000_000

class TrackingTest {
    private val clock = TestClock()
    private val reelsTab = Classification(Screen.REELS_TAB, tabBarVisible = true, selectedTab = "clips_tab")

    @Test
    fun `a running service marks the day tracked from its start`() {
        val stats = StatsState().serviceStarted(clock.now())
        assertEquals(clock.wall, stats.day(OCT_24).trackedSinceWall)
        val counted = stats.countAll(clock.minutes(90))
        assertEquals(90 * MINUTE, counted.day(OCT_24).trackedMillis)
        assertEquals(stats.day(OCT_24).trackedSinceWall, counted.day(OCT_24).trackedSinceWall)
    }

    @Test
    fun `tracking through midnight tracks the next day from midnight`() {
        val night = TestClock(LocalDateTime.of(2026, 10, 24, 23, 0))
        val stats = StatsState().serviceStarted(night.now()).countAll(night.minutes(120))
        assertEquals(60 * MINUTE, stats.day(OCT_24).trackedMillis)
        assertEquals(60 * MINUTE, stats.day(OCT_25).trackedMillis)
        assertEquals(OCT_25.atStartOfDay(AMMAN).toInstant().toEpochMilli(), stats.day(OCT_25).trackedSinceWall)
    }

    @Test
    fun `days without the service are not tracked`() {
        val stats = StatsState().serviceStarted(clock.now()).serviceStopped(clock.minutes(5))
        assertTrue(Days.key(OCT_25) !in stats.days)
        assertNull(stats.live.trackedUntil)
    }

    @Test
    fun `process death closes open sessions at the last write`() {
        val before = StatsState()
            .serviceStarted(clock.now())
            .instagramInFront(clock.now())
            .viewerSeen(clock.now(), reelsTab, ProtectionMode.PAUSED)
            .countAll(clock.minutes(4))
        // The process dies here; a new one starts ten minutes later.
        val after = before.serviceStarted(clock.minutes(10))
        assertEquals(4 * MINUTE, after.day(OCT_24).instagram.timeMillis)
        assertEquals(4 * MINUTE, after.day(OCT_24).reels.pausedMillis)
        assertEquals(1, after.day(OCT_24).reels.sessions)
        assertEquals(4 * MINUTE, after.day(OCT_24).trackedMillis)
        assertNull(after.live.instagram)
        assertNull(after.live.viewer)
        assertEquals(clock.now().mark(), after.live.trackedUntil)
        // The next time Instagram comes to the front is a new opening.
        assertEquals(2, after.instagramInFront(clock.now()).day(OCT_24).instagram.openings)
    }

    @Test
    fun `recovery after a reboot adds nothing past the last write`() {
        val before = StatsState().serviceStarted(clock.now()).instagramInFront(clock.now()).countAll(clock.minutes(3))
        val after = before.serviceStarted(clock.reboot(offMillis = 60 * MINUTE))
        assertEquals(3 * MINUTE, after.day(OCT_24).instagram.timeMillis)
        assertEquals(3 * MINUTE, after.day(OCT_24).trackedMillis)
    }

    @Test
    fun `recovery forgets attempts waiting for an outcome`() {
        val before = StatsState().policyEvents(clock.now(), listOf(PolicyEvent.Attempt(BlockCategory.EXPLORE_REEL)))
        val after = before.serviceStarted(clock.seconds(30))
        assertSame(after, after.policyEvents(clock.now(), listOf(PolicyEvent.Confirmed(BlockCategory.EXPLORE_REEL))))
    }

    @Test
    fun `stopping the service counts and closes everything`() {
        val stats = StatsState()
            .serviceStarted(clock.now())
            .instagramInFront(clock.now())
            .viewerSeen(clock.now(), reelsTab, ProtectionMode.DISABLED)
            .serviceStopped(clock.minutes(2))
        assertEquals(2 * MINUTE, stats.day(OCT_24).instagram.timeMillis)
        assertEquals(2 * MINUTE, stats.day(OCT_24).reels.disabledMillis)
        assertNull(stats.live.viewer)
        assertNull(stats.live.instagram?.countedUntil)
    }

    /** The end of a pause [millis] after [from], on the same boot. */
    private fun endAfter(from: Moment, millis: Long): Mark = Mark(from.elapsedMillis + millis, from.wallMillis + millis, from.bootCount)

    @Test
    fun `a pause is counted once and its time when it ends`() {
        val start = clock.now()
        val end = endAfter(start, 5 * MINUTE)
        val running = StatsState().pauseStarted(start, PAUSE_ID, end)
        assertSame(running, running.pauseStarted(clock.minutes(1), PAUSE_ID, end))
        val ended = running.pauseEnded(clock.minutes(1))
        assertEquals(1, ended.day(OCT_24).pausesStarted)
        assertEquals(2 * MINUTE, ended.day(OCT_24).pausedMillis)
        assertNull(ended.live.openPause)
    }

    @Test
    fun `an expired pause counts its full duration`() {
        val start = clock.now()
        val ended = StatsState().pauseStarted(start, PAUSE_ID, endAfter(start, 5 * MINUTE)).pauseEnded(clock.minutes(30))
        assertEquals(5 * MINUTE, ended.day(OCT_24).pausedMillis)
    }

    @Test
    fun `a pause survives recovery and a new pause ends the old one`() {
        val start = clock.now()
        val running = StatsState().pauseStarted(start, PAUSE_ID, endAfter(start, 10 * MINUTE))
        val recovered = running.serviceStarted(clock.minutes(2))
        assertEquals(running.live.openPause, recovered.live.openPause)
        val replaced = recovered.pauseStarted(clock.minutes(1), PAUSE_ID + 1, endAfter(clock.now(), 5 * MINUTE))
        assertEquals(2, replaced.day(OCT_24).pausesStarted)
        assertEquals(3 * MINUTE, replaced.day(OCT_24).pausedMillis)
    }

    @Test
    fun `the same pause with a new end is not a new pause`() {
        val start = clock.now()
        val running = StatsState().pauseStarted(start, PAUSE_ID, endAfter(start, 10 * MINUTE))
        val moved = running.pauseStarted(clock.minutes(1), PAUSE_ID, endAfter(start, 4 * MINUTE))
        val ended = moved.pauseEnded(clock.minutes(9))
        assertEquals(1, ended.day(OCT_24).pausesStarted)
        assertEquals(4 * MINUTE, ended.day(OCT_24).pausedMillis)
    }

    @Test
    fun `a pause over midnight is split`() {
        val night = TestClock(LocalDateTime.of(2026, 10, 24, 23, 58))
        val start = night.now()
        val ended = StatsState().pauseStarted(start, PAUSE_ID, endAfter(start, 5 * MINUTE)).pauseEnded(night.minutes(10))
        assertEquals(2 * MINUTE, ended.day(OCT_24).pausedMillis)
        assertEquals(3 * MINUTE, ended.day(OCT_25).pausedMillis)
        assertEquals(1, ended.day(OCT_24).pausesStarted)
    }

    @Test
    fun `a wall clock change during a pause keeps its elapsed time`() {
        // Codex 10: the paused time came from wall clocks and became 0 after the clock went back.
        val start = clock.now()
        val running = StatsState().pauseStarted(start, PAUSE_ID, endAfter(start, 10 * MINUTE))
        clock.minutes(2)
        clock.wall -= 60 * MINUTE
        val ended = running.pauseEnded(clock.minutes(1))
        assertEquals(3 * MINUTE, ended.days.values.sumOf { it.pausedMillis })
    }

    @Test
    fun `a pause that ended before a reboot counts up to its end`() {
        val start = clock.now()
        val running = StatsState().pauseStarted(start, PAUSE_ID, endAfter(start, 5 * MINUTE))
        val ended = running.pauseEnded(clock.reboot(offMillis = 20 * MINUTE))
        assertEquals(5 * MINUTE, ended.day(OCT_24).pausedMillis)
    }

    @Test
    fun `reset during a pause counts it once, from the reset`() {
        // Codex 4: reset moved the stored start, the service then took the same pause for a new one.
        val start = clock.now()
        val end = endAfter(start, 10 * MINUTE)
        val reset = StatsState().serviceStarted(start).pauseStarted(start, PAUSE_ID, end).reset(clock.minutes(5), tracking = true)
        val later = reset.pauseStarted(clock.minutes(1), PAUSE_ID, end).pauseEnded(clock.minutes(4))
        assertEquals(0, later.day(OCT_24).pausesStarted)
        assertEquals(5 * MINUTE, later.day(OCT_24).pausedMillis)
    }

    @Test
    fun `reset clears every day and keeps what runs`() {
        val start = clock.now()
        val before = StatsState()
            .serviceStarted(start)
            .pauseStarted(start, PAUSE_ID, endAfter(start, 10 * MINUTE))
            .instagramInFront(start)
            .countAll(clock.minutes(5))
        val reset = before.reset(clock.now(), tracking = true)
        assertNotEquals(before.generation, reset.generation)
        assertEquals(setOf(Days.key(OCT_24)), reset.days.keys)
        assertEquals(DaySummary(trackedSinceWall = clock.wall), reset.day(OCT_24))
        assertEquals(clock.now().mark(), reset.live.openPause?.countedUntil)
        val later = reset.countAll(clock.minutes(1)).pauseEnded(clock.now())
        assertEquals(MINUTE, later.day(OCT_24).instagram.timeMillis)
        assertEquals(1, later.day(OCT_24).instagram.sessions)
        assertEquals(MINUTE, later.day(OCT_24).trackedMillis)
        assertEquals(MINUTE, later.day(OCT_24).pausedMillis)
        assertTrue(StatsState().reset(clock.now(), tracking = false).days.isEmpty())
    }

    @Test
    fun `the service adopts a reset made by the app`() {
        val service = StatsState().serviceStarted(clock.now()).instagramInFront(clock.now()).countAll(clock.minutes(5))
        val stored = service.reset(clock.seconds(10), tracking = true)
        val adopted = service.countAll(clock.seconds(5)).adoptReset(stored, clock.now())
        assertEquals(stored.generation, adopted.generation)
        assertEquals(stored.days, adopted.days)
        val next = adopted.countAll(clock.minutes(2))
        assertEquals(2 * MINUTE, next.day(OCT_24).instagram.timeMillis)
        assertEquals(2 * MINUTE, next.day(OCT_24).trackedMillis)
    }

    @Test
    fun `writing drops days past retention`() {
        val old = StatsState(days = mapOf(Days.key(OCT_24.minusDays(40)) to DaySummary(trackedMillis = 1)))
        assertTrue(old.countAll(clock.now()).days.isEmpty())
    }

    @Test
    fun `one day adds up`() {
        // 09:00 service on; 09:00-09:10 Instagram with 2 min of paused Reels; 3 blocked attempts.
        val start = clock.now()
        var stats = StatsState().serviceStarted(start).instagramInFront(start)
        stats = stats.pauseStarted(clock.minutes(1), PAUSE_ID, endAfter(clock.now(), MINUTE))
            .viewerSeen(clock.now(), reelsTab, ProtectionMode.PAUSED)
            .viewerGone(clock.minutes(1))
            .pauseEnded(clock.now())
            .viewerSeen(clock.now(), reelsTab, ProtectionMode.PAUSED)
            .viewerMode(clock.minutes(1), ProtectionMode.PROTECTED)
            .viewerGone(clock.advance(300))
        repeat(3) { stats = stats.coverTapped(clock.seconds(30)) }
        val tenPast = clock.advance(start.wallMillis + 10 * MINUTE - clock.wall)
        stats = stats.instagramAway(tenPast).countAll(tenPast)
        val day = stats.day(OCT_24)
        assertEquals(InstagramDay(openings = 1, sessions = 1, timeMillis = 10 * MINUTE, longestMillis = 10 * MINUTE), day.instagram)
        assertEquals(2 * MINUTE, day.reels.pausedMillis)
        assertEquals(300L, day.reels.protectedMillis)
        assertEquals(2, day.reels.sessions)
        assertEquals(3, day.blocked.total)
        assertEquals(1, day.pausesStarted)
        assertEquals(MINUTE, day.pausedMillis)
        assertEquals(10 * MINUTE, day.trackedMillis)
    }
}
