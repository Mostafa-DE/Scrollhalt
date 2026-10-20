package app.instafocus.detect

/** Screen rectangle of a node, in pixels. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * The part of an accessibility node the detector needs. The service adapts
 * AccessibilityNodeInfo to this; tests use a plain fake.
 */
interface UiNode {
    val viewId: String?
    val isVisibleToUser: Boolean
    val isSelected: Boolean
    val bounds: Bounds
    val children: List<UiNode>

    /**
     * Identity of the live view this node came from (AccessibilityNodeInfo equality: window
     * plus view id), stable while the view exists. Null when unknown.
     */
    val key: Int? get() = null
}

/** Depth-first walk over this node and all its descendants. */
fun UiNode.walk(): Sequence<UiNode> = sequence {
    val stack = ArrayDeque<UiNode>()
    stack.addLast(this@walk)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        yield(node)
        node.children.asReversed().forEach { stack.addLast(it) }
    }
}

/** A node the user can actually see: visible and with a non-empty on-screen area. */
fun UiNode.isOnScreen(): Boolean = isVisibleToUser && !bounds.isEmpty

/** First on-screen node whose id matches one of [names] (see [InstagramRules.matches]). */
fun UiNode.findOnScreen(names: List<String>): UiNode? =
    walk().firstOrNull { node -> node.isOnScreen() && names.any { InstagramRules.matches(node.viewId, it) } }

/** Resource ids of every on-screen node, in tree order, without duplicates. */
fun UiNode.onScreenIds(): List<String> =
    walk().filter { it.isOnScreen() }.mapNotNull { it.viewId }.distinct().toList()
