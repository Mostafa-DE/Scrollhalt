package app.scrollhalt.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramRulesTest {
    @Test
    fun `matches full id`() {
        assertTrue(InstagramRules.matches("com.instagram.android:id/clips_tab", "clips_tab"))
    }

    @Test
    fun `matches suffix after id separator for other packages`() {
        assertTrue(InstagramRules.matches("com.instagram.clone:id/clips_tab", "clips_tab"))
    }

    @Test
    fun `does not match partial names or null`() {
        assertFalse(InstagramRules.matches("com.instagram.android:id/clips_tab_icon", "clips_tab"))
        assertFalse(InstagramRules.matches("clips_tab", "clips_tab"))
        assertFalse(InstagramRules.matches(null, "clips_tab"))
    }

    @Test
    fun `full id adds the package prefix`() {
        assertEquals("com.instagram.android:id/feed_tab", InstagramRules.fullId("feed_tab"))
    }

    @Test
    fun `activity matches by full or simple name`() {
        assertTrue(InstagramRules.isActivity(MODAL, InstagramRules.MODAL_ACTIVITY))
        assertTrue(InstagramRules.isActivity("x.y.TransparentModalActivity", InstagramRules.MODAL_ACTIVITY))
        assertFalse(InstagramRules.isActivity(MAIN_TAB, InstagramRules.MODAL_ACTIVITY))
        assertFalse(InstagramRules.isActivity(INSTAGRAM_MAIN, InstagramRules.MODAL_ACTIVITY))
        assertTrue(INSTAGRAM_MAIN in InstagramRules.mainActivities)
        assertFalse(InstagramRules.isActivity(null, InstagramRules.MODAL_ACTIVITY))
    }
}
