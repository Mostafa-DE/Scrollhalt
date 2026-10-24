package app.scrollhalt.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide flag: true while a [ScrollhaltService] instance is connected. */
object ServiceState {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private var owner: Any? = null

    /**
     * [instance] connected ([value] true) or stopped (false). Android can connect a new
     * instance before it destroys the old one, so a stop only counts from the current one.
     */
    @Synchronized
    internal fun setConnected(value: Boolean, instance: Any) {
        if (value) {
            owner = instance
        } else {
            if (owner !== instance) return
            owner = null
        }
        _connected.value = value
    }
}
