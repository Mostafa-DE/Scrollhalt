package app.scrollhalt.stats

import app.scrollhalt.detect.InstagramRules

/** What is in front, for Instagram sessions. */
enum class Front {
    INSTAGRAM,

    /** A window over Instagram that does not leave it: its time counts as Instagram time. */
    OVER_INSTAGRAM,

    /** Another app or the launcher: the session stops. */
    ELSEWHERE,

    /** No active window known; changes nothing. */
    UNKNOWN,
}

object UsageRules {
    /**
     * Packages whose windows come over Instagram without leaving it: the notification shade,
     * Quick Settings and the volume panel (System UI), permission dialogs, keyboards, and
     * Samsung's Edge panel and screenshot toolbar. Any keyboard is also recognised by its
     * window type.
     */
    val overlayPackages: List<String> = listOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.app.smartcapture",
    )

    /** [packageName] of the active window; [inputMethod] when that window is a keyboard. */
    fun front(packageName: String?, inputMethod: Boolean): Front = when {
        packageName == null -> Front.UNKNOWN
        packageName == InstagramRules.PACKAGE -> Front.INSTAGRAM
        inputMethod || packageName in overlayPackages -> Front.OVER_INSTAGRAM
        else -> Front.ELSEWHERE
    }
}
