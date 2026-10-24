package app.scrollhalt.stats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritePolicyTest {
    @Test
    fun `changes are written at most every 30 s`() {
        assertFalse(WritePolicy.due(lastWriteElapsed = 0, nowElapsed = 29_999, changed = true, inInstagram = false))
        assertTrue(WritePolicy.due(lastWriteElapsed = 0, nowElapsed = 30_000, changed = true, inInstagram = false))
    }

    @Test
    fun `last seen alone is written at most every 60 s`() {
        assertFalse(WritePolicy.due(lastWriteElapsed = 0, nowElapsed = 59_999, changed = false, inInstagram = true))
        assertTrue(WritePolicy.due(lastWriteElapsed = 0, nowElapsed = 60_000, changed = false, inInstagram = true))
    }

    @Test
    fun `nothing to write outside instagram without changes`() {
        assertFalse(WritePolicy.due(lastWriteElapsed = 0, nowElapsed = 3_600_000, changed = false, inInstagram = false))
    }

    @Test
    fun `the first change is written at once`() {
        assertTrue(WritePolicy.due(lastWriteElapsed = null, nowElapsed = 10, changed = true, inInstagram = false))
    }
}
