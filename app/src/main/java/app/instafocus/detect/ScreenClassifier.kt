package app.instafocus.detect

/**
 * Decides which Instagram surface is on screen. Instagram keeps the Reels tab (its pager
 * and controls, flagged visible, full screen) alive behind every other screen, so the
 * reel tree alone proves nothing. The signals that do are the bottom tab bar (on screen
 * on tab roots, gone inside a viewer, thread or modal), which tab is selected, DM markers
 * and the current activity.
 */
object ScreenClassifier {
    fun classify(root: UiNode, activity: String?): Classification {
        val tabBar = root.findOnScreen(listOf(InstagramRules.TAB_BAR_ID))
        val selectedTab = tabBar?.let(::selectedTab)
        val inMainTabs = activity == null || InstagramRules.isActivity(activity, InstagramRules.MAIN_TAB_ACTIVITY)
        val reelsTabSelected = root.walk().any { it.isSelected && InstagramRules.matches(it.viewId, InstagramRules.REELS_TAB_ID) }
        val reelInFront = tabBar == null && !reelsTabSelected && root.findOnScreen(InstagramRules.reelViewerIds) != null
        val reelUiOnScreen = root.findOnScreen(InstagramRules.reelUiIds) != null
        val sharedSignal = InstagramRules.isActivity(activity, InstagramRules.MODAL_ACTIVITY) ||
            root.findOnScreen(InstagramRules.sharedReelMarkerIds) != null

        val screen = when {
            selectedTab == InstagramRules.REELS_TAB_ID && inMainTabs -> Screen.REELS_TAB
            reelInFront && reelUiOnScreen && sharedSignal -> Screen.SINGLE_REEL
            root.findOnScreen(InstagramRules.dmMarkerIds) != null -> Screen.DM
            reelInFront && reelUiOnScreen -> Screen.SINGLE_REEL
            reelInFront -> Screen.UNKNOWN
            else -> Screen.OTHER
        }
        return Classification(
            screen = screen,
            sharedInDm = screen == Screen.SINGLE_REEL && sharedSignal,
            selectedTab = selectedTab,
            activity = activity,
        )
    }

    private fun selectedTab(tabBar: UiNode): String? =
        tabBar.walk()
            .firstOrNull { it.isSelected && it.isOnScreen() && InstagramRules.tabIds.any { name -> InstagramRules.matches(it.viewId, name) } }
            ?.let { node -> InstagramRules.tabIds.first { InstagramRules.matches(node.viewId, it) } }
}
