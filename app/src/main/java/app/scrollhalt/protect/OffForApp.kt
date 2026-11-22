package app.scrollhalt.protect

/**
 * Scrollhalt switched its accessibility service off because [packageName], an app that refuses
 * to run while an accessibility app is on (a banking app), came to the front. It comes back
 * [COMEBACK_MILLIS] later. The [comeback] timer counts like a pause: elapsed time on the boot it
 * started in, the wall clock after a reboot (capped at its duration, [PauseState.rebased] once).
 */
data class OffForApp(val packageName: String, val comeback: PauseState) {
    fun isDue(now: Moment): Boolean = !comeback.isActive(now)

    /** When to come back, on this boot's elapsed clock (the alarm); now if it is due. */
    fun backAtElapsed(now: Moment): Long = now.elapsedMillis + comeback.remaining(now)

    /** When to come back, by the wall clock ("Back on at HH:MM"); now if it is due. */
    fun backAtWall(now: Moment): Long = now.wallMillis + comeback.remaining(now)

    companion object {
        const val COMEBACK_MILLIS: Long = 5 * 60_000L

        fun start(packageName: String, now: Moment): OffForApp = OffForApp(packageName, PauseState.start(now, COMEBACK_MILLIS))
    }
}

/** The `ENABLED_ACCESSIBILITY_SERVICES` secure setting: flattened component names separated by ':'. */
object EnabledServices {
    /**
     * [value] with [component] (in `package/package.Class` form) at the end, unless it is there
     * already, also in the short `package/.Class` form. Other services keep their order.
     */
    fun withService(value: String?, component: String): String {
        val entries = value.orEmpty().split(':').filter { it.isNotEmpty() }
        val present = entries.any { full(it) == component }
        return (if (present) entries else entries + component).joinToString(":")
    }

    private fun full(entry: String): String {
        val packageName = entry.substringBefore('/')
        val className = entry.substringAfter('/')
        return if (className.startsWith('.')) "$packageName/$packageName$className" else entry
    }
}
