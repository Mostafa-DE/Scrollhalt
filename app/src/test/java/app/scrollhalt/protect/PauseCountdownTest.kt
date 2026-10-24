package app.scrollhalt.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PauseCountdownTest {
    @Test
    fun `the countdown covers only the last 10 s`() {
        assertNull(PauseCountdown.seconds(10_001))
        assertEquals(10, PauseCountdown.seconds(10_000))
        assertNull(PauseCountdown.seconds(0))
        assertNull(PauseCountdown.seconds(-5))
    }

    @Test
    fun `seconds are rounded up so 1 is shown until the end`() {
        assertEquals(10, PauseCountdown.seconds(9_001))
        assertEquals(9, PauseCountdown.seconds(9_000))
        assertEquals(1, PauseCountdown.seconds(1))
        assertEquals((10 downTo 1).toList(), (10 downTo 1).map { PauseCountdown.seconds(it * 1_000L) })
    }

    @Test
    fun `the next tick comes when the shown second changes`() {
        assertEquals(400L, PauseCountdown.nextTickMillis(9_400))
        assertEquals(1_000L, PauseCountdown.nextTickMillis(9_000))
        assertEquals(1L, PauseCountdown.nextTickMillis(1))
    }

    @Test
    fun `before the window the first tick comes at its start`() {
        assertEquals(50_000L, PauseCountdown.nextTickMillis(60_000))
        assertEquals(1L, PauseCountdown.nextTickMillis(10_001))
    }
}
