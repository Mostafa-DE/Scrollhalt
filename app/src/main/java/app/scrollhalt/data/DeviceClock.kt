package app.scrollhalt.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.scrollhalt.protect.Clock
import app.scrollhalt.protect.Moment
import java.time.ZoneId

/** The device clocks. The boot count is read once: a reboot always starts a new process. */
class DeviceClock(context: Context) : Clock {
    private val bootCount: Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    override fun now(): Moment = Moment(SystemClock.elapsedRealtime(), System.currentTimeMillis(), bootCount, ZoneId.systemDefault())
}
