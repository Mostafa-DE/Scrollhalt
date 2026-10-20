package app.instafocus.detect

val SCREEN: Bounds = Bounds(0, 0, 1440, 3120)
private val SMALL: Bounds = Bounds(0, 2900, 200, 3120)

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

fun tabBar(reelsSelected: Boolean = false, visible: Boolean = true): FakeNode = node(
    "tab_bar", visible, bounds = SMALL, children = arrayOf(
        node("feed_tab", visible, selected = !reelsSelected),
        node("clips_tab", visible, selected = reelsSelected),
    ),
)

fun reelViewer(
    viewerVisible: Boolean = true,
    withUi: Boolean = true,
    pagerName: String = "clips_viewer_view_pager",
): FakeNode = node(
    pagerName, viewerVisible, bounds = SCREEN, children = if (withUi) {
        arrayOf(node("clips_ufi_component", viewerVisible), node("clips_author_username", viewerVisible))
    } else {
        emptyArray()
    },
)

fun dmThread(): FakeNode = node(
    "direct_thread", bounds = SCREEN, children = arrayOf(
        node("direct_thread_toolbar"),
        node("thread_message_list", bounds = SCREEN),
        node("row_thread_composer_edittext"),
    ),
)

fun exploreGrid(): FakeNode = node("explore", bounds = SCREEN, children = arrayOf(node("action_bar_search_edit_text")))

fun feed(): FakeNode = node("main_feed", bounds = SCREEN, children = arrayOf(node("row_feed_photo_profile_name")))
