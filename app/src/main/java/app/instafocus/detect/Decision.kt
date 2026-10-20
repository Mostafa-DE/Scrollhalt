package app.instafocus.detect

enum class Decision {
    NONE,

    /** Switch to the Home tab (BACK from Instagram's root would leave the app). */
    GO_HOME,

    /** Press the system Back button. */
    BACK,

    /** Scroll the reel pager back to the reel the user opened. */
    SNAP_BACK,
}

enum class SnapDirection { BACKWARD, FORWARD }

data class PolicySettings(
    val blockingEnabled: Boolean = true,
    val allowSingleReel: Boolean = true,
)

/** What the service observed, in the order it happened. Times are monotonic milliseconds. */
sealed interface PolicyInput {
    val atMillis: Long

    data class ScreenSeen(override val atMillis: Long, val classification: Classification) : PolicyInput

    data class ViewClicked(override val atMillis: Long, val viewId: String?) : PolicyInput

    /** A scroll event whose source is the reel pager; [fromIndex] is the first visible page. */
    data class PagerScrolled(override val atMillis: Long, val fromIndex: Int) : PolicyInput
}

data class PolicyResult(
    val policy: ReelPolicy,
    val decision: Decision,
    val reason: String,
    val snapDirection: SnapDirection? = null,
)
