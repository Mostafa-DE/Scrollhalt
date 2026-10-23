package app.scrollhalt.protect

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

class ProtectionModeTest {
    private val now = Moment(10_000, 1_000_000, 1, ZoneOffset.UTC)
    private val pause = PauseState.start(now, 60_000)

    @Test
    fun `blocking on without a pause protects`() {
        assertEquals(ProtectionMode.PROTECTED, protectionMode(true, null, now))
    }

    @Test
    fun `an active pause pauses`() {
        assertEquals(ProtectionMode.PAUSED, protectionMode(true, pause, now))
    }

    @Test
    fun `an expired pause protects again`() {
        val later = now.copy(elapsedMillis = now.elapsedMillis + 60_000, wallMillis = now.wallMillis + 60_000)
        assertEquals(ProtectionMode.PROTECTED, protectionMode(true, pause, later))
    }

    @Test
    fun `block reels off wins over a pause`() {
        assertEquals(ProtectionMode.DISABLED, protectionMode(false, pause, now))
        assertEquals(ProtectionMode.DISABLED, protectionMode(false, null, now))
    }
}
