package app.scrollhalt.stats

import app.scrollhalt.detect.BlockCategory
import app.scrollhalt.protect.Moment
import app.scrollhalt.protect.ProtectionMode
import kotlinx.serialization.Serializable

/**
 * Usage statistics: one [DaySummary] per local date (ISO key, e.g. "2026-10-24"), kept
 * [Days.RETENTION_DAYS] days, plus the [LiveRecord] of what is in progress. No per-event
 * history is kept. Durations are estimates measured with elapsed time.
 */
@Serializable
data class StatsState(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Changes on "Reset statistics", so the service never writes back what was reset. */
    val generation: Long = 0,
    val days: Map<String, DaySummary> = emptyMap(),
    val live: LiveRecord = LiveRecord(),
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

@Serializable
data class DaySummary(
    val instagram: InstagramDay = InstagramDay(),
    val reels: ReelsDay = ReelsDay(),
    val blocked: BlockedDay = BlockedDay(),
    val pausesStarted: Int = 0,
    val pausedMillis: Long = 0,
    /** Time the service was running. A day without an entry is "Not tracked". */
    val trackedMillis: Long = 0,
    /** Wall time tracking started on this day (its midnight when the service ran through it). */
    val trackedSinceWall: Long? = null,
)

@Serializable
data class InstagramDay(
    /** Times Instagram came to the front; a return within the grace gap is not a new one. */
    val openings: Int = 0,
    /** Sessions with time on this day: those opened on it plus one running over midnight. */
    val sessions: Int = 0,
    val timeMillis: Long = 0,
    /** Longest part of one session on this day. */
    val longestMillis: Long = 0,
)

/** Estimated Reels viewing: a Reels viewer on screen, not proof that a video played. */
@Serializable
data class ReelsDay(
    val protectedMillis: Long = 0,
    val pausedMillis: Long = 0,
    val disabledMillis: Long = 0,
    val reelsTabMillis: Long = 0,
    val singleReelMillis: Long = 0,
    /** Viewer sessions that lasted at least [ReelsViewing.MIN_SESSION_MS]. */
    val sessions: Int = 0,
    /** The reel each session opened on, plus every swipe to another reel that was not blocked. */
    val watched: Int = 0,
    val longestMillis: Long = 0,
) {
    val totalMillis: Long get() = protectedMillis + pausedMillis + disabledMillis
}

/** One category of blocked attempts, by how far each got. */
@Serializable
data class Outcome(val detected: Int = 0, val actionTaken: Int = 0, val confirmed: Int = 0)

@Serializable
data class BlockedDay(
    val reelsTab: Outcome = Outcome(),
    val exploreReels: Outcome = Outcome(),
    val nextReel: Outcome = Outcome(),
    /** Attempts that escalated to the launcher, counted apart from the categories. */
    val sentToLauncher: Int = 0,
) {
    val total: Int get() = reelsTab.detected + exploreReels.detected + nextReel.detected

    fun of(category: BlockCategory): Outcome = when (category) {
        BlockCategory.REELS_TAB -> reelsTab
        BlockCategory.EXPLORE_REEL -> exploreReels
        BlockCategory.NEXT_REEL -> nextReel
    }

    fun with(category: BlockCategory, outcome: Outcome): BlockedDay = when (category) {
        BlockCategory.REELS_TAB -> copy(reelsTab = outcome)
        BlockCategory.EXPLORE_REEL -> copy(exploreReels = outcome)
        BlockCategory.NEXT_REEL -> copy(nextReel = outcome)
    }
}

/** What is in progress. After process death it is closed at [lastSeen] (see [recover]). */
@Serializable
data class LiveRecord(
    val instagram: OpenSession? = null,
    val viewer: OpenViewer? = null,
    /** The service is running; its time is counted up to here. */
    val trackedUntil: Mark? = null,
    /** Everything open was counted up to here (at the last write). */
    val lastSeen: Mark? = null,
    /** The pause whose start was counted; its time is counted when it ends. */
    val openPause: OpenPause? = null,
    val attempts: AttemptMarks = AttemptMarks(),
)

/** An Instagram session. Time is counted up to [countedUntil]; null while Instagram is away. */
@Serializable
data class OpenSession(
    val countedUntil: Mark?,
    /** When Instagram left the front; a return within [InstagramUsage.GRACE_MS] continues the session. */
    val awaySince: Mark? = null,
    /** Local date of the session's current day part, and how long that part is. */
    val day: String,
    val dayMillis: Long = 0,
)

enum class ViewerKind { REELS_TAB, SINGLE_REEL }

/** A Reels viewer on screen, counted up to [countedUntil] in [mode]. */
@Serializable
data class OpenViewer(
    val kind: ViewerKind,
    val startedAt: Mark,
    val countedUntil: Mark,
    val mode: ProtectionMode,
    val totalMillis: Long = 0,
    /** Counted as a session: it lasted [ReelsViewing.MIN_SESSION_MS]. */
    val counted: Boolean = false,
    val day: String,
    val dayMillis: Long = 0,
    /** Pager and page index from scroll events, and the page on screen from scans. */
    val pagerKey: String? = null,
    val index: Int? = null,
    val page: String? = null,
)

/**
 * A running pause, counted as started once: [id] stays the same for the whole pause, also
 * when [end] moves (rebased after a reboot). Its time is counted from [countedUntil] (its
 * start, or a reset) up to [end] or the moment it was ended early, by elapsed time.
 */
@Serializable
data class OpenPause(val id: Long, val countedUntil: Mark, val end: Mark)

/** Attempts still waiting for their action or confirmation, and the last Reels tab attempt. */
@Serializable
data class AttemptMarks(
    val awaitingAction: Set<BlockCategory> = emptySet(),
    val awaitingConfirmation: Set<BlockCategory> = emptySet(),
    val lastReelsTabAt: Mark? = null,
)

/** A clock reading kept in the statistics: a [Moment] without its time zone. */
@Serializable
data class Mark(val elapsedMillis: Long, val wallMillis: Long, val bootCount: Int)

fun Moment.mark(): Mark = Mark(elapsedMillis, wallMillis, bootCount)

/** Elapsed time from [from] to [to] within one boot, else by wall clock; never negative. */
fun millisBetween(from: Mark, to: Mark): Long =
    if (from.bootCount == to.bootCount && to.elapsedMillis >= from.elapsedMillis) {
        to.elapsedMillis - from.elapsedMillis
    } else {
        (to.wallMillis - from.wallMillis).coerceAtLeast(0)
    }
