package app.instafocus.detect

enum class Screen {
    /** Full-screen reel pager with its reel controls on screen. */
    REEL_VIEWER,

    /** Direct messages inbox or thread. */
    DM,

    /** Explore grid or search. */
    EXPLORE,

    /** Anything else inside Instagram: feed, profile, stories, settings. */
    OTHER,

    /** The reel pager is on screen but its controls are not; not confident either way. */
    UNKNOWN,
}

data class Classification(
    val screen: Screen,
    val reelsTabSelected: Boolean,
    /** A DM marker (e.g. the reply bar) is on screen together with the reel viewer. */
    val dmOverlay: Boolean = false,
)
