package app.instafocus.detect

val SCREEN: Bounds = Bounds(0, 0, 1080, 2400)
private val SMALL: Bounds = Bounds(0, 2200, 200, 2400)

const val MAIN_TAB: String = "com.instagram.android.activity.MainTabActivity"
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

fun feed(): FakeNode = node("main_feed", bounds = SCREEN, children = arrayOf(node("row_feed_photo_profile_name")))

/** The viewer of a reel shared in a DM: sender row and message composer, no tab bar. */
fun sharedReelViewer(): FakeNode = reelViewer(
    SCREEN, true, "clips_viewer_view_pager",
    node("clips_sent_by", children = arrayOf(node("sender_username_or_fullname"), node("sender_profile_pic"))),
    node("reel_viewer_message_composer", children = arrayOf(node("reply_bar_container_scroll_view"))),
)
