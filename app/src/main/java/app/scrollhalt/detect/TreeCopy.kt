package app.scrollhalt.detect

/** Immutable node of a copied tree. */
data class SnapshotNode(
    override val viewId: String?,
    override val isVisibleToUser: Boolean,
    override val isSelected: Boolean,
    override val bounds: Bounds,
    override val children: List<UiNode>,
    override val key: Int? = null,
) : UiNode

/** Read access to a live tree (AccessibilityNodeInfo in the service, a fake in tests). */
interface NodeSource<N> {
    fun viewId(node: N): String?
    fun isVisibleToUser(node: N): Boolean
    fun isSelected(node: N): Boolean
    fun bounds(node: N): Bounds
    fun key(node: N): Int
    fun childCount(node: N): Int
    fun child(node: N, index: Int): N?
    fun release(node: N)
}

/**
 * Copies the tree under [root] into immutable [SnapshotNode]s. Every child obtained is
 * released; the caller still owns [root]. Subtrees of nodes not visible to the user are
 * skipped (the node itself is kept, so a hidden tab still reports its selected flag).
 * A node that goes stale while being read (throws) is dropped with its subtree.
 */
fun <N> copyTree(root: N, source: NodeSource<N>, maxDepth: Int = 60, maxNodes: Int = 3_000): SnapshotNode? {
    var count = 0

    fun copy(node: N, depth: Int): SnapshotNode? = try {
        count++
        val visible = source.isVisibleToUser(node)
        // Read everything about this node before visiting children.
        val viewId = source.viewId(node)
        val selected = source.isSelected(node)
        val bounds = source.bounds(node)
        val key = source.key(node)
        val children = if (visible && depth < maxDepth) {
            (0 until source.childCount(node)).mapNotNull { index ->
                if (count >= maxNodes) return@mapNotNull null
                val child = source.child(node, index) ?: return@mapNotNull null
                try {
                    copy(child, depth + 1)
                } finally {
                    source.release(child)
                }
            }
        } else {
            emptyList()
        }
        SnapshotNode(viewId, visible, selected, bounds, children, key)
    } catch (_: RuntimeException) {
        null
    }

    return copy(root, depth = 0)
}
