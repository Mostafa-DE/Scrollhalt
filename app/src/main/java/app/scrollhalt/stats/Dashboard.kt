package app.scrollhalt.stats

import java.time.LocalDate

/** What the main screen shows: today, and the last 7 days ending today. */
data class Dashboard(val today: DaySummary?, val week: List<DayBar>)

/** One day of the chart; a day without statistics was not tracked (the service was off). */
data class DayBar(
    val date: LocalDate,
    val tracked: Boolean,
    val instagramMillis: Long = 0,
    val opens: Int = 0,
    val reelsMillis: Long = 0,
    val blocked: Int = 0,
)

/** The chips of the 7-day chart. */
enum class ChartMetric {
    INSTAGRAM_TIME,
    OPENS,
    REELS,
    BLOCKED,
    ;

    fun of(bar: DayBar): Long = when (this) {
        INSTAGRAM_TIME -> bar.instagramMillis
        OPENS -> bar.opens.toLong()
        REELS -> bar.reelsMillis
        BLOCKED -> bar.blocked.toLong()
    }
}

fun dashboard(state: StatsState, today: LocalDate): Dashboard {
    val week = (6L downTo 0L).map { back ->
        val date = today.minusDays(back)
        val day = state.days[Days.key(date)]
        if (day == null) {
            DayBar(date, tracked = false)
        } else {
            DayBar(date, tracked = true, day.instagram.timeMillis, day.instagram.openings, day.reels.totalMillis, day.blocked.total)
        }
    }
    return Dashboard(state.days[Days.key(today)], week)
}

fun InstagramDay.averageMillis(): Long = if (sessions == 0) 0 else timeMillis / sessions

/** Whole hours and the minutes after them, rounded down. */
data class HoursMinutes(val hours: Long, val minutes: Long)

fun hoursMinutes(millis: Long): HoursMinutes {
    val totalMinutes = millis.coerceAtLeast(0) / 60_000
    return HoursMinutes(totalMinutes / 60, totalMinutes % 60)
}
