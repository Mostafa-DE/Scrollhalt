package app.scrollhalt.stats

import kotlinx.serialization.json.Json

/** The stored form of [StatsState]: JSON with an explicit schema version. */
object StatsJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(state: StatsState): String = json.encodeToString(StatsState.serializer(), state)

    /**
     * Fields written by a newer version are ignored. Schema 1 is the first, so there is
     * nothing to migrate yet; a future change of meaning goes here, keyed on the version.
     * Throws a SerializationException for text that is not statistics.
     */
    fun decode(text: String): StatsState {
        val state = json.decodeFromString(StatsState.serializer(), text)
        return if (state.schemaVersion == StatsState.SCHEMA_VERSION) state else state.copy(schemaVersion = StatsState.SCHEMA_VERSION)
    }
}
