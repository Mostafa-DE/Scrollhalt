package app.scrollhalt.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val blockingEnabled: Boolean = true,
    val allowSingleReel: Boolean = true,
    val debugCapture: Boolean = false,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store: DataStore<Preferences> = context.applicationContext.settingsStore

    val settings: Flow<AppSettings> = store.data.map { prefs ->
        AppSettings(
            blockingEnabled = prefs[BLOCKING_ENABLED] ?: true,
            allowSingleReel = prefs[ALLOW_SINGLE_REEL] ?: true,
            debugCapture = prefs[DEBUG_CAPTURE] ?: false,
        )
    }

    suspend fun setBlockingEnabled(value: Boolean) {
        store.edit { it[BLOCKING_ENABLED] = value }
    }

    suspend fun setAllowSingleReel(value: Boolean) {
        store.edit { it[ALLOW_SINGLE_REEL] = value }
    }

    suspend fun setDebugCapture(value: Boolean) {
        store.edit { it[DEBUG_CAPTURE] = value }
    }

    private companion object {
        val BLOCKING_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("blockingEnabled")
        val ALLOW_SINGLE_REEL: Preferences.Key<Boolean> = booleanPreferencesKey("allowSingleReel")
        val DEBUG_CAPTURE: Preferences.Key<Boolean> = booleanPreferencesKey("debugCapture")
    }
}
