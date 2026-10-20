package app.instafocus.detect

enum class Screen {
    /** The Reels tab: tab bar on screen with the Reels tab selected. */
    REELS_TAB,

    /** A single reel opened from a DM, Explore, the feed or a profile: pager on screen, no tab bar. */
    SINGLE_REEL,

    /** DM thread or inbox. Never acted on. */
    DM,

    /** Anything else: feed, profile, Explore grid, stories, settings. */
    OTHER,

    /** A reel pager without a tab bar but also without reel controls; not confident either way. */
    UNKNOWN,
}

data class Classification(
    val screen: Screen,
    /** For [Screen.SINGLE_REEL]: the reel was shared in a DM (modal activity or sender markers). */
    val sharedInDm: Boolean = false,
    /** The bottom tab bar is on screen (for the debug log). */
    val tabBarVisible: Boolean = false,
    /** Name of the selected bottom tab that is on screen, if any (for the debug log). */
    val selectedTab: String? = null,
    /** Current Instagram activity class, if known (for the debug log). */
    val activity: String? = null,
)
