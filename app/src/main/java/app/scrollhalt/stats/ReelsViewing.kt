package app.scrollhalt.stats

import app.scrollhalt.detect.Classification
import app.scrollhalt.detect.ReelPolicy
import app.scrollhalt.detect.Screen
import app.scrollhalt.protect.Moment
import app.scrollhalt.protect.ProtectionMode

/**
 * Estimated Reels viewing, fed by every classified screen whether blocking is on, paused or
 * off. A viewer session runs while the Reels tab or a single reel is on screen (UNKNOWN
 * frames do not end it) and ends on any other screen, when Instagram leaves the front or
 * the screen goes off. A viewer open is not proof that a video played, and inline feed
 * videos and Stories are not seen at all.
 */
object ReelsViewing {
    /** Shorter viewer sessions add time but do not count as sessions. */
    const val MIN_SESSION_MS: Long = 1_000

    /** Page changes this soon after a viewer opens are Instagram laying it out, not swipes. */
    const val SETTLE_MS: Long = 1_000
}

fun StatsState.viewerSeen(now: Moment, classification: Classification, mode: ProtectionMode): StatsState {
    val kind = when (classification.screen) {
        Screen.REELS_TAB -> ViewerKind.REELS_TAB
        Screen.SINGLE_REEL -> ViewerKind.SINGLE_REEL
        Screen.UNKNOWN -> return this
        Screen.DM, Screen.EXPLORE_GRID, Screen.PROFILE, Screen.OTHER -> return viewerGone(now)
    }
    val open = live.viewer
    return when {
        open == null -> openViewer(now, kind, mode, classification.reelPage)
        open.kind != kind -> viewerGone(now).openViewer(now, kind, mode, classification.reelPage)
        else -> viewerMode(now, mode).pageSeen(now, classification.reelPage)
    }
}

/**
 * A scroll of a reel pager. Each new page index is a swipe to another reel; the first index
 * of a pager is only the baseline unless it comes after the viewer settled (then the
 * positioning scroll was missed and this is the user's swipe).
 */
fun StatsState.viewerScrolled(now: Moment, pagerKey: String, index: Int): StatsState {
    if (index < 0) return this
    val open = live.viewer ?: return copy(live = live.copy(lastScroll = ScrollMark(pagerKey, index, now.mark())))
    if (pagerKey == open.pagerKey && index == open.index) return this
    val swipe = when {
        open.pagerKey == null -> settled(open, now)
        pagerKey != open.pagerKey -> false
        else -> true
    }
    val tracked = copy(live = live.copy(viewer = open.copy(pagerKey = pagerKey, index = index)))
    return if (swipe) tracked.reelWatched(now, open.mode) else tracked
}

/** The protection mode changed: time so far goes to the old mode. */
fun StatsState.viewerMode(now: Moment, mode: ProtectionMode): StatsState {
    val open = live.viewer ?: return this
    if (open.mode == mode) return this
    val counted = countViewer(now)
    return counted.copy(live = counted.live.copy(viewer = counted.live.viewer?.copy(mode = mode)))
}

/** The viewer left the screen at [now]. */
fun StatsState.viewerGone(now: Moment): StatsState {
    if (live.viewer == null) return this
    val counted = countViewer(now)
    return counted.copy(live = counted.live.copy(viewer = null))
}

/** Counts the viewer's time up to [now], split at midnight, by mode and by where it was opened. */
fun StatsState.countViewer(now: Moment): StatsState {
    val open = live.viewer ?: return this
    val (counted, viewer) = splitByDay(open.countedUntil, now).fold(days to open) { (days, viewer), part ->
        val key = Days.key(part.date)
        val dayMillis = (viewer.dayParts[key] ?: 0) + part.millis
        val total = viewer.totalMillis + part.millis
        val first = !viewer.counted && total >= ReelsViewing.MIN_SESSION_MS
        val updated = days.update(part.date) { day ->
            val reels = day.reels.plus(viewer.mode, viewer.kind, part.millis)
            day.copy(
                reels = reels.copy(
                    sessions = reels.sessions + if (first) 1 else 0,
                    watched = reels.watched + if (first) 1 else 0,
                    longestMillis = maxOf(reels.longestMillis, dayMillis),
                ),
            )
        }
        updated to viewer.copy(dayParts = viewer.dayParts + (key to dayMillis), totalMillis = total, counted = viewer.counted || first)
    }
    return copy(days = counted, live = live.copy(viewer = viewer.copy(countedUntil = now.mark())))
}

/** A scroll just before the viewer (Instagram positioning the pager) is its baseline, as in [ReelPolicy]. */
private fun StatsState.openViewer(now: Moment, kind: ViewerKind, mode: ProtectionMode, page: String?): StatsState {
    val scroll = live.lastScroll?.takeIf { millisBetween(it.at, now.mark()) <= ReelPolicy.OPENING_SCROLL_MS }
    val viewer = OpenViewer(
        kind,
        startedAt = now.mark(),
        countedUntil = now.mark(),
        mode = mode,
        pagerKey = scroll?.pagerKey,
        index = scroll?.index,
        page = page,
    )
    return copy(live = live.copy(viewer = viewer, lastScroll = null))
}

/** Another page on screen. Only counts when the pager reports no indexes (they count in [viewerScrolled]). */
private fun StatsState.pageSeen(now: Moment, page: String?): StatsState {
    val open = live.viewer ?: return this
    if (page == null || page == open.page) return this
    val swipe = open.page != null && open.index == null && settled(open, now)
    val tracked = copy(live = live.copy(viewer = open.copy(page = page)))
    return if (swipe) tracked.reelWatched(now, open.mode) else tracked
}

/** A swipe to another reel. While protected it is blocked (a Next-Reel swipe), not watched. */
private fun StatsState.reelWatched(now: Moment, mode: ProtectionMode): StatsState {
    if (mode == ProtectionMode.PROTECTED) return this
    return copy(days = days.update(Days.today(now)) { it.copy(reels = it.reels.copy(watched = it.reels.watched + 1)) })
}

private fun settled(viewer: OpenViewer, now: Moment): Boolean = millisBetween(viewer.startedAt, now.mark()) >= ReelsViewing.SETTLE_MS

private fun ReelsDay.plus(mode: ProtectionMode, kind: ViewerKind, millis: Long): ReelsDay {
    val byMode = when (mode) {
        ProtectionMode.PROTECTED -> copy(protectedMillis = protectedMillis + millis)
        ProtectionMode.PAUSED -> copy(pausedMillis = pausedMillis + millis)
        ProtectionMode.DISABLED -> copy(disabledMillis = disabledMillis + millis)
    }
    return when (kind) {
        ViewerKind.REELS_TAB -> byMode.copy(reelsTabMillis = byMode.reelsTabMillis + millis)
        ViewerKind.SINGLE_REEL -> byMode.copy(singleReelMillis = byMode.singleReelMillis + millis)
    }
}
