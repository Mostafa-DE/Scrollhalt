package app.scrollhalt.protect

/** The last [WINDOW_MS] of a pause, counted down in whole seconds (10, 9, ... 1). */
object PauseCountdown {
    const val WINDOW_MS: Long = 10_000

    /** Seconds to show with [remainingMillis] left, rounded up; null outside the window. */
    fun seconds(remainingMillis: Long): Int? =
        if (remainingMillis in 1..WINDOW_MS) ((remainingMillis + 999) / 1_000).toInt() else null

    /** Delay until the shown second changes, or until the window starts. */
    fun nextTickMillis(remainingMillis: Long): Long {
        val shown = seconds(remainingMillis) ?: return (remainingMillis - WINDOW_MS).coerceAtLeast(1)
        return (remainingMillis - (shown - 1) * 1_000L).coerceAtLeast(1)
    }
}
