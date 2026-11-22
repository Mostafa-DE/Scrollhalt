package app.scrollhalt.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.scrollhalt.protect.ProtectionMode
import app.scrollhalt.service.ScrollhaltService

enum class ProtectionStatus {
    /** Reels are blocked. */
    ACTIVE,

    /** A timed pause is running. */
    PAUSED,

    /** Block Reels is switched off. */
    DISABLED,

    /** The accessibility service is off or not connected: setup needed. */
    SERVICE_UNAVAILABLE,

    /** Scrollhalt switched itself off while an app on its list is used; it comes back by itself or asks to. */
    OFF_FOR_APP,
}

/** [offForApp]: a switch-off for an app is recorded; it ends when the service connects again. */
fun protectionStatus(enabled: Boolean, connected: Boolean, mode: ProtectionMode, offForApp: Boolean = false): ProtectionStatus = when {
    offForApp && !(enabled && connected) -> ProtectionStatus.OFF_FOR_APP
    !enabled || !connected -> ProtectionStatus.SERVICE_UNAVAILABLE
    mode == ProtectionMode.DISABLED -> ProtectionStatus.DISABLED
    mode == ProtectionMode.PAUSED -> ProtectionStatus.PAUSED
    else -> ProtectionStatus.ACTIVE
}

/** True when the user has switched Scrollhalt on in Accessibility settings. */
fun isServiceEnabled(context: Context): Boolean {
    val ours = ComponentName(context, ScrollhaltService::class.java)
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
