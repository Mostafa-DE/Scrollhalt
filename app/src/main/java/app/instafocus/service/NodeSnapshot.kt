package app.instafocus.service

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import app.instafocus.detect.Bounds
import app.instafocus.detect.UiNode

/**
 * Immutable copy of the on-screen part of an accessibility tree, adapted to [UiNode].
 * Copying lets every live AccessibilityNodeInfo be released right after it is read, and
 * keeps the detector free of stale-node exceptions. Invisible subtrees are skipped
 * (Instagram keeps every tab loaded, so this is most of the tree).
 */
data class NodeSnapshot(
    override val viewId: String?,
    override val isVisibleToUser: Boolean,
    override val isSelected: Boolean,
    override val bounds: Bounds,
    override val children: List<UiNode>,
) : UiNode {
    companion object {
        private const val MAX_DEPTH: Int = 60
        private const val MAX_NODES: Int = 3_000

        /** Copies [root]. The caller still owns [root]; every child is released here. */
        fun of(root: AccessibilityNodeInfo): NodeSnapshot? = Builder().copy(root, depth = 0)
    }

    private class Builder {
        private var count = 0
        private val rect = Rect()

        fun copy(node: AccessibilityNodeInfo, depth: Int): NodeSnapshot? = try {
            count++
            val visible = node.isVisibleToUser
            node.getBoundsInScreen(rect)
            val children = if (visible && depth < MAX_DEPTH) copyChildren(node, depth) else emptyList()
            NodeSnapshot(
                viewId = node.viewIdResourceName,
                isVisibleToUser = visible,
                isSelected = node.isSelected,
                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                children = children,
            )
        } catch (_: RuntimeException) {
            // The node went stale while we were reading it.
            null
        }

        private fun copyChildren(node: AccessibilityNodeInfo, depth: Int): List<NodeSnapshot> =
            (0 until node.childCount).mapNotNull { index ->
                if (count >= MAX_NODES) return@mapNotNull null
                val child = node.getChild(index) ?: return@mapNotNull null
                try {
                    copy(child, depth + 1)
                } finally {
                    child.release()
                }
            }
    }
}

/** Returns the node to the system pool on API levels that still pool nodes. */
fun AccessibilityNodeInfo.release() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        recycle()
    }
}
