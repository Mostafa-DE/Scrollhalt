package app.scrollhalt.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DaysTest {
    private fun wall(at: LocalDateTime, zone: ZoneId = AMMAN): Long = at.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `time inside one day is one part`() {
        val parts = splitByDay(wall(LocalDateTime.of(2026, 10, 24, 9, 0)), 30 * MINUTE, AMMAN)
        assertEquals(listOf(DayPart(OCT_24, wall(LocalDateTime.of(2026, 10, 24, 9, 0)), 30 * MINUTE)), parts)
    }

    @Test
    fun `time over midnight is split at local midnight`() {
        val start = wall(LocalDateTime.of(2026, 10, 24, 23, 50))
        val parts = splitByDay(start, 25 * MINUTE, AMMAN)
        assertEquals(2, parts.size)
        assertEquals(DayPart(OCT_24, start, 10 * MINUTE), parts[0])
        assertEquals(DayPart(OCT_25, wall(LocalDateTime.of(2026, 10, 25, 0, 0)), 15 * MINUTE), parts[1])
    }

    @Test
    fun `a 25 hour day at the end of summer time stays one day`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val parts = splitByDay(wall(LocalDateTime.of(2026, 10, 25, 0, 0), berlin), 25 * 60 * MINUTE, berlin)
        assertEquals(listOf(OCT_25), parts.map { it.date })
        assertEquals(25 * 60 * MINUTE, parts.single().millis)
    }

    @Test
    fun `no time no parts`() {
        assertTrue(splitByDay(0, 0, AMMAN).isEmpty())
        assertTrue(splitByDay(0, -5, AMMAN).isEmpty())
    }

    @Test
    fun `elapsed time decides within a boot and the wall clock across boots`() {
        val from = Mark(elapsedMillis = 1_000, wallMillis = 50_000, bootCount = 1)
        assertEquals(4_000L, millisBetween(from, Mark(5_000, 999_999, 1)))
        assertEquals(10_000L, millisBetween(from, Mark(200, 60_000, 2)))
        assertEquals(0L, millisBetween(from, Mark(200, 40_000, 2)))
    }

    @Test
    fun `retention keeps thirty days including today`() {
        val today = LocalDate.of(2026, 10, 24)
        val all = (0L..40L).associate { Days.key(today.minusDays(it)) to DaySummary(trackedMillis = it) } + ("garbage" to DaySummary())
        val kept = all.retain(today)
        assertEquals(30, kept.size)
        assertTrue(Days.key(today) in kept)
        assertTrue(Days.key(today.minusDays(29)) in kept)
        assertTrue(Days.key(today.minusDays(30)) !in kept)
        assertTrue("garbage" !in kept)
    }
}
