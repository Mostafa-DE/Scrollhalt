package app.instafocus.detect

/**
 * Every Instagram view id and activity name InstaFocus relies on. When an Instagram
 * update renames something, this is the only file that needs to change (use the debug
 * snapshot to find the new names). View names are given without the
 * "com.instagram.android:id/" prefix. Confirmed on Instagram 451.0.0.53.80.
 */
object InstagramRules {
    const val PACKAGE: String = "com.instagram.android"
    private const val ID_SEPARATOR: String = ":id/"

    /** Activity hosting the bottom tabs, and the Reels tab, Explore and feed-opened reels. */
    const val MAIN_TAB_ACTIVITY: String = "com.instagram.android.activity.MainTabActivity"

    /** Activity a reel shared in a DM opens in. */
    const val MODAL_ACTIVITY: String = "com.instagram.modal.TransparentModalActivity"

    /**
     * The vertical reel pager (a ViewPager2). Instagram keeps the Reels tab's pager alive,
     * "visible", behind other screens, so its presence alone never means "watching reels".
     */
    val reelViewerIds: List<String> = listOf("clips_viewer_view_pager", "clips_view_pager")

    /** Reel chrome; at least one must be on screen with the pager. */
    val reelUiIds: List<String> = listOf(
        "clips_ufi_component",
        "clips_author_username",
        "clips_caption_component",
        "clips_captions_component",
        "clips_video_container",
        "clips_viewer_action_bar",
    )

    /** Only on screen in the viewer of a reel shared in a DM. Ids only: their text is never read. */
    val sharedReelMarkerIds: List<String> = listOf(
        "clips_sent_by",
        "reel_viewer_message_composer",
        "reply_bar_container_scroll_view",
        "sender_username_or_fullname",
    )

    /** DM thread and inbox. */
    val dmMarkerIds: List<String> = listOf(
        "direct_thread_header",
        "row_thread_composer_edittext",
        "message_composer_reply_bar_container",
        "row_thread_composer_container",
        "direct_thread_toolbar",
        "thread_message_list",
        "direct_inbox_recycler_view",
    )

    const val TAB_BAR_ID: String = "tab_bar"
    const val REELS_TAB_ID: String = "clips_tab"
    const val HOME_TAB_ID: String = "feed_tab"

    /** Bottom tabs, in bar order; used to log which one is selected. */
    val tabIds: List<String> = listOf("feed_tab", "clips_tab", "direct_tab", "search_tab", "profile_tab")

    /** Full id for findAccessibilityNodeInfosByViewId. */
    fun fullId(name: String): String = PACKAGE + ID_SEPARATOR + name

    /** True when [viewId] is [name] in Instagram's package, or any id whose part after ":id/" is [name]. */
    fun matches(viewId: String?, name: String): Boolean {
        if (viewId == null) return false
        if (viewId == fullId(name)) return true
        val separator = viewId.indexOf(ID_SEPARATOR)
        return separator >= 0 && viewId.substring(separator + ID_SEPARATOR.length) == name
    }

    /** True when [activity] is [expected], by full class name or by simple name. */
    fun isActivity(activity: String?, expected: String): Boolean =
        activity != null && (activity == expected || activity.substringAfterLast('.') == expected.substringAfterLast('.'))
}
