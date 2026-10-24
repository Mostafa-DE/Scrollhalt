package app.scrollhalt.protect

/**
 * A timed pause of Reels blocking, as stored. In the boot it was started in, elapsed time
 * decides (immune to wall clock changes). After a reboot elapsed time restarts, so the wall
 * clock decides, and the remaining time is capped at [durationMillis] in case the clock moved;
 * the first time a new boot sees the pause it is [rebased] onto that boot's elapsed time.
 */
data class PauseState(
    val endWallMillis: Long,
    val endElapsedMillis: Long,
    val bootCount: Int,
    val durationMillis: Long,
    /** When the pause was started: its identity, kept by a rebase. */
    val startedWallMillis: Long = endWallMillis - durationMillis,
) {
    fun remaining(now: Moment): Long {
        val left = if (isOnBootOf(now)) endElapsedMillis - now.elapsedMillis else endWallMillis - now.wallMillis
        return left.coerceIn(0, durationMillis)
    }

    fun isActive(now: Moment): Boolean = remaining(now) > 0

    /** Elapsed time never goes back within a boot, so an earlier reading than the start means a reboot. */
    fun isOnBootOf(now: Moment): Boolean =
        now.bootCount == bootCount && now.elapsedMillis >= endElapsedMillis - durationMillis

    /**
     * After a reboot: what remains, fixed once on this boot's clocks, so it counts down by
     * elapsed time. Applying the cap on every reading instead would hold the pause at its full
     * length until the old wall-clock end when the clock went back.
     */
    fun rebased(now: Moment): PauseState {
        if (isOnBootOf(now)) return this
        val left = remaining(now)
        return copy(endWallMillis = now.wallMillis + left, endElapsedMillis = now.elapsedMillis + left, bootCount = now.bootCount)
    }

    companion object {
        /** The choices in the pause sheet. */
        val DURATION_MINUTES: List<Int> = listOf(1, 5, 10, 15)

        fun start(now: Moment, durationMillis: Long): PauseState = PauseState(
            endWallMillis = now.wallMillis + durationMillis,
            endElapsedMillis = now.elapsedMillis + durationMillis,
            bootCount = now.bootCount,
            durationMillis = durationMillis,
            startedWallMillis = now.wallMillis,
        )
    }
}
