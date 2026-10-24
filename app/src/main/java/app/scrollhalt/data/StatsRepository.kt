package app.scrollhalt.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import app.scrollhalt.protect.Moment
import app.scrollhalt.stats.StatsJson
import app.scrollhalt.stats.StatsState
import app.scrollhalt.stats.reset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val TAG: String = "Scrollhalt"

private object StatsSerializer : Serializer<StatsState> {
    override val defaultValue: StatsState = StatsState()

    override suspend fun readFrom(input: InputStream): StatsState = try {
        StatsJson.decode(input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable statistics", e)
    }

    override suspend fun writeTo(t: StatsState, output: OutputStream) {
        output.write(StatsJson.encode(t).encodeToByteArray())
    }
}

/** App-private file, never backed up (allowBackup is off). An unreadable file starts over. */
private val Context.statsStore: DataStore<StatsState> by dataStore(
    fileName = "stats.json",
    serializer = StatsSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { StatsState() },
)

/** One writer at a time, in order, and not tied to a service that may be stopping. */
private val writes: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

class StatsRepository(context: Context) {
    private val store: DataStore<StatsState> = context.applicationContext.statsStore

    val stats: Flow<StatsState> = store.data.catch { e ->
        if (e !is IOException) throw e
        Log.w(TAG, "statistics unreadable: ${e.javaClass.simpleName}")
        emit(StatsState())
    }

    /** Writes [state] in the background, unless the stored statistics were reset meanwhile. */
    fun save(state: StatsState) {
        writes.launch {
            try {
                store.updateData { stored -> if (stored.generation == state.generation) state else stored }
            } catch (e: IOException) {
                Log.w(TAG, "statistics not written: ${e.javaClass.simpleName}")
            }
        }
    }

    /** "Reset statistics"; settings and a running pause are not touched. */
    suspend fun reset(now: Moment, tracking: Boolean) {
        store.updateData { it.reset(now, tracking) }
    }
}
