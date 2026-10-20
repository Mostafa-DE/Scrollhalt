package app.instafocus.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeCopyTest {
    /** A live-tree stand-in. Like AccessibilityNodeInfo, it reports bounds into one shared, mutable rect. */
    private class Live(
        val id: String,
        val bounds: Bounds,
        val visible: Boolean = true,
        val selected: Boolean = false,
        val children: List<Live> = emptyList(),
        val stale: Boolean = false,
    )

    private class Source : NodeSource<Live> {
        val shared = IntArray(4)
        val released = mutableListOf<String>()

        override fun viewId(node: Live): String? = node.id
        override fun isVisibleToUser(node: Live): Boolean = if (node.stale) throw IllegalStateException() else node.visible
        override fun isSelected(node: Live): Boolean = node.selected
        override fun childCount(node: Live): Int = node.children.size
        override fun child(node: Live, index: Int): Live = node.children[index]
        override fun release(node: Live) {
            released += node.id
        }

        override fun bounds(node: Live): Bounds {
            shared[0] = node.bounds.left
            shared[1] = node.bounds.top
            shared[2] = node.bounds.right
            shared[3] = node.bounds.bottom
            return Bounds(shared[0], shared[1], shared[2], shared[3])
        }
    }

    private val tab = Live("clips_tab", Bounds(216, 2163, 432, 2298), selected = true)
    private val tree = Live(
        "root", Bounds(0, 0, 1080, 2340),
        children = listOf(Live("pager", Bounds(0, 112, 1080, 2163), children = listOf(Live("leaf", Bounds(1, 2, 3, 4)))), tab),
    )

    @Test
    fun `each node keeps its own bounds, not its last descendant's`() {
        // Regression: parents used to get the bounds of their last child (shared Rect read too late).
        val copy = copyTree(tree, Source())
        assertEquals(Bounds(0, 0, 1080, 2340), copy?.bounds)
        assertEquals(Bounds(0, 112, 1080, 2163), copy?.children?.get(0)?.bounds)
        assertEquals(Bounds(216, 2163, 432, 2298), copy?.children?.get(1)?.bounds)
        assertTrue(copy?.children?.get(1)?.isSelected == true)
    }

    @Test
    fun `every child is released and the root is not`() {
        val source = Source()
        copyTree(tree, source)
        assertEquals(listOf("leaf", "pager", "clips_tab"), source.released)
    }

    @Test
    fun `hidden nodes are kept without their subtree`() {
        val hidden = Live("tab_bar", Bounds(0, 0, 0, 0), visible = false, children = listOf(tab))
        val copy = copyTree(Live("root", Bounds(0, 0, 10, 10), children = listOf(hidden)), Source())
        val kept = copy?.children?.single()
        assertEquals("tab_bar", kept?.viewId)
        assertEquals(emptyList<UiNode>(), kept?.children)
    }

    @Test
    fun `stale nodes are dropped`() {
        val copy = copyTree(Live("root", Bounds(0, 0, 10, 10), children = listOf(Live("gone", Bounds(0, 0, 1, 1), stale = true), tab)), Source())
        assertEquals(listOf("clips_tab"), copy?.children?.map { it.viewId })
        assertNull(copyTree(Live("x", Bounds(0, 0, 1, 1), stale = true), Source()))
    }

    @Test
    fun `node limit stops the copy`() {
        val wide = Live("root", Bounds(0, 0, 10, 10), children = (0 until 10).map { Live("n$it", Bounds(0, 0, 1, 1)) })
        assertEquals(3, copyTree(wide, Source(), maxNodes = 4)?.children?.size)
    }
}
