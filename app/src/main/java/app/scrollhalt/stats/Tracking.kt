package app.scrollhalt.stats

import app.scrollhalt.protect.Moment

/** The service connected: closes what a previous process left open and starts tracked time. */
fun StatsState.serviceStarted(now: Moment): StatsState {
    val recovered = recover(now)
    val today = Days.today(now)
    return recovered.copy(
        days = recovered.days.update(today) { it.copy(trackedSinceWall = minOf(it.trackedSinceWall ?: now.wallMillis, now.wallMillis)) },
        live = recovered.live.copy(trackedUntil = now.mark()),
    )
}

/** The service is stopping: counts everything and closes it. */
fun StatsState.serviceStopped(now: Moment): StatsState {
    val closed = countAll(now).instagramAway(now).viewerGone(now)
    return closed.copy(live = closed.live.copy(trackedUntil = null))
}

/**
 * Closes the sessions a previous process left open at the last time they were counted
 * ([LiveRecord.lastSeen]); whether Instagram stayed open after that is unknown, so that time
 * is not counted. The pause is kept: its timer ran on without the process.
 */
fun StatsState.recover(now: Moment): StatsState {
    val last = live.lastSeen
    val closed = if (last == null) this else Moment(last.elapsedMillis, last.wallMillis, last.bootCount, now.zone).let { at ->
        countInstagram(at).viewerGone(at).countTracked(at)
    }
    return closed.copy(live = LiveRecord(pause = live.pause))
}

/** Counts everything in progress up to [now] (before each write) and drops days past retention. */
fun StatsState.countAll(now: Moment): StatsState {
    val counted = countInstagram(now).countViewer(now).countTracked(now)
    return counted.copy(days = counted.days.retain(Days.today(now)), live = counted.live.copy(lastSeen = now.mark()))
}

/** Counts the service's running time up to [now]; each day remembers when tracking began on it. */
fun StatsState.countTracked(now: Moment): StatsState {
    val from = live.trackedUntil ?: return this
    val counted = splitByDay(from, now).fold(days) { days, part ->
        days.update(part.date) { day ->
            day.copy(
                trackedMillis = day.trackedMillis + part.millis,
                trackedSinceWall = minOf(day.trackedSinceWall ?: part.startWallMillis, part.startWallMillis),
            )
        }
    }
    return copy(days = counted, live = live.copy(trackedUntil = now.mark()))
}

/** A pause is running; counted once however often it is seen again. */
fun StatsState.pauseStarted(now: Moment, span: PauseSpan): StatsState {
    if (live.pause == span) return this
    val previous = pauseEnded(now)
    val date = Days.dateOf(span.startWallMillis, now.zone)
    return previous.copy(
        days = previous.days.update(date) { it.copy(pausesStarted = it.pausesStarted + 1) },
        live = previous.live.copy(pause = span),
    )
}

/** The pause ended (expired, resumed early or cancelled): its time is split over its days. */
fun StatsState.pauseEnded(now: Moment): StatsState {
    val span = live.pause ?: return this
    val end = minOf(now.wallMillis, span.endWallMillis)
    val counted = splitByDay(span.startWallMillis, end - span.startWallMillis, now.zone).fold(days) { days, part ->
        days.update(part.date) { it.copy(pausedMillis = it.pausedMillis + part.millis) }
    }
    return copy(days = counted, live = live.copy(pause = null))
}

/**
 * "Reset statistics": every day goes and what is in progress counts on from [now]. With
 * [tracking] (the service runs) today starts as tracked.
 */
fun StatsState.reset(now: Moment, tracking: Boolean): StatsState = copy(
    generation = maxOf(now.wallMillis, generation + 1),
    days = if (tracking) mapOf(Days.key(Days.today(now)) to DaySummary(trackedSinceWall = now.wallMillis)) else emptyMap(),
    live = live.restarted(now),
)

/** The service's statistics after the stored ones were reset elsewhere. */
fun StatsState.adoptReset(stored: StatsState, now: Moment): StatsState =
    copy(generation = stored.generation, days = stored.days, live = live.restarted(now))

/** Nothing before [now] is counted any more; an open session counts as one on today. */
private fun LiveRecord.restarted(now: Moment): LiveRecord {
    val mark = now.mark()
    return copy(
        instagram = instagram?.let { if (it.countedUntil == null) it else it.copy(countedUntil = mark, day = "", dayMillis = 0) },
        viewer = viewer?.copy(countedUntil = mark, dayMillis = 0),
        trackedUntil = trackedUntil?.let { mark },
        pause = pause?.copy(startWallMillis = maxOf(pause.startWallMillis, now.wallMillis)),
        attempts = AttemptMarks(),
    )
}
