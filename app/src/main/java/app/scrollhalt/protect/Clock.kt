package app.scrollhalt.protect

import java.time.ZoneId

/**
 * One reading of every clock the pause and usage logic needs. [elapsedMillis] is
 * SystemClock.elapsedRealtime (monotonic, counts deep sleep, restarts at boot), [wallMillis]
 * the wall clock (can jump), [bootCount] tells boots apart (-1 when unknown).
 */
data class Moment(
    val elapsedMillis: Long,
    val wallMillis: Long,
    val bootCount: Int,
    val zone: ZoneId,
)

/** Source of [Moment]s: the device clocks in the app, a fake in tests. */
fun interface Clock {
    fun now(): Moment
}
