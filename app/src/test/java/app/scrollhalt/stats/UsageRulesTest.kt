package app.scrollhalt.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageRulesTest {
    @Test
    fun `instagram in front`() {
        assertEquals(Front.INSTAGRAM, UsageRules.front("com.instagram.android", inputMethod = false))
    }

    @Test
    fun `shade, keyboards and dialogs do not leave instagram`() {
        listOf("com.android.systemui", "com.google.android.permissioncontroller", "com.samsung.android.honeyboard").forEach {
            assertEquals(it, Front.OVER_INSTAGRAM, UsageRules.front(it, inputMethod = false))
        }
        assertEquals(Front.OVER_INSTAGRAM, UsageRules.front("org.some.keyboard", inputMethod = true))
    }

    @Test
    fun `the launcher and other apps leave it`() {
        assertEquals(Front.ELSEWHERE, UsageRules.front("com.sec.android.app.launcher", inputMethod = false))
        assertEquals(Front.ELSEWHERE, UsageRules.front("app.scrollhalt", inputMethod = false))
    }

    @Test
    fun `no window changes nothing`() {
        assertEquals(Front.UNKNOWN, UsageRules.front(null, inputMethod = false))
    }
}
