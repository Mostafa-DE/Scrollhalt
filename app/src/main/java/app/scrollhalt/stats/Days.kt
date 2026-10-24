package app.scrollhalt.stats

import app.scrollhalt.protect.Moment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

object Days {
    /** Days kept, today included; older ones are dropped. */
    const val RETENTION_DAYS: Int = 30

    fun key(date: LocalDate): String = date.toString()

    fun dateOf(wallMillis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(wallMillis).atZone(zone).toLocalDate()

    fun today(now: Moment): LocalDate = dateOf(now.wallMillis, now.zone)

    fun parse(key: String): LocalDate? = try {
        LocalDate.parse(key)
    } catch (_: DateTimeParseException) {
        null
    }
}

/** A stretch of time inside one local day. */
data class DayPart(val date: LocalDate, val startWallMillis: Long, val millis: Long)

/**
 * Splits [millis] starting at [startWallMillis] at the local midnights of [zone], so a
 * session over midnight counts on both days. The zone is the current one: after a time
 * zone change, time not yet counted goes to the dates of the new zone.
 */
fun splitByDay(startWallMillis: Long, millis: Long, zone: ZoneId): List<DayPart> = split(startWallMillis, millis, zone, emptyList())

private tailrec fun split(start: Long, left: Long, zone: ZoneId, parts: List<DayPart>): List<DayPart> {
    if (left <= 0) return parts
    val date = Days.dateOf(start, zone)
    val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val part = minOf(left, nextMidnight - start)
    return split(start + part, left - part, zone, parts + DayPart(date, start, part))
}

/** The time from [from] to [now], split by local day. */
fun splitByDay(from: Mark, now: Moment): List<DayPart> = splitByDay(from.wallMillis, millisBetween(from, now.mark()), now.zone)

/** The summary of [date] changed by [change]; a missing day starts empty. */
fun Map<String, DaySummary>.update(date: LocalDate, change: (DaySummary) -> DaySummary): Map<String, DaySummary> {
    val key = Days.key(date)
    return this + (key to change(this[key] ?: DaySummary()))
}

/** Keeps the last [Days.RETENTION_DAYS] days up to [today]; unreadable keys are dropped too. */
fun Map<String, DaySummary>.retain(today: LocalDate): Map<String, DaySummary> {
    val oldest = today.minusDays(Days.RETENTION_DAYS - 1L)
    return filterKeys { key -> Days.parse(key)?.let { !it.isBefore(oldest) } == true }
}
