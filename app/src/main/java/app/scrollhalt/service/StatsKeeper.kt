package app.scrollhalt.service

import app.scrollhalt.protect.Clock
import app.scrollhalt.protect.Moment
import app.scrollhalt.stats.StatsState
import app.scrollhalt.stats.WritePolicy
import app.scrollhalt.stats.countAll
import app.scrollhalt.stats.serviceStarted
import app.scrollhalt.stats.serviceStopped

/**
 * The service's usage statistics, kept in memory and changed only through the pure reducers
 * in `stats/`. Writes are batched by [WritePolicy] and checked on events, never on a timer.
 */
internal class StatsKeeper(private val clock: Clock) {
    private var state: StatsState? = null
    private var changed = false
    private var lastWriteElapsed: Long? = null

    /** An Instagram session is counting time. */
    val counting: Boolean get() = state?.live?.instagram?.countedUntil != null

    fun start() {
        state = StatsState().serviceStarted(clock.now())
        write()
    }

    fun stop() {
        val current = state ?: return
        state = current.serviceStopped(clock.now())
        changed = true
        write()
        state = null
    }

    /** Applies [change]; true when it changed anything. Ignored until the statistics are loaded. */
    fun update(change: StatsState.(Moment) -> StatsState): Boolean {
        val current = state ?: return false
        val next = current.change(clock.now())
        if (next === current) return false
        state = next
        changed = true
        return true
    }

    /** Writes if [WritePolicy] says it is time. */
    fun maybeWrite() {
        if (WritePolicy.due(lastWriteElapsed, clock.now().elapsedMillis, changed, counting)) write()
    }

    /** Counts everything up to now and writes it. */
    fun write() {
        val current = state ?: return
        val now = clock.now()
        state = current.countAll(now)
        changed = false
        lastWriteElapsed = now.elapsedMillis
    }
}
