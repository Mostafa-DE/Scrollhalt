package app.scrollhalt.protect

/**
 * A timed pause of Reels blocking, as stored. In the boot it was started in, elapsed time
 * decides (immune to wall clock changes). After a reboot elapsed time restarts, so the wall
 * clock decides, and the remaining time is capped at [durationMillis] in case the clock moved.
 */
data class PauseState(
    val endWallMillis: Long,
    val endElapsedMillis: Long,
    val bootCount: Int,
    val durationMillis: Long,
) {
    fun remaining(now: Moment): Long {
        val left = if (sameBoot(now)) endElapsedMillis - now.elapsedMillis else endWallMillis - now.wallMillis
        return left.coerceIn(0, durationMillis)
    }

    fun isActive(now: Moment): Boolean = remaining(now) > 0

    /** Elapsed time never goes back within a boot, so an earlier reading than the start means a reboot. */
    private fun sameBoot(now: Moment): Boolean =
        now.bootCount == bootCount && now.elapsedMillis >= endElapsedMillis - durationMillis

    companion object {
        /** The choices in the pause sheet. */
        val DURATION_MINUTES: List<Int> = listOf(1, 5, 10, 15)

        fun start(now: Moment, durationMillis: Long): PauseState = PauseState(
            endWallMillis = now.wallMillis + durationMillis,
            endElapsedMillis = now.elapsedMillis + durationMillis,
            bootCount = now.bootCount,
            durationMillis = durationMillis,
        )
    }
}
