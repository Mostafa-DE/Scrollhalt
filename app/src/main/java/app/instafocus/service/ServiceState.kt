package app.instafocus.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide flag: true while [InstaFocusService] is connected. */
object ServiceState {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    internal fun setConnected(value: Boolean) {
        _connected.value = value
    }
}
