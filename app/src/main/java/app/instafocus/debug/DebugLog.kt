package app.instafocus.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DebugEntry(
    val timeMillis: Long,
    val eventType: String,
    val screen: String,
    val decision: String,
    val reason: String,
)

/**
 * In-memory only: the last [MAX_ENTRIES] decisions and, when debug capture is on,
 * the resource ids visible in the last scan. Never written to disk or sent anywhere.
 * Snapshots hold view ids only: no text, content descriptions or usernames.
 */
object DebugLog {
    private const val MAX_ENTRIES: Int = 200

    private val _entries = MutableStateFlow<List<DebugEntry>>(emptyList())
    val entries: StateFlow<List<DebugEntry>> = _entries.asStateFlow()

    private val _snapshot = MutableStateFlow<List<String>>(emptyList())
    val snapshot: StateFlow<List<String>> = _snapshot.asStateFlow()

    fun add(entry: DebugEntry) {
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }

    fun setSnapshot(visibleIds: List<String>) {
        _snapshot.value = visibleIds
    }

    fun clear() {
        _entries.value = emptyList()
        _snapshot.value = emptyList()
    }
}
