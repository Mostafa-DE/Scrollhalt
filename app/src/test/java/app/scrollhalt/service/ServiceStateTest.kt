package app.scrollhalt.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStateTest {
    @Test
    fun `connected until the instance stops`() {
        val service = Any()
        ServiceState.setConnected(true, service)
        assertTrue(ServiceState.connected.value)
        ServiceState.setConnected(false, service)
        assertFalse(ServiceState.connected.value)
    }

    @Test
    fun `an old instance stopping after a new one connected keeps the status`() {
        val old = Any()
        val new = Any()
        ServiceState.setConnected(true, old)
        ServiceState.setConnected(true, new)
        ServiceState.setConnected(false, old)
        ServiceState.setConnected(false, old)
        assertTrue(ServiceState.connected.value)
        ServiceState.setConnected(false, new)
        assertFalse(ServiceState.connected.value)
    }

    @Test
    fun `an instance that never connected changes nothing`() {
        val service = Any()
        ServiceState.setConnected(true, service)
        ServiceState.setConnected(false, Any())
        assertTrue(ServiceState.connected.value)
        ServiceState.setConnected(false, service)
    }
}
