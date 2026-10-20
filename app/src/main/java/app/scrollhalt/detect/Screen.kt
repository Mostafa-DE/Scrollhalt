package app.scrollhalt.detect

enum class Screen {
    /** The Reels tab: tab bar on screen with the Reels tab selected. */
    REELS_TAB,

    /** A reel opened from a DM, Explore, the feed or a profile: pager on screen, no tab bar. */
    SINGLE_REEL,

    /** DM thread or inbox. Never acted on. */
    DM,

    /** The Explore grid in the Search tab. Reels opened from it are closed at once. */
    EXPLORE_GRID,

    /** A profile page. Reels opened from it play once, even when the profile was found by search. */
    PROFILE,

    /** Anything else: feed, search results, stories, settings. */
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
    /**
     * Name of the selected bottom tab, if any tab reports it. Read from hidden tab buttons
     * too, since the Explore-opened reel hides the tab bar but keeps search_tab selected.
     */
    val selectedTab: String? = null,
    /** On-screen bounds of the Reels tab button, while the tab bar is visible: where the cover goes. */
    val reelsTabBounds: Bounds? = null,
    /**
     * For a reel in front: identity of the reel page on screen (the largest on-screen child
     * of the front pager), as "pagerKey/pageKey". A different page means the user swiped.
     */
    val reelPage: String? = null,
    /** Current Instagram activity class, if known (for the debug log). */
    val activity: String? = null,
)
