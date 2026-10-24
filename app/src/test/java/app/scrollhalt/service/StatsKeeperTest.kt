package app.scrollhalt.service

import app.scrollhalt.stats.MINUTE
import app.scrollhalt.stats.OCT_24
import app.scrollhalt.stats.StatsState
import app.scrollhalt.stats.StatsStore
import app.scrollhalt.stats.TestClock
import app.scrollhalt.stats.day
import app.scrollhalt.stats.instagramAway
import app.scrollhalt.stats.instagramInFront
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsKeeperTest {
    /** Saves land only when [flush] runs, like DataStore writes on their own thread. */
    private class SlowStore : StatsStore {
        private val stored = MutableStateFlow(StatsState())
        private val queue = mutableListOf<StatsState>()
        override val stats: StateFlow<StatsState> = stored

        override fun save(state: StatsState) {
            queue += state
        }

        fun flush() {
            queue.forEach { saved -> stored.value = if (saved.generation == stored.value.generation) saved else stored.value }
            queue.clear()
        }

        val latest: StatsState get() = stored.value
    }

    private val clock = TestClock()
    private val store = SlowStore()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun stopCollecting() {
        scope.cancel()
    }

    @Test
    fun `a quick reconnect keeps what the old instance had not written yet`() {
        // Codex 3: a new keeper loaded the store before the old keeper's last save landed,
        // then wrote that stale copy over it.
        val keeper = StatsKeeper(clock, store)
        val old = Any()
        keeper.start(old, scope)
        keeper.update { instagramInFront(it) }
        clock.minutes(5)
        keeper.update { instagramAway(it) }
        keeper.stop(old)
        val new = Any()
        keeper.start(new, scope)
        keeper.write()
        store.flush()
        assertEquals(5 * MINUTE, store.latest.day(OCT_24).instagram.timeMillis)
    }

    @Test
    fun `an old instance stopping after the new one started changes nothing`() {
        val keeper = StatsKeeper(clock, store)
        val old = Any()
        val new = Any()
        keeper.start(old, scope)
        keeper.update { instagramInFront(it) }
        keeper.start(new, scope)
        clock.minutes(3)
        keeper.stop(old)
        assertEquals(true, keeper.counting)
        keeper.stop(new)
        store.flush()
        assertEquals(3 * MINUTE, store.latest.day(OCT_24).instagram.timeMillis)
        assertEquals(3 * MINUTE, store.latest.day(OCT_24).trackedMillis)
    }

    @Test
    fun `a reconnect after a stop tracks again`() {
        val keeper = StatsKeeper(clock, store)
        val first = Any()
        keeper.start(first, scope)
        clock.minutes(2)
        keeper.stop(first)
        clock.minutes(10)
        keeper.start(Any(), scope)
        clock.minutes(1)
        keeper.write()
        store.flush()
        assertEquals(3 * MINUTE, store.latest.day(OCT_24).trackedMillis)
    }
}
