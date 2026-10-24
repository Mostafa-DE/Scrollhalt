package app.scrollhalt.stats

/**
 * When the service writes statistics without a forcing event (leaving Instagram, screen off,
 * a pause change and stopping always write). Changed counts are written at most every
 * [CHANGED_MS]; while Instagram is open with nothing else changed, the last-seen time at
 * most every [LAST_SEEN_MS]. Checked on events only: no timer.
 */
object WritePolicy {
    const val CHANGED_MS: Long = 30_000
    const val LAST_SEEN_MS: Long = 60_000

    fun due(lastWriteElapsed: Long?, nowElapsed: Long, changed: Boolean, inInstagram: Boolean): Boolean {
        val since = lastWriteElapsed?.let { nowElapsed - it } ?: Long.MAX_VALUE
        return (changed && since >= CHANGED_MS) || (inInstagram && since >= LAST_SEEN_MS)
    }
}
