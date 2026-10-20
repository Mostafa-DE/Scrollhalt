package app.scrollhalt.detect

/**
 * Every Instagram view id and activity name Scrollhalt relies on. When an Instagram
 * update renames something, this is the only file that needs to change (use the debug
 * snapshot to find the new names). View names are given without the
 * "com.instagram.android:id/" prefix. Confirmed on Instagram 451.0.0.53.80.
 */
object InstagramRules {
    const val PACKAGE: String = "com.instagram.android"
    private const val ID_SEPARATOR: String = ":id/"

    /**
     * Activities hosting the bottom tabs, the Reels tab and Explore/feed-opened reels.
     * dumpsys shows MainTabActivity resumed, but window-state events name
     * InstagramMainActivity. Reference only: no decision depends on them, so a renamed
     * main activity cannot silently disable blocking.
     */
    val mainActivities: List<String> = listOf(
        "com.instagram.android.activity.MainTabActivity",
        "com.instagram.mainactivity.InstagramMainActivity",
    )

    /** Activity a reel shared in a DM opens in; the only activity used as a signal. */
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

    /**
     * Bottom tab bar container. Seen in the View hierarchy (dumpsys), but on 451 the
     * accessibility tree has no such node: the tab buttons are direct children of the root.
     * So the tab bar counts as on screen when it or any tab button is on screen.
     */
    const val TAB_BAR_ID: String = "tab_bar"
    const val REELS_TAB_ID: String = "clips_tab"
    const val HOME_TAB_ID: String = "feed_tab"

    /**
     * Search/Explore tab. Observed on 451: an Explore reel runs in the main activity with
     * the tab bar gone and search_tab selected. A reel whose origin screen is unknown while
     * this tab is selected is treated as an Explore reel.
     */
    const val SEARCH_TAB_ID: String = "search_tab"

    /**
     * Explore grid: search_tab selected, tab buttons on screen, and at least
     * [EXPLORE_GRID_MIN_CELLS] grid cells on screen (`recycler_view` > `layout_container` >
     * `image_preview`, 3 columns). Search results (`row_search_user_container`,
     * `row_search_keyword_title`, ...) have no such cells and stay OTHER.
     */
    const val EXPLORE_GRID_CELL_ID: String = "image_preview"
    const val EXPLORE_GRID_MIN_CELLS: Int = 3

    /** A profile page (own or another user's, also one reached through search). */
    val profileMarkerIds: List<String> = listOf(
        "profile_viewpager",
        "profile_tab_layout",
        "row_profile_header_imageview_frame_layout",
    )

    /**
     * Tab bar fill colours for the Reels cover, sampled from Instagram 451 screenshots
     * (dark: rgb(12,15,20)). Light is not confirmed yet. Never sampled at runtime.
     */
    const val TAB_BAR_COLOR_DARK: Int = 0xFF0C0F14.toInt()
    const val TAB_BAR_COLOR_LIGHT: Int = 0xFFFFFFFF.toInt()

    /**
     * Bottom tab buttons, in bar order. On 451 a tab reports isSelected only after a tab
     * switch; after a cold start no tab is selected. Then the Reels tab is recognised by
     * its pager being on screen: tab pages live in a ViewPager (`swipeable_tab_view_pager`)
     * and pages other than the current one are not visible to accessibility.
     */
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
