package app.scrollhalt.service

import android.content.Context
import app.scrollhalt.data.DeviceClock
import app.scrollhalt.data.StatsRepository
import app.scrollhalt.protect.Clock
import app.scrollhalt.protect.Moment
import app.scrollhalt.stats.StatsState
import app.scrollhalt.stats.StatsStore
import app.scrollhalt.stats.WritePolicy
import app.scrollhalt.stats.adoptReset
import app.scrollhalt.stats.countAll
import app.scrollhalt.stats.serviceStarted
import app.scrollhalt.stats.serviceStopped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The service's usage statistics, kept in memory and changed only through the pure reducers
 * in `stats/`. Writes are batched by [WritePolicy] and checked on events, never on a timer.
 * Inputs before the stored statistics are loaded (a few milliseconds) are dropped.
 *
 * One keeper lives as long as the process ([shared]) and service instances hand it over, so a
 * quick reconnect goes on with the statistics in memory instead of reloading a file the old
 * instance may not have finished writing. Only the instance that started last can stop it.
 */
internal class StatsKeeper(private val clock: Clock, private val store: StatsStore) {
    private var state: StatsState? = null
    private var owner: Any? = null
    private var changed = false
    private var lastWriteElapsed: Long? = null

    /** An Instagram session is counting time. */
    val counting: Boolean get() = state?.live?.instagram?.countedUntil != null

    /**
     * [instance] took over. The first start loads the stored statistics; later ones go on from
     * memory. Then follows the store to adopt a reset made in the app.
     */
    fun start(instance: Any, scope: CoroutineScope) {
        owner = instance
        val current = state
        if (current != null && current.live.trackedUntil == null) {
            state = current.serviceStarted(clock.now())
            changed = true
            write()
        }
        scope.launch { store.stats.collect(::onStored) }
    }

    private fun onStored(stored: StatsState) {
        val current = state
        when {
            current == null -> state = stored.serviceStarted(clock.now())
            stored.generation != current.generation -> state = current.adoptReset(stored, clock.now())
            else -> return
        }
        changed = true
        write()
    }

    /** [instance] stopped; ignored when a newer instance already took over. */
    fun stop(instance: Any) {
        if (owner !== instance) return
        owner = null
        val current = state ?: return
        state = current.serviceStopped(clock.now())
        changed = true
        write()
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
        val counted = current.countAll(now)
        state = counted
        changed = false
        lastWriteElapsed = now.elapsedMillis
        store.save(counted)
    }

    companion object {
        private var shared: StatsKeeper? = null

        /** The process-wide keeper; main thread only, like the service. */
        fun shared(context: Context): StatsKeeper =
            shared ?: StatsKeeper(DeviceClock(context), StatsRepository(context)).also { shared = it }
    }
}
