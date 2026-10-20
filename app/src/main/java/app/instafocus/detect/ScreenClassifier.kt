package app.instafocus.detect

/**
 * Decides which Instagram surface is on screen. Instagram keeps the Reels tab (its pager
 * and controls, flagged visible, full screen) alive behind every other screen, so the
 * reel tree alone proves nothing. The signals that do are the bottom tab bar (on screen
 * on tab roots, gone inside a viewer, thread or modal), which tab is selected and DM
 * markers. The activity is only a positive signal for reels shared in a DM.
 */
object ScreenClassifier {
    fun classify(root: UiNode, activity: String?): Classification {
        val allTabs = root.walk().mapNotNull { node -> tabName(node)?.let { node to it } }.toList()
        val tabButtons = allTabs.filter { (node, _) -> node.isOnScreen() }
        val tabBarVisible = tabButtons.isNotEmpty() || root.findOnScreen(listOf(InstagramRules.TAB_BAR_ID)) != null
        val selectedTab = (tabButtons.firstOrNull { (node, _) -> node.isSelected } ?: allTabs.firstOrNull { (node, _) -> node.isSelected })
            ?.second
        val reelsTabBounds = tabButtons.firstOrNull { (_, name) -> name == InstagramRules.REELS_TAB_ID }?.first?.bounds
        val pagerOnScreen = root.findOnScreen(InstagramRules.reelViewerIds) != null
        val reelInFront = !tabBarVisible && selectedTab != InstagramRules.REELS_TAB_ID && pagerOnScreen
        val reelUiOnScreen = root.findOnScreen(InstagramRules.reelUiIds) != null
        val sharedSignal = InstagramRules.isActivity(activity, InstagramRules.MODAL_ACTIVITY) ||
            root.findOnScreen(InstagramRules.sharedReelMarkerIds) != null

        val screen = when {
            // Any activity: the tab bar and its selection are the signal, not the activity name.
            // With no tab selected yet (cold start), the Reels pager on screen means the Reels tab.
            tabBarVisible && selectedTab == InstagramRules.REELS_TAB_ID -> Screen.REELS_TAB
            tabBarVisible && selectedTab == null && pagerOnScreen -> Screen.REELS_TAB
            reelInFront && reelUiOnScreen && sharedSignal -> Screen.SINGLE_REEL
            root.findOnScreen(InstagramRules.dmMarkerIds) != null -> Screen.DM
            reelInFront && reelUiOnScreen -> Screen.SINGLE_REEL
            reelInFront -> Screen.UNKNOWN
            tabBarVisible && selectedTab == InstagramRules.SEARCH_TAB_ID && exploreCells(root) >= InstagramRules.EXPLORE_GRID_MIN_CELLS ->
                Screen.EXPLORE_GRID
            root.findOnScreen(InstagramRules.profileMarkerIds) != null -> Screen.PROFILE
            else -> Screen.OTHER
        }
        return Classification(
            screen = screen,
            sharedInDm = screen == Screen.SINGLE_REEL && sharedSignal,
            tabBarVisible = tabBarVisible,
            selectedTab = selectedTab,
            reelsTabBounds = reelsTabBounds,
            activity = activity,
        )
    }

    private fun exploreCells(root: UiNode): Int =
        root.walk().count { it.isOnScreen() && InstagramRules.matches(it.viewId, InstagramRules.EXPLORE_GRID_CELL_ID) }

    private fun tabName(node: UiNode): String? = InstagramRules.tabIds.firstOrNull { InstagramRules.matches(node.viewId, it) }
}
