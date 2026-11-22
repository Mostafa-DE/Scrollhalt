package app.scrollhalt.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.scrollhalt.protect.Moment
import app.scrollhalt.protect.OffForApp
import app.scrollhalt.protect.PauseState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val blockingEnabled: Boolean = true,
    val allowSingleReel: Boolean = true,
    val debugCapture: Boolean = false,
    /** The timed pause, if one was started; it may have expired already (see [PauseState.isActive]). */
    val pause: PauseState? = null,
    /** Package names of the apps Scrollhalt turns its service off for ("Turn off for these apps"). */
    val turnOffFor: Set<String> = emptySet(),
    /** The service is off for one of those apps until it connects again; may be due already. */
    val offForApp: OffForApp? = null,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.settingsStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            blockingEnabled = prefs[BLOCKING_ENABLED] ?: true,
            allowSingleReel = prefs[ALLOW_SINGLE_REEL] ?: true,
            debugCapture = prefs[DEBUG_CAPTURE] ?: false,
            pause = pauseOf(prefs),
            turnOffFor = prefs[TURN_OFF_FOR] ?: emptySet(),
            offForApp = offForAppOf(prefs),
        )
    }

    /** Switching Block Reels off also cancels a running pause. */
    suspend fun setBlockingEnabled(value: Boolean) {
        store.edit {
            it[BLOCKING_ENABLED] = value
            if (!value) it.removePause()
        }
    }

    suspend fun startPause(pause: PauseState) {
        store.edit { it.putPause(pause) }
    }

    /** A pause stored on an earlier boot moves onto this boot's clocks, once (see [PauseState.rebased]). */
    suspend fun rebasePause(now: Moment) {
        store.edit { prefs ->
            val pause = pauseOf(prefs)
            if (pause != null && !pause.isOnBootOf(now)) prefs.putPause(pause.rebased(now))
        }
    }

    /** Ends the pause; with [expected], only if that pause is still the stored one. */
    suspend fun clearPause(expected: PauseState? = null) {
        store.edit { if (expected == null || pauseOf(it) == expected) it.removePause() }
    }

    suspend fun setAllowSingleReel(value: Boolean) {
        store.edit { it[ALLOW_SINGLE_REEL] = value }
    }

    suspend fun setDebugCapture(value: Boolean) {
        store.edit { it[DEBUG_CAPTURE] = value }
    }

    /** Adds [packageName] to the apps to turn off for ([listed]) or removes it. */
    suspend fun setTurnOffFor(packageName: String, listed: Boolean) {
        store.edit {
            val current = it[TURN_OFF_FOR] ?: emptySet()
            it[TURN_OFF_FOR] = if (listed) current + packageName else current - packageName
        }
    }

    suspend fun startOffForApp(off: OffForApp) {
        store.edit { it.putOffForApp(off) }
    }

    /** A comeback stored on an earlier boot moves onto this boot's clocks, once (see [PauseState.rebased]). */
    suspend fun rebaseOffForApp(now: Moment) {
        store.edit { prefs ->
            val off = offForAppOf(prefs)
            if (off != null && !off.comeback.isOnBootOf(now)) prefs.putOffForApp(off.copy(comeback = off.comeback.rebased(now)))
        }
    }

    /** The service is back. */
    suspend fun clearOffForApp() {
        store.edit { it.removeOffForApp() }
    }

    private fun offForAppOf(prefs: Preferences): OffForApp? {
        val packageName = prefs[OFF_PACKAGE] ?: return null
        val offAtWall = prefs[OFF_AT_WALL] ?: return null
        val backAtWall = prefs[OFF_BACK_AT_WALL] ?: return null
        val backAtElapsed = prefs[OFF_BACK_AT_ELAPSED] ?: return null
        val bootCount = prefs[OFF_BOOT_COUNT] ?: return null
        return OffForApp(packageName, PauseState(backAtWall, backAtElapsed, bootCount, OffForApp.COMEBACK_MILLIS, offAtWall))
    }

    private fun pauseOf(prefs: Preferences): PauseState? {
        val endWall = prefs[PAUSE_END_WALL] ?: return null
        val endElapsed = prefs[PAUSE_END_ELAPSED] ?: return null
        val bootCount = prefs[PAUSE_BOOT_COUNT] ?: return null
        val duration = prefs[PAUSE_DURATION] ?: return null
        return PauseState(endWall, endElapsed, bootCount, duration, prefs[PAUSE_START_WALL] ?: endWall - duration)
    }

    private fun MutablePreferences.putPause(pause: PauseState) {
        this[PAUSE_END_WALL] = pause.endWallMillis
        this[PAUSE_END_ELAPSED] = pause.endElapsedMillis
        this[PAUSE_BOOT_COUNT] = pause.bootCount
        this[PAUSE_DURATION] = pause.durationMillis
        this[PAUSE_START_WALL] = pause.startedWallMillis
    }

    private fun MutablePreferences.putOffForApp(off: OffForApp) {
        this[OFF_PACKAGE] = off.packageName
        this[OFF_AT_WALL] = off.comeback.startedWallMillis
        this[OFF_BACK_AT_WALL] = off.comeback.endWallMillis
        this[OFF_BACK_AT_ELAPSED] = off.comeback.endElapsedMillis
        this[OFF_BOOT_COUNT] = off.comeback.bootCount
    }

    private fun MutablePreferences.removeOffForApp() {
        remove(OFF_PACKAGE)
        remove(OFF_AT_WALL)
        remove(OFF_BACK_AT_WALL)
        remove(OFF_BACK_AT_ELAPSED)
        remove(OFF_BOOT_COUNT)
    }

    private fun MutablePreferences.removePause() {
        remove(PAUSE_END_WALL)
        remove(PAUSE_END_ELAPSED)
        remove(PAUSE_BOOT_COUNT)
        remove(PAUSE_DURATION)
        remove(PAUSE_START_WALL)
    }

    private companion object {
        val BLOCKING_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("blockingEnabled")
        val ALLOW_SINGLE_REEL: Preferences.Key<Boolean> = booleanPreferencesKey("allowSingleReel")
        val DEBUG_CAPTURE: Preferences.Key<Boolean> = booleanPreferencesKey("debugCapture")
        val PAUSE_END_WALL: Preferences.Key<Long> = longPreferencesKey("pauseEndWall")
        val PAUSE_END_ELAPSED: Preferences.Key<Long> = longPreferencesKey("pauseEndElapsed")
        val PAUSE_BOOT_COUNT: Preferences.Key<Int> = intPreferencesKey("pauseBootCount")
        val PAUSE_DURATION: Preferences.Key<Long> = longPreferencesKey("pauseDuration")
        val PAUSE_START_WALL: Preferences.Key<Long> = longPreferencesKey("pauseStartWall")
        val TURN_OFF_FOR: Preferences.Key<Set<String>> = stringSetPreferencesKey("turnOffFor")
        val OFF_PACKAGE: Preferences.Key<String> = stringPreferencesKey("offForAppPackage")
        val OFF_AT_WALL: Preferences.Key<Long> = longPreferencesKey("offForAppOffAtWall")
        val OFF_BACK_AT_WALL: Preferences.Key<Long> = longPreferencesKey("offForAppBackAtWall")
        val OFF_BACK_AT_ELAPSED: Preferences.Key<Long> = longPreferencesKey("offForAppBackAtElapsed")
        val OFF_BOOT_COUNT: Preferences.Key<Int> = intPreferencesKey("offForAppBootCount")
    }
}
