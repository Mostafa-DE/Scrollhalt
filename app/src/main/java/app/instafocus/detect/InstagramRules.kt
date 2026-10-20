package app.instafocus.detect

/**
 * Every Instagram view id InstaFocus relies on. When an Instagram update renames a
 * view, this is the only file that needs to change (use the debug snapshot to find
 * the new names). Names are given without the "com.instagram.android:id/" prefix.
 */
object InstagramRules {
    const val PACKAGE: String = "com.instagram.android"
    private const val ID_SEPARATOR: String = ":id/"

    /** The vertical pager that hosts full-screen reels. */
    val reelViewerIds: List<String> = listOf("clips_viewer_view_pager", "clips_view_pager")

    /** Reel chrome; at least one must be on screen with the pager to count as a reel viewer. */
    val reelUiIds: List<String> = listOf(
        "clips_ufi_component",
        "clips_author_username",
        "clips_captions_component",
        "clips_video_container",
    )

    /** Direct messages: thread, composer and inbox. */
    val dmMarkerIds: List<String> = listOf(
        "reply_bar_container_scroll_view",
        "direct_thread_toolbar",
        "row_thread_composer_edittext",
        "thread_message_list",
        "direct_inbox_recycler_view",
    )

    /** Explore and search. */
    val exploreMarkerIds: List<String> = listOf("action_bar_search_edit_text")

    const val REELS_TAB_ID: String = "clips_tab"
    const val HOME_TAB_ID: String = "feed_tab"

    /** Full id for findAccessibilityNodeInfosByViewId. */
    fun fullId(name: String): String = PACKAGE + ID_SEPARATOR + name

    /** True when [viewId] is [name] in Instagram's package, or any id whose part after ":id/" is [name]. */
    fun matches(viewId: String?, name: String): Boolean {
        if (viewId == null) return false
        if (viewId == fullId(name)) return true
        val separator = viewId.indexOf(ID_SEPARATOR)
        return separator >= 0 && viewId.substring(separator + ID_SEPARATOR.length) == name
    }
}
