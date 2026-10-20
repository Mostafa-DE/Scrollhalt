package app.instafocus.detect

import app.instafocus.detect.PolicyInput.PagerScrolled
import app.instafocus.detect.PolicyInput.ScreenSeen
import app.instafocus.detect.PolicyInput.ViewClicked

/**
 * Immutable state machine that turns what the service observes into a [Decision].
 * It prefers doing nothing over doing the wrong thing: it never acts on DM or other
 * screens, and presses Back on opening a reel only when it is sure the reel is in front.
 */
data class ReelPolicy(
    val session: ViewerSession? = null,
    /** Last pager scroll, kept so a scroll just before the viewer is classified still counts. */
    val lastScroll: PagerScrolled? = null,
    /** Times of actions taken within the loop-guard window. */
    val recentActions: List<Long> = emptyList(),
    val pausedUntil: Long? = null,
) {
    /**
     * A single reel the user opened. Swiping [pagerKey] away from [startIndex] snaps back.
     * [sharedInDm] is the stronger origin signal (modal activity or sender markers).
     */
    data class ViewerSession(
        val startedAt: Long,
        val sharedInDm: Boolean,
        val pagerKey: String? = null,
        val startIndex: Int? = null,
    )

    fun reduce(input: PolicyInput, settings: PolicySettings): PolicyResult = when (input) {
        is ScreenSeen -> onScreen(input, settings)
        is ViewClicked -> onClick(input, settings)
        is PagerScrolled -> onPagerScroll(input, settings)
    }

    private fun onClick(input: ViewClicked, settings: PolicySettings): PolicyResult = when {
        !InstagramRules.matches(input.viewId, InstagramRules.REELS_TAB_ID) -> PolicyResult(this, Decision.NONE, "click ignored")
        !settings.blockingEnabled -> PolicyResult(this, Decision.NONE, "Reels tab clicked, blocking disabled")
        else -> copy(session = null).act(input.atMillis, Decision.GO_HOME, "Reels tab clicked: going Home")
    }

    private fun onScreen(input: ScreenSeen, settings: PolicySettings): PolicyResult {
        val classification = input.classification
        return when (classification.screen) {
            Screen.UNKNOWN -> PolicyResult(this, Decision.NONE, "not confident: reel pager without reel controls")
            Screen.REELS_TAB ->
                if (settings.blockingEnabled) {
                    copy(session = null).act(input.atMillis, Decision.GO_HOME, "Reels tab selected: going Home")
                } else {
                    PolicyResult(copy(session = null), Decision.NONE, "Reels tab, blocking disabled")
                }
            Screen.SINGLE_REEL -> onSingleReel(input.atMillis, classification, settings)
            Screen.DM, Screen.OTHER -> PolicyResult(copy(session = null), Decision.NONE, "on ${classification.screen}")
        }
    }

    private fun onSingleReel(at: Long, classification: Classification, settings: PolicySettings): PolicyResult {
        if (!settings.blockingEnabled) return PolicyResult(this, Decision.NONE, "blocking disabled")
        val origin = origin(classification.sharedInDm)
        session?.let { return PolicyResult(this, Decision.NONE, "single reel allowed ($origin)") }

        if (!settings.allowSingleReel && classification.sharedInDm) {
            return act(at, Decision.BACK, "reel opened ($origin), single reels off: going back")
        }
        val recent = lastScroll?.takeIf { at - it.atMillis <= SETTLE_MS }
        val started = copy(
            session = ViewerSession(at, classification.sharedInDm, recent?.pagerKey, recent?.fromIndex),
        )
        val reason = if (settings.allowSingleReel) {
            "allowed single reel ($origin)"
        } else {
            // Without a DM signal the pager in front could be the Reels tab kept alive behind
            // another screen, so Back now could break that screen. Close on the first swipe instead.
            "reel opened ($origin), single reels off: closing on first swipe"
        }
        return PolicyResult(started, Decision.NONE, reason)
    }

    private fun onPagerScroll(input: PagerScrolled, settings: PolicySettings): PolicyResult {
        val index = input.fromIndex
        if (index < 0) return PolicyResult(this, Decision.NONE, "pager scroll without index")

        val tracked = copy(lastScroll = input)
        val current = session
            ?: return PolicyResult(tracked, Decision.NONE, "pager scroll outside a single reel")
        if (!settings.blockingEnabled) return PolicyResult(tracked, Decision.NONE, "blocking disabled")

        val start = current.startIndex
        val restart = { why: String ->
            PolicyResult(
                tracked.copy(session = current.copy(pagerKey = input.pagerKey, startIndex = index)),
                Decision.NONE,
                "start index $index ($why)",
            )
        }
        return when {
            input.atMillis - current.startedAt < SETTLE_MS -> restart("settling")
            start == null -> restart("adopted late")
            input.pagerKey != current.pagerKey -> restart("different pager")
            index == start -> PolicyResult(tracked, Decision.NONE, "on the opened reel")
            !settings.allowSingleReel ->
                tracked.act(input.atMillis, Decision.BACK, "swiped from reel $start to $index, single reels off: going back")
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

    private fun origin(sharedInDm: Boolean): String = if (sharedInDm) "shared in DM" else "Explore/feed/profile"

    companion object {
        const val SETTLE_MS: Long = 1_200
        const val COOLDOWN_MS: Long = 700
        const val LOOP_WINDOW_MS: Long = 5_000
        const val LOOP_PAUSE_MS: Long = 10_000
        const val MAX_ACTIONS_PER_WINDOW: Int = 3
    }
}
