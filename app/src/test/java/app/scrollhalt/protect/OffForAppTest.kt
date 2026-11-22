package app.scrollhalt.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class OffForAppTest {
    private val zone = ZoneId.of("Asia/Amman")
    private val start = Moment(elapsedMillis = 50_000, wallMillis = 1_000_000_000, bootCount = 7, zone = zone)
    private val fiveMinutes = 5 * 60_000L
    private val bank = "com.example.bank"

    private fun later(millis: Long, from: Moment = start): Moment =
        from.copy(elapsedMillis = from.elapsedMillis + millis, wallMillis = from.wallMillis + millis)

    @Test
    fun `switching off records the app and a comeback five minutes later`() {
        val off = OffForApp.start(bank, start)
        assertEquals(bank, off.packageName)
        assertEquals(fiveMinutes, OffForApp.COMEBACK_MILLIS)
        assertEquals(start.wallMillis, off.comeback.startedWallMillis)
        assertEquals(start.wallMillis + fiveMinutes, off.comeback.endWallMillis)
        assertEquals(start.elapsedMillis + fiveMinutes, off.comeback.endElapsedMillis)
        assertEquals(7, off.comeback.bootCount)
    }

    @Test
    fun `the comeback is due after five minutes`() {
        val off = OffForApp.start(bank, start)
        assertFalse(off.isDue(start))
        assertFalse(off.isDue(later(fiveMinutes - 1)))
        assertTrue(off.isDue(later(fiveMinutes)))
        assertTrue(off.isDue(later(fiveMinutes + 60_000)))
    }

    @Test
    fun `on the same boot the alarm time stays put however often it is asked`() {
        val off = OffForApp.start(bank, start)
        assertEquals(start.elapsedMillis + fiveMinutes, off.backAtElapsed(start))
        assertEquals(start.elapsedMillis + fiveMinutes, off.backAtElapsed(later(2 * 60_000)))
    }

    @Test
    fun `on the same boot a wall clock jump moves neither the alarm nor the shown time`() {
        val off = OffForApp.start(bank, start)
        val jumped = later(60_000).copy(wallMillis = start.wallMillis + 3_600_000)
        assertEquals(start.elapsedMillis + fiveMinutes, off.backAtElapsed(jumped))
        assertEquals(jumped.wallMillis + 4 * 60_000, off.backAtWall(jumped))
        assertFalse(off.isDue(jumped))
    }

    @Test
    fun `the shown time is the wall clock end`() {
        val off = OffForApp.start(bank, start)
        assertEquals(start.wallMillis + fiveMinutes, off.backAtWall(start))
        assertEquals(start.wallMillis + fiveMinutes, off.backAtWall(later(3 * 60_000)))
    }

    @Test
    fun `when due the alarm and the shown time are now`() {
        val off = OffForApp.start(bank, start)
        val due = later(fiveMinutes + 30_000)
        assertEquals(due.elapsedMillis, off.backAtElapsed(due))
        assertEquals(due.wallMillis, off.backAtWall(due))
    }

    @Test
    fun `after a reboot the alarm is set on the new boot from what the wall clock says is left`() {
        val off = OffForApp.start(bank, start)
        val booted = Moment(elapsedMillis = 20_000, wallMillis = start.wallMillis + 2 * 60_000, bootCount = 8, zone = zone)
        assertFalse(off.isDue(booted))
        assertEquals(20_000L + 3 * 60_000, off.backAtElapsed(booted))
    }

    @Test
    fun `a reboot after the comeback time makes it due at once`() {
        val off = OffForApp.start(bank, start)
        val booted = Moment(elapsedMillis = 20_000, wallMillis = start.wallMillis + 9 * 60_000, bootCount = 8, zone = zone)
        assertTrue(off.isDue(booted))
        assertEquals(booted.elapsedMillis, off.backAtElapsed(booted))
    }

    @Test
    fun `after a reboot with the clock set back the rebased comeback still ends within five minutes`() {
        val off = OffForApp.start(bank, start)
        val booted = Moment(elapsedMillis = 10_000, wallMillis = start.wallMillis - 3_600_000, bootCount = 8, zone = zone)
        val rebased = off.copy(comeback = off.comeback.rebased(booted))
        assertEquals(booted.elapsedMillis + fiveMinutes, rebased.backAtElapsed(booted))
        assertEquals(booted.elapsedMillis + fiveMinutes, rebased.backAtElapsed(later(60_000, booted)))
        assertTrue(rebased.isDue(later(fiveMinutes, booted)))
    }

    @Test
    fun `before the comeback the check waits until the comeback time`() {
        val off = OffForApp.start(bank, start)
        assertEquals(ComebackStep.Wait(start.elapsedMillis + fiveMinutes), off.nextStep(start, serviceEnabled = false))
        assertEquals(ComebackStep.Wait(start.elapsedMillis + fiveMinutes), off.nextStep(later(4 * 60_000), serviceEnabled = false))
    }

    @Test
    fun `a check after a reboot waits on the new boot's clock`() {
        val off = OffForApp.start(bank, start)
        val booted = Moment(elapsedMillis = 20_000, wallMillis = start.wallMillis + 2 * 60_000, bootCount = 8, zone = zone)
        assertEquals(ComebackStep.Wait(20_000L + 3 * 60_000), off.nextStep(booted, serviceEnabled = false))
    }

    @Test
    fun `when due the check comes back and tries again five minutes later if nothing worked`() {
        val off = OffForApp.start(bank, start)
        val due = later(fiveMinutes + 30_000)
        assertEquals(ComebackStep.Due(retryAtElapsedMillis = due.elapsedMillis + fiveMinutes), off.nextStep(due, serviceEnabled = false))
    }

    @Test
    fun `a switch-off is forgotten once the service is enabled again, due or not`() {
        // The service was turned back on (or disableSelf did not take effect): no reminder.
        val off = OffForApp.start(bank, start)
        assertEquals(ComebackStep.Forget, off.nextStep(start, serviceEnabled = true))
        assertEquals(ComebackStep.Forget, off.nextStep(later(fiveMinutes + 1), serviceEnabled = true))
    }

    @Test
    fun `the service list gets Scrollhalt when it is empty`() {
        assertEquals(OURS, EnabledServices.withService(null, OURS))
        assertEquals(OURS, EnabledServices.withService("", OURS))
    }

    @Test
    fun `other services are kept in order and Scrollhalt is appended`() {
        assertEquals("$TALKBACK:$OURS", EnabledServices.withService(TALKBACK, OURS))
        assertEquals("$TALKBACK:$OTHER:$OURS", EnabledServices.withService("$TALKBACK:$OTHER", OURS))
    }

    @Test
    fun `Scrollhalt is not added twice`() {
        assertEquals("$TALKBACK:$OURS", EnabledServices.withService("$TALKBACK:$OURS", OURS))
        assertEquals("$OURS:$TALKBACK", EnabledServices.withService("$OURS:$TALKBACK", OURS))
    }

    @Test
    fun `the short class form counts as Scrollhalt too`() {
        val short = "app.scrollhalt/.service.ScrollhaltService"
        assertEquals("$TALKBACK:$short", EnabledServices.withService("$TALKBACK:$short", OURS))
    }

    @Test
    fun `empty entries are dropped`() {
        assertEquals("$TALKBACK:$OURS", EnabledServices.withService(":$TALKBACK::", OURS))
    }

    private companion object {
        const val OURS = "app.scrollhalt/app.scrollhalt.service.ScrollhaltService"
        const val TALKBACK = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"
        const val OTHER = "com.example.other/.OtherService"
    }
}
