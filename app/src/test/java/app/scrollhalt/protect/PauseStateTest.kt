package app.scrollhalt.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class PauseStateTest {
    private val zone = ZoneId.of("Asia/Amman")
    private val start = Moment(elapsedMillis = 50_000, wallMillis = 1_000_000_000, bootCount = 7, zone = zone)
    private val fiveMinutes = 5 * 60_000L

    private fun later(millis: Long, from: Moment = start): Moment =
        from.copy(elapsedMillis = from.elapsedMillis + millis, wallMillis = from.wallMillis + millis)

    @Test
    fun `a new pause runs for its whole duration`() {
        val pause = PauseState.start(start, fiveMinutes)
        assertTrue(pause.isActive(start))
        assertEquals(fiveMinutes, pause.remaining(start))
        assertEquals(start.wallMillis + fiveMinutes, pause.endWallMillis)
        assertEquals(start.elapsedMillis + fiveMinutes, pause.endElapsedMillis)
        assertEquals(7, pause.bootCount)
    }

    @Test
    fun `remaining counts down and the pause expires on time`() {
        val pause = PauseState.start(start, fiveMinutes)
        assertEquals(60_000L, pause.remaining(later(4 * 60_000)))
        assertTrue(pause.isActive(later(fiveMinutes - 1)))
        assertFalse(pause.isActive(later(fiveMinutes)))
        assertEquals(0L, pause.remaining(later(fiveMinutes + 90_000)))
    }

    @Test
    fun `same boot trusts elapsed time over a wall clock jump`() {
        val pause = PauseState.start(start, fiveMinutes)
        val wallJumpedAhead = later(60_000).copy(wallMillis = start.wallMillis + 3_600_000)
        assertEquals(4 * 60_000L, pause.remaining(wallJumpedAhead))
    }

    @Test
    fun `process recreated mid pause keeps the same end`() {
        // The service and the UI read the stored pause again: same boot, elapsed time went on.
        val stored = PauseState.start(start, fiveMinutes)
        val restored = PauseState(stored.endWallMillis, stored.endElapsedMillis, stored.bootCount, stored.durationMillis)
        assertEquals(stored.remaining(later(120_000)), restored.remaining(later(120_000)))
        assertEquals(3 * 60_000L, restored.remaining(later(120_000)))
    }

    @Test
    fun `after a reboot the wall clock decides`() {
        val pause = PauseState.start(start, fiveMinutes)
        val rebooted = Moment(elapsedMillis = 20_000, wallMillis = start.wallMillis + 2 * 60_000, bootCount = 8, zone = zone)
        assertEquals(3 * 60_000L, pause.remaining(rebooted))
    }

    @Test
    fun `reboot after expiry resumes protection`() {
        val pause = PauseState.start(start, fiveMinutes)
        val rebooted = Moment(elapsedMillis = 20_000, wallMillis = start.wallMillis + 10 * 60_000, bootCount = 8, zone = zone)
        assertFalse(pause.isActive(rebooted))
    }

    @Test
    fun `after a reboot remaining is capped at the original duration`() {
        // The wall clock went back an hour while the phone was off.
        val pause = PauseState.start(start, fiveMinutes)
        val rebooted = Moment(elapsedMillis = 20_000, wallMillis = start.wallMillis - 3_600_000, bootCount = 8, zone = zone)
        assertEquals(fiveMinutes, pause.remaining(rebooted))
    }

    @Test
    fun `elapsed time going backwards means another boot even with an unknown boot count`() {
        val unknownBoot = start.copy(bootCount = -1)
        val pause = PauseState.start(unknownBoot, fiveMinutes)
        val rebooted = Moment(elapsedMillis = 1_000, wallMillis = start.wallMillis + 6 * 60_000, bootCount = -1, zone = zone)
        assertFalse(pause.isActive(rebooted))
    }

    @Test
    fun `after a reboot with the clock set back the rebased pause ends after its remaining time`() {
        // Codex P2: the cap was applied again on every call, so the pause lasted until the old
        // wall-clock deadline (an hour and five minutes here).
        val pause = PauseState.start(start, fiveMinutes)
        val booted = Moment(elapsedMillis = 10_000, wallMillis = start.wallMillis - 3_600_000, bootCount = 8, zone = zone)
        val rebased = pause.rebased(booted)
        assertTrue(rebased.isOnBootOf(booted))
        assertEquals(fiveMinutes, rebased.remaining(booted))
        assertEquals(60_000L, rebased.remaining(later(4 * 60_000, booted)))
        assertFalse(rebased.isActive(later(fiveMinutes, booted)))
    }

    @Test
    fun `a rebase keeps what remained and the identity of the pause`() {
        val pause = PauseState.start(start, fiveMinutes)
        val booted = Moment(elapsedMillis = 10_000, wallMillis = start.wallMillis + 2 * 60_000, bootCount = 8, zone = zone)
        val rebased = pause.rebased(booted)
        assertEquals(3 * 60_000L, rebased.remaining(booted))
        assertEquals(pause.startedWallMillis, rebased.startedWallMillis)
        assertEquals(fiveMinutes, rebased.durationMillis)
        assertEquals(8, rebased.bootCount)
    }

    @Test
    fun `a pause is not rebased on its own boot`() {
        val pause = PauseState.start(start, fiveMinutes)
        assertTrue(pause.isOnBootOf(later(60_000)))
        assertSame(pause, pause.rebased(later(60_000)))
    }

    @Test
    fun `a pause that ended before the reboot stays ended when rebased`() {
        val pause = PauseState.start(start, fiveMinutes)
        val booted = Moment(elapsedMillis = 10_000, wallMillis = start.wallMillis + 9 * 60_000, bootCount = 8, zone = zone)
        assertFalse(pause.rebased(booted).isActive(booted))
    }

    @Test
    fun `a stored pause without a start is identified by its end minus its duration`() {
        val stored = PauseState(endWallMillis = 900_000, endElapsedMillis = 5_000, bootCount = 1, durationMillis = 300_000)
        assertEquals(600_000L, stored.startedWallMillis)
        assertEquals(start.wallMillis, PauseState.start(start, fiveMinutes).startedWallMillis)
    }

    @Test
    fun `durations offered in the pause sheet`() {
        assertEquals(listOf(1, 5, 10, 15), PauseState.DURATION_MINUTES)
    }
}
