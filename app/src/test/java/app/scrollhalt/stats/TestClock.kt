package app.scrollhalt.stats

import app.scrollhalt.protect.Clock
import app.scrollhalt.protect.Moment
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

val AMMAN: ZoneId = ZoneId.of("Asia/Amman")

/** A clock the test moves by hand; elapsed and wall time advance together. */
class TestClock(
    start: LocalDateTime = LocalDateTime.of(2026, 10, 24, 9, 0),
    var zone: ZoneId = AMMAN,
    var bootCount: Int = 3,
) : Clock {
    var elapsed: Long = 5_000_000
    var wall: Long = start.atZone(zone).toInstant().toEpochMilli()

    override fun now(): Moment = Moment(elapsed, wall, bootCount, zone)

    fun advance(millis: Long): Moment {
        elapsed += millis
        wall += millis
        return now()
    }

    fun seconds(s: Long): Moment = advance(s * 1_000)

    fun minutes(m: Long): Moment = advance(m * 60_000)

    /** Power cycle: elapsed time starts again near zero and the boot count goes up. */
    fun reboot(offMillis: Long): Moment {
        bootCount += 1
        elapsed = 10_000
        wall += offMillis
        return now()
    }
}

fun StatsState.day(date: LocalDate): DaySummary = days[Days.key(date)] ?: DaySummary()

val OCT_24: LocalDate = LocalDate.of(2026, 10, 24)
val OCT_25: LocalDate = LocalDate.of(2026, 10, 25)

const val MINUTE: Long = 60_000
