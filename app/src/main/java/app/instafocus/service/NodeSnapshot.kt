package app.instafocus.service

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import app.instafocus.detect.Bounds
import app.instafocus.detect.NodeSource
import app.instafocus.detect.SnapshotNode
import app.instafocus.detect.copyTree

/**
 * Copies a live accessibility tree into an immutable snapshot, so every
 * AccessibilityNodeInfo is released right after it is read and the detector never sees a
 * stale node.
 */
object NodeSnapshot {
    /** The caller still owns [root]. */
    fun of(root: AccessibilityNodeInfo): SnapshotNode? = copyTree(root, AccessibilityNodeSource())

    private class AccessibilityNodeSource : NodeSource<AccessibilityNodeInfo> {
        private val rect = Rect()

        override fun viewId(node: AccessibilityNodeInfo): String? = node.viewIdResourceName
        override fun isVisibleToUser(node: AccessibilityNodeInfo): Boolean = node.isVisibleToUser
        override fun isSelected(node: AccessibilityNodeInfo): Boolean = node.isSelected
        override fun childCount(node: AccessibilityNodeInfo): Int = node.childCount
        override fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = node.getChild(index)
        override fun release(node: AccessibilityNodeInfo) = node.release()
        override fun key(node: AccessibilityNodeInfo): Int = node.hashCode()

        /** Returns a value copy: the shared Rect must never leak past this call. */
        override fun bounds(node: AccessibilityNodeInfo): Bounds {
            node.getBoundsInScreen(rect)
            return Bounds(rect.left, rect.top, rect.right, rect.bottom)
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
