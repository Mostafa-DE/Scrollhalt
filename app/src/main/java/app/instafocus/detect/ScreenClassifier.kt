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
        val tabButtons = root.walk()
            .filter { node -> node.isOnScreen() && InstagramRules.tabIds.any { InstagramRules.matches(node.viewId, it) } }
            .toList()
        val tabBarVisible = tabButtons.isNotEmpty() || root.findOnScreen(listOf(InstagramRules.TAB_BAR_ID)) != null
        val selectedTab = tabButtons.firstOrNull { it.isSelected }
            ?.let { node -> InstagramRules.tabIds.first { InstagramRules.matches(node.viewId, it) } }
        val pagerOnScreen = root.findOnScreen(InstagramRules.reelViewerIds) != null
        val reelsTabSelected = root.walk().any { it.isSelected && InstagramRules.matches(it.viewId, InstagramRules.REELS_TAB_ID) }
        val reelInFront = !tabBarVisible && !reelsTabSelected && pagerOnScreen
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
            else -> Screen.OTHER
        }
        return Classification(
            screen = screen,
            sharedInDm = screen == Screen.SINGLE_REEL && sharedSignal,
            tabBarVisible = tabBarVisible,
            selectedTab = selectedTab,
            activity = activity,
        )
    }
}
