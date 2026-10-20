package app.instafocus.detect

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
}

data class PolicyResult(
    val policy: ReelPolicy,
    val decision: Decision,
    val reason: String,
    /** When set, the service must scan again at this time: to judge an action, or to retry one held back. */
    val retryAtMillis: Long? = null,
)
