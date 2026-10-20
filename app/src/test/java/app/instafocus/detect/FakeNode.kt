package app.instafocus.detect

val SCREEN: Bounds = Bounds(0, 0, 1080, 2400)
private val SMALL: Bounds = Bounds(0, 2200, 200, 2400)

const val MAIN_TAB: String = "com.instagram.android.activity.MainTabActivity"
const val INSTAGRAM_MAIN: String = "com.instagram.mainactivity.InstagramMainActivity"
const val MODAL: String = "com.instagram.modal.TransparentModalActivity"

data class FakeNode(
    override val viewId: String? = null,
    override val isVisibleToUser: Boolean = true,
    override val isSelected: Boolean = false,
    override val bounds: Bounds = SMALL,
    override val children: List<UiNode> = emptyList(),
) : UiNode

fun ig(name: String): String = "com.instagram.android:id/$name"

fun node(
    name: String,
    visible: Boolean = true,
    selected: Boolean = false,
    bounds: Bounds = SMALL,
    vararg children: UiNode,
): FakeNode = FakeNode(ig(name), visible, selected, bounds, children.toList())

fun root(vararg children: UiNode): FakeNode = FakeNode(bounds = SCREEN, children = children.toList())

/** Bottom tab bar as on Instagram 451, with [selected] carrying the selected flag. */
fun tabBar(selected: String = "feed_tab", visible: Boolean = true): FakeNode = node(
    "tab_bar", visible, bounds = SMALL,
    children = InstagramRules.tabIds.map { node(it, visible, selected = it == selected) }.toTypedArray(),
)

/** Tab buttons as the 451 accessibility tree has them: siblings under the root, no tab_bar node. */
fun tabButtons(selected: String? = null): Array<UiNode> =
    InstagramRules.tabIds.map { node(it, selected = it == selected) }.toTypedArray()

/** A tab page the main ViewPager keeps off screen: not visible to accessibility, empty bounds. */
fun offscreenReelsPage(): FakeNode = node(
    "clips_viewer_view_pager", visible = false, bounds = Bounds(1080, 112, 1080, 2163),
    children = arrayOf(node("clips_ufi_component", visible = false)),
)

/** A reel pager with its controls, as Instagram keeps it alive, visible, behind other screens. */
fun reelViewer(
    bounds: Bounds = SCREEN,
    withUi: Boolean = true,
    pagerName: String = "clips_viewer_view_pager",
    vararg extra: UiNode,
): FakeNode = node(
    "root_clips_layout", bounds = bounds, children = arrayOf(
        node(
            pagerName, bounds = bounds, children = if (withUi) {
                arrayOf(
                    node("clips_video_container", bounds = bounds),
                    node("clips_ufi_component"),
                    node("clips_author_username"),
                    node("clips_caption_component"),
                    *extra,
                )
            } else {
                emptyArray()
            },
        ),
    ),
)

fun dmThread(): FakeNode = node(
    "direct_thread", bounds = SCREEN, children = arrayOf(
        node("direct_thread_header"),
        node("row_thread_composer_container", children = arrayOf(node("row_thread_composer_edittext"))),
        node("message_composer_reply_bar_container"),
    ),
)

fun dmInbox(): FakeNode = node("direct_inbox_recycler_view", bounds = SCREEN)

/** Explore grid as dumped on 451: recycler_view of layout_container cells with image_preview. */
fun exploreGrid(cells: Int = 9): FakeNode = node(
    "swipeable_tab_view_pager", bounds = SCREEN, children = arrayOf(
        node("pill_bar_rv"),
        node(
            "recycler_view", bounds = SCREEN,
            children = (0 until cells).map { node("layout_container", children = arrayOf(node("image_preview"))) }.toTypedArray(),
        ),
        node("explore_action_bar_secondary_button_stub"),
    ),
)

/** Search results while typing: user and keyword rows, no grid cells. */
fun searchResults(): FakeNode = node(
    "search_results", bounds = SCREEN, children = arrayOf(
        node("action_bar_search_edit_text"),
        node("row_search_user_container", children = arrayOf(node("row_search_user_username"), node("row_search_user_fullname"))),
        node("row_search_keyword_title"),
    ),
)

/** Another user's profile (opened from search): tab buttons not in the tree. */
fun profilePage(): FakeNode = node(
    "profile", bounds = SCREEN, children = arrayOf(
        node("row_profile_header_imageview_frame_layout"),
        node("profile_header_follow_button"),
        node("profile_tab_layout"),
        node("profile_viewpager", bounds = SCREEN),
    ),
)

fun feed(): FakeNode = node("main_feed", bounds = SCREEN, children = arrayOf(node("row_feed_photo_profile_name")))

/** The viewer of a reel shared in a DM: sender row and message composer, no tab bar. */
fun sharedReelViewer(): FakeNode = reelViewer(
    SCREEN, true, "clips_viewer_view_pager",
    node("clips_sent_by", children = arrayOf(node("sender_username_or_fullname"), node("sender_profile_pic"))),
    node("reel_viewer_message_composer", children = arrayOf(node("reply_bar_container_scroll_view"))),
)
