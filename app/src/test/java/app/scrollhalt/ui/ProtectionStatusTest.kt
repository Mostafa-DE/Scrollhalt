package app.scrollhalt.ui

import app.scrollhalt.protect.ProtectionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectionStatusTest {
    @Test
    fun `service off or not connected needs setup whatever the mode`() {
        ProtectionMode.entries.forEach { mode ->
            assertEquals(ProtectionStatus.SERVICE_UNAVAILABLE, protectionStatus(enabled = false, connected = false, mode = mode))
            assertEquals(ProtectionStatus.SERVICE_UNAVAILABLE, protectionStatus(enabled = true, connected = false, mode = mode))
        }
    }

    @Test
    fun `running service shows the protection mode`() {
        assertEquals(ProtectionStatus.ACTIVE, protectionStatus(true, true, ProtectionMode.PROTECTED))
        assertEquals(ProtectionStatus.PAUSED, protectionStatus(true, true, ProtectionMode.PAUSED))
        assertEquals(ProtectionStatus.DISABLED, protectionStatus(true, true, ProtectionMode.DISABLED))
    }
}
