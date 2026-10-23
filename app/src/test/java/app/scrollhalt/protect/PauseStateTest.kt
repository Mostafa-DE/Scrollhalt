package app.scrollhalt.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `durations offered in the pause sheet`() {
        assertEquals(listOf(1, 5, 10, 15), PauseState.DURATION_MINUTES)
    }
}
