package app.scrollhalt.stats

import app.scrollhalt.protect.Moment

/**
 * Instagram sessions. A session starts when Instagram comes to the front (one opening;
 * screens inside Instagram never count) and stops when another app or the launcher comes to
 * the front or the screen goes off. Coming back within [GRACE_MS] continues it: no new
 * opening, and the time away is not counted.
 */
object InstagramUsage {
    const val GRACE_MS: Long = 10_000
}

/** Instagram is in front. Repeated calls while it stays there change nothing. */
fun StatsState.instagramInFront(now: Moment): StatsState {
    val open = live.instagram
    val away = open?.awaySince
    return when {
        open?.countedUntil != null -> this
        open != null && away != null && millisBetween(away, now.mark()) <= InstagramUsage.GRACE_MS ->
            copy(live = live.copy(instagram = open.copy(countedUntil = now.mark(), awaySince = null)))
        else -> openSession(now)
    }
}

/** Instagram left the front at [now] (another app, the launcher, screen off). */
fun StatsState.instagramAway(now: Moment): StatsState {
    if (live.instagram?.countedUntil == null) return this
    val counted = countInstagram(now)
    val session = counted.live.instagram ?: return counted
    return counted.copy(live = counted.live.copy(instagram = session.copy(countedUntil = null, awaySince = now.mark())))
}

/** Counts the running session's time up to [now], split at midnight. */
fun StatsState.countInstagram(now: Moment): StatsState {
    val open = live.instagram ?: return this
    val from = open.countedUntil ?: return this
    val (counted, session) = splitByDay(from, now).fold(days to open) { (days, session), part ->
        val key = Days.key(part.date)
        val newDay = key != session.day
        val dayMillis = (if (newDay) 0 else session.dayMillis) + part.millis
        val updated = days.update(part.date) { day ->
            day.copy(
                instagram = day.instagram.copy(
                    sessions = day.instagram.sessions + if (newDay) 1 else 0,
                    timeMillis = day.instagram.timeMillis + part.millis,
                    longestMillis = maxOf(day.instagram.longestMillis, dayMillis),
                ),
            )
        }
        updated to session.copy(day = key, dayMillis = dayMillis)
    }
    return copy(days = counted, live = live.copy(instagram = session.copy(countedUntil = now.mark())))
}

private fun StatsState.openSession(now: Moment): StatsState {
    val today = Days.today(now)
    val counted = days.update(today) { day ->
        day.copy(instagram = day.instagram.copy(openings = day.instagram.openings + 1, sessions = day.instagram.sessions + 1))
    }
    return copy(days = counted, live = live.copy(instagram = OpenSession(countedUntil = now.mark(), day = Days.key(today))))
}
