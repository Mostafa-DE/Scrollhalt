package app.instafocus.detect

import app.instafocus.detect.PolicyInput.PagerScrolled
import app.instafocus.detect.PolicyInput.ScreenSeen
import app.instafocus.detect.PolicyInput.ViewClicked

/**
 * Immutable state machine that turns what the service observes into a [Decision].
 * It prefers doing nothing over doing the wrong thing: it never acts on DM, Explore or
 * other screens, and never on a viewer the classifier is not sure about.
 */
data class ReelPolicy(
    /** Last confidently classified non-viewer surface. */
    val lastSurface: Screen? = null,
    val lastReelsTabClickAt: Long? = null,
    val lastPagerIndex: Int? = null,
    val lastPagerScrollAt: Long? = null,
    val session: ViewerSession? = null,
    /** Times of actions taken within the loop-guard window. */
    val recentActions: List<Long> = emptyList(),
    val pausedUntil: Long? = null,
) {
    /** A reel the user was allowed to open; swiping away from [startIndex] snaps back. */
    data class ViewerSession(val startedAt: Long, val origin: Screen, val startIndex: Int?)

    fun reduce(input: PolicyInput, settings: PolicySettings): PolicyResult = when (input) {
        is ScreenSeen -> onScreen(input, settings)
        is ViewClicked -> onClick(input)
        is PagerScrolled -> onPagerScroll(input, settings)
    }

    private fun onClick(input: ViewClicked): PolicyResult =
        if (InstagramRules.matches(input.viewId, InstagramRules.REELS_TAB_ID)) {
            PolicyResult(copy(lastReelsTabClickAt = input.atMillis), Decision.NONE, "Reels tab clicked")
        } else {
            PolicyResult(this, Decision.NONE, "click ignored")
        }

    private fun onScreen(input: ScreenSeen, settings: PolicySettings): PolicyResult {
        val classification = input.classification
        return when (classification.screen) {
            Screen.UNKNOWN -> PolicyResult(this, Decision.NONE, "not confident: reel pager without reel controls")
            Screen.REEL_VIEWER -> onViewer(input.atMillis, classification, settings)
            Screen.DM, Screen.EXPLORE, Screen.OTHER ->
                PolicyResult(copy(lastSurface = classification.screen, session = null), Decision.NONE, "on ${classification.screen}")
        }
    }

    private fun onViewer(at: Long, classification: Classification, settings: PolicySettings): PolicyResult {
        if (!settings.blockingEnabled) return PolicyResult(this, Decision.NONE, "blocking disabled")

        val tabClickedRecently = lastReelsTabClickAt?.let { at - it <= TAB_CLICK_WINDOW_MS } ?: false
        if (classification.reelsTabSelected || tabClickedRecently) {
            val why = if (classification.reelsTabSelected) "Reels tab selected" else "Reels tab just clicked"
            return act(at, Decision.GO_HOME, "$why: going Home")
        }

        val current = session
        if (current != null) {
            return PolicyResult(this, Decision.NONE, "single reel allowed (from ${current.origin})")
        }

        val origin = if (classification.dmOverlay) Screen.DM else lastSurface ?: Screen.OTHER
        if (!settings.allowSingleReel) {
            return act(at, Decision.BACK, "reel opened from $origin, single reels off: going back")
        }

        val recentIndex = lastPagerIndex?.takeIf { lastPagerScrollAt?.let { at - it <= SETTLE_MS } ?: false }
        val started = copy(session = ViewerSession(startedAt = at, origin = origin, startIndex = recentIndex))
        return PolicyResult(started, Decision.NONE, "allowed single reel from $origin")
    }

    private fun onPagerScroll(input: PagerScrolled, settings: PolicySettings): PolicyResult {
        val index = input.fromIndex
        if (index < 0) return PolicyResult(this, Decision.NONE, "pager scroll without index")

        val tracked = copy(lastPagerIndex = index, lastPagerScrollAt = input.atMillis)
        val current = session
            ?: return PolicyResult(tracked, Decision.NONE, "pager scroll outside a viewer session")
        if (!settings.blockingEnabled) return PolicyResult(tracked, Decision.NONE, "blocking disabled")

        val start = current.startIndex
        return when {
            input.atMillis - current.startedAt < SETTLE_MS ->
                PolicyResult(tracked.copy(session = current.copy(startIndex = index)), Decision.NONE, "start index $index")
            start == null ->
                PolicyResult(tracked.copy(session = current.copy(startIndex = index)), Decision.NONE, "start index $index adopted late")
            index == start -> PolicyResult(tracked, Decision.NONE, "on the opened reel")
            else -> tracked.act(
                at = input.atMillis,
                decision = Decision.SNAP_BACK,
                reason = "swiped from reel $start to $index: snapping back",
                direction = if (index > start) SnapDirection.BACKWARD else SnapDirection.FORWARD,
            )
        }
    }

    /** Applies the cooldown and the loop guard before letting an action through. */
    private fun act(at: Long, decision: Decision, reason: String, direction: SnapDirection? = null): PolicyResult {
        val paused = pausedUntil
        if (paused != null && at < paused) {
            return PolicyResult(this, Decision.NONE, "loop guard: paused for ${paused - at} ms ($reason)")
        }
        val recent = recentActions.filter { at - it < LOOP_WINDOW_MS }
        val last = recent.maxOrNull()
        if (last != null && at - last < COOLDOWN_MS) {
            return PolicyResult(copy(pausedUntil = null, recentActions = recent), Decision.NONE, "cooldown ($reason)")
        }
        if (recent.size >= MAX_ACTIONS_PER_WINDOW) {
            val pausedPolicy = copy(pausedUntil = at + LOOP_PAUSE_MS, recentActions = emptyList())
            return PolicyResult(pausedPolicy, Decision.NONE, "loop guard: $MAX_ACTIONS_PER_WINDOW actions in 5 s, pausing 10 s")
        }
        return PolicyResult(copy(pausedUntil = null, recentActions = recent + at), decision, reason, direction)
    }

    companion object {
        const val TAB_CLICK_WINDOW_MS: Long = 2_000
        const val SETTLE_MS: Long = 1_200
        const val COOLDOWN_MS: Long = 700
        const val LOOP_WINDOW_MS: Long = 5_000
        const val LOOP_PAUSE_MS: Long = 10_000
        const val MAX_ACTIONS_PER_WINDOW: Int = 3
    }
}
