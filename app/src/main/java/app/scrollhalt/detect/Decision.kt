package app.scrollhalt.detect

enum class Decision {
    NONE,

    /** Switch to the Home tab (BACK from Instagram's root would leave the app). */
    GO_HOME,

    /** Press the system Back button: closes a reel viewer. */
    BACK,

    /** Go to the launcher: the last resort when gentler actions did not leave the reel. */
    GLOBAL_HOME,
}

data class PolicySettings(
    val blockingEnabled: Boolean = true,
    val allowSingleReel: Boolean = true,
)

/** What the service observed, in the order it happened. Times are monotonic milliseconds. */
sealed interface PolicyInput {
    val atMillis: Long

    data class ScreenSeen(override val atMillis: Long, val classification: Classification) : PolicyInput

    data class ViewClicked(override val atMillis: Long, val viewId: String?) : PolicyInput

    /**
     * A scroll event whose source is a reel pager, which reports its current page as
     * [fromIndex]. [pagerKey] identifies the source view: two pagers can exist at once
     * (an Explore reel in front of the Reels tab), and only the one in front scrolls.
     */
    data class PagerScrolled(override val atMillis: Long, val pagerKey: String, val fromIndex: Int) : PolicyInput

    /** The active window is no longer Instagram (home screen, another app, notification shade). */
    data class LeftApp(override val atMillis: Long) : PolicyInput

    /** Blocking stopped (a pause started or Block Reels was switched off). */
    data class ProtectionPaused(override val atMillis: Long) : PolicyInput

    /** Blocking started again (a pause ended or Block Reels was switched on). */
    data class ProtectionResumed(override val atMillis: Long) : PolicyInput
}

/** What a blocked attempt was aimed at. */
enum class BlockCategory {
    /** The Reels tab, opened by a tap, a link or Instagram restoring it. */
    REELS_TAB,

    /** A reel opened from the Explore grid (or an unknown screen in the Search tab). */
    EXPLORE_REEL,

    /** A swipe to another reel after an allowed single reel. */
    NEXT_REEL,
}

/** Structured facts about blocking, for usage statistics. [PolicyResult.reason] stays free text for the log. */
sealed interface PolicyEvent {
    /** A new attempt: the first action against it, never a retry, a held-back or an escalated one. */
    data class Attempt(val category: BlockCategory) : PolicyEvent

    /** The blocked screen was left after an action against it. */
    data class Confirmed(val category: BlockCategory) : PolicyEvent

    /** An attempt escalated to the launcher (its first [Decision.GLOBAL_HOME]). */
    data object SentToLauncher : PolicyEvent
}

data class PolicyResult(
    val policy: ReelPolicy,
    val decision: Decision,
    val reason: String,
    /** When set, the service must scan again at this time: to judge an action, or to retry one held back. */
    val retryAtMillis: Long? = null,
    val events: List<PolicyEvent> = emptyList(),
) {
    /** Adds [earlier] in front of this result's own events. */
    fun withEvents(earlier: List<PolicyEvent>): PolicyResult = if (earlier.isEmpty()) this else copy(events = earlier + events)
}
