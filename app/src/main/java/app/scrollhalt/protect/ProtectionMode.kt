package app.scrollhalt.protect

/** What Reels blocking is doing right now. */
enum class ProtectionMode {
    /** Block Reels on, no pause: Reels are blocked. */
    PROTECTED,

    /** A timed pause is running: Reels open normally until it ends. */
    PAUSED,

    /** Block Reels switched off. */
    DISABLED,
}

fun protectionMode(blockingEnabled: Boolean, pause: PauseState?, now: Moment): ProtectionMode = when {
    !blockingEnabled -> ProtectionMode.DISABLED
    pause?.isActive(now) == true -> ProtectionMode.PAUSED
    else -> ProtectionMode.PROTECTED
}
