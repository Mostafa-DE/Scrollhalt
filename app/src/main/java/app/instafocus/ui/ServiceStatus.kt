package app.instafocus.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.instafocus.service.InstaFocusService

enum class ProtectionStatus { OFF, ENABLED_NOT_RUNNING, PAUSED, PROTECTING }

fun protectionStatus(enabled: Boolean, connected: Boolean, blockingEnabled: Boolean): ProtectionStatus = when {
    !enabled -> ProtectionStatus.OFF
    !connected -> ProtectionStatus.ENABLED_NOT_RUNNING
    !blockingEnabled -> ProtectionStatus.PAUSED
    else -> ProtectionStatus.PROTECTING
}

/** True when the user has switched InstaFocus on in Accessibility settings. */
fun isServiceEnabled(context: Context): Boolean {
    val ours = ComponentName(context, InstaFocusService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
}

fun accessibilitySettingsIntent(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun appInfoIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
