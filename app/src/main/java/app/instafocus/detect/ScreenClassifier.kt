package app.instafocus.detect

/**
 * Decides which Instagram surface is on screen. Only nodes the user can see count:
 * Instagram keeps every tab loaded at once, so the mere presence of an id means nothing.
 */
object ScreenClassifier {
    fun classify(root: UiNode): Classification {
        val viewerOnScreen = root.findOnScreen(InstagramRules.reelViewerIds) != null
        val reelUiOnScreen = root.findOnScreen(InstagramRules.reelUiIds) != null
        val dmOnScreen = root.findOnScreen(InstagramRules.dmMarkerIds) != null
        val screen = when {
            viewerOnScreen && reelUiOnScreen -> Screen.REEL_VIEWER
            viewerOnScreen -> Screen.UNKNOWN
            dmOnScreen -> Screen.DM
            root.findOnScreen(InstagramRules.exploreMarkerIds) != null -> Screen.EXPLORE
            else -> Screen.OTHER
        }
        return Classification(
            screen = screen,
            reelsTabSelected = isReelsTabSelected(root),
            dmOverlay = screen == Screen.REEL_VIEWER && dmOnScreen,
        )
    }

    private fun isReelsTabSelected(root: UiNode): Boolean =
        root.walk().any { node ->
            node.isSelected && node.isOnScreen() && InstagramRules.matches(node.viewId, InstagramRules.REELS_TAB_ID)
        }
}
