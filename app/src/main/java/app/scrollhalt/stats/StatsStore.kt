package app.scrollhalt.stats

import kotlinx.coroutines.flow.Flow

/** Where the statistics are kept: an app-private DataStore file in the app, a fake in tests. */
interface StatsStore {
    val stats: Flow<StatsState>

    /** Writes [state] in the background, unless the stored statistics were reset meanwhile. */
    fun save(state: StatsState)
}
