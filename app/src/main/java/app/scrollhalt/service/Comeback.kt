package app.scrollhalt.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.scrollhalt.R
import app.scrollhalt.data.DeviceClock
import app.scrollhalt.data.SettingsRepository
import app.scrollhalt.protect.EnabledServices
import app.scrollhalt.protect.OffForApp
import app.scrollhalt.ui.accessibilitySettingsIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Brings Scrollhalt back after it switched its service off for an app ([OffForApp]): on the
 * comeback alarm, and after a reboot while a comeback is still pending. Not exported: the alarm
 * is Scrollhalt's own PendingIntent and BOOT_COMPLETED comes from the system.
 */
class ComebackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Comeback.ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Comeback.check(app)
            } catch (e: IOException) {
                Log.e(LOG_TAG, "comeback check failed", e)
            } catch (e: RuntimeException) {
                Log.e(LOG_TAG, "comeback check failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * The comeback: an inexact alarm (no exact-alarm permission) 5 minutes after the switch-off.
 * When it is due, Scrollhalt turns its service back on itself if `WRITE_SECURE_SETTINGS` was
 * granted over adb, otherwise it posts a reminder (only with notification permission).
 */
internal object Comeback {
    const val ACTION: String = "app.scrollhalt.action.COMEBACK"
    private const val CHANNEL_ID: String = "reminders"
    private const val REMINDER_ID: Int = 1

    /** Waits on (after a reboot: re-armed on this boot), or turns the service back on or reminds. */
    suspend fun check(context: Context) {
        val repository = SettingsRepository(context)
        val clock = DeviceClock(context)
        repository.rebaseOffForApp(clock.now())
        val off = repository.settings.first().offForApp ?: return
        val now = clock.now()
        if (!off.isDue(now)) {
            schedule(context, off.backAtElapsed(now))
            return
        }
        Log.i(LOG_TAG, "comeback due for ${off.packageName}")
        if (!turnBackOn(context)) remind(context)
    }

    /** The alarm at [atElapsedMillis] (elapsedRealtime); replaces an earlier one. */
    fun schedule(context: Context, atElapsedMillis: Long) {
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMillis, alarmIntent(context))
        Log.d(LOG_TAG, "comeback alarm in ${atElapsedMillis - SystemClock.elapsedRealtime()} ms")
    }

    /** The service is back: no alarm and no reminder any more. */
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        context.getSystemService(NotificationManager::class.java).cancel(REMINDER_ID)
    }

    /** `WRITE_SECURE_SETTINGS` was granted (only possible over adb): Scrollhalt can turn itself back on. */
    fun canTurnBackOn(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Adds the service to the enabled accessibility services; false without the permission or if refused. */
    fun turnBackOn(context: Context): Boolean {
        if (!canTurnBackOn(context)) return false
        val resolver = context.contentResolver
        val ours = ComponentName(context, ScrollhaltService::class.java).flattenToString()
        return try {
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, EnabledServices.withService(enabled, ours))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(LOG_TAG, "service turned back on")
            true
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "turning the service back on was refused: ${e.javaClass.simpleName}")
            false
        }
    }

    /** "Scrollhalt is off": opens Accessibility settings. Nothing without notification permission. */
    private fun remind(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(LOG_TAG, "reminder skipped: no notification permission")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(context, 0, accessibilitySettingsIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(context.getString(R.string.reminder_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(REMINDER_ID, notification)
        Log.i(LOG_TAG, "reminder posted")
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ComebackReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
