package app.instafocus.detect

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
        assertTrue(InstagramRules.fullId("feed_tab") == "com.instagram.android:id/feed_tab")
    }
}
