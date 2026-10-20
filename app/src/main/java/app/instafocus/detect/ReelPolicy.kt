package app.instafocus.detect

import app.instafocus.detect.PolicyInput.LeftApp
import app.instafocus.detect.PolicyInput.PagerScrolled
import app.instafocus.detect.PolicyInput.ScreenSeen
import app.instafocus.detect.PolicyInput.ViewClicked

/** Where a reel in front was opened from. */
enum class ReelOrigin {
    /** Shared in a DM (modal activity or sender markers). */
    DM,

    /** Opened while the Search tab is selected: the Explore grid, or a profile reached by search. */
    EXPLORE,

    /** Opened from the feed or a profile. */
    OTHER,
}

/**
 * Immutable state machine that turns what the service observes into a [Decision].
 *
 * - Reels tab: go Home (the service also covers the tab button, so this is the backup).
 * - Explore reels: close at once. DM, feed and profile reels: the first one plays, the
 *   first swipe to another reel closes it.
 * - It never acts on DM or other screens.
 *
 * Every attempt is blocked, however often the user retries. An action that did not work
 * (the first observation after the cooldown still shows the same blocked screen) escalates
 * to a stronger one, ending at the launcher. Only if even that fails repeatedly does it
 * pause, because then detection itself is probably broken.
 */
data class ReelPolicy(
    val session: ViewerSession? = null,
    /** Last pager scroll, kept so a scroll just before the viewer is classified still counts. */
    val lastScroll: PagerScrolled? = null,
    /** Selected tab from the last scan with the tab bar visible; viewers hide the tab bar. */
    val lastSelectedTab: String? = null,
    /** The last action whose outcome is not known yet. */
    val pending: PendingAction? = null,
    val lastActionAt: Long? = null,
    /** Times [Decision.GLOBAL_HOME] did not leave Instagram, within [BROKEN_WINDOW_MS]. */
    val globalHomeFailures: List<Long> = emptyList(),
    val pausedUntil: Long? = null,
) {
    /**
     * A reel the user opened. The first page change of [pagerKey] away from [startIndex]
     * sets [swipedAway], and from then on the viewer is closed until it is gone.
     */
    data class ViewerSession(
        val startedAt: Long,
        val origin: ReelOrigin,
        val pagerKey: String? = null,
        val startIndex: Int? = null,
        val swipedAway: Boolean = false,
    )

    /**
     * An action taken against [target]. [base] is the first rung of its escalation ladder,
     * [last] the action actually taken, [streak] how many earlier actions were ineffective.
     */
    data class PendingAction(
        val target: Screen,
        val base: Decision,
        val last: Decision,
        val streak: Int,
        val actedAt: Long,
    )

    fun reduce(input: PolicyInput, settings: PolicySettings): PolicyResult = when (input) {
        is ScreenSeen -> onScreen(input, settings)
        is ViewClicked -> onClick(input, settings)
        is PagerScrolled -> onPagerScroll(input, settings)
        is LeftApp -> PolicyResult(copy(session = null, pending = null), Decision.NONE, "left Instagram")
    }

    private fun onClick(input: ViewClicked, settings: PolicySettings): PolicyResult = when {
        !InstagramRules.matches(input.viewId, InstagramRules.REELS_TAB_ID) -> idle(input.atMillis, "click ignored")
        !settings.blockingEnabled -> PolicyResult(this, Decision.NONE, "Reels tab clicked, blocking disabled")
        else -> {
            // A tap on the Reels tab after our last action is a new attempt by the user, not
            // proof that the action failed: start the ladder again instead of escalating.
            val fresh = pending?.takeIf { it.target == Screen.REELS_TAB && input.atMillis > it.actedAt } != null
            copy(session = null, pending = if (fresh) null else pending)
                .block(input.atMillis, Screen.REELS_TAB, Decision.GO_HOME, "Reels tab clicked")
        }
    }

    private fun onScreen(input: ScreenSeen, settings: PolicySettings): PolicyResult {
        val classification = input.classification
        val seen = if (classification.tabBarVisible && classification.selectedTab != null) {
            copy(lastSelectedTab = classification.selectedTab)
        } else {
            this
        }
        return when (classification.screen) {
            Screen.UNKNOWN -> seen.idle(input.atMillis, "not confident: reel pager without reel controls")
            Screen.DM, Screen.OTHER ->
                PolicyResult(seen.copy(session = null, pending = null), Decision.NONE, "on ${classification.screen}")
            Screen.REELS_TAB ->
                if (settings.blockingEnabled) {
                    seen.copy(session = null).block(input.atMillis, Screen.REELS_TAB, Decision.GO_HOME, "Reels tab selected")
                } else {
                    PolicyResult(seen.copy(session = null, pending = null), Decision.NONE, "Reels tab, blocking disabled")
                }
            Screen.SINGLE_REEL -> seen.onReel(input.atMillis, classification, settings)
        }
    }

    private fun onReel(at: Long, classification: Classification, settings: PolicySettings): PolicyResult {
        if (!settings.blockingEnabled) return PolicyResult(copy(pending = null), Decision.NONE, "blocking disabled")
        // An action against the Reels tab that led here worked: the Reels tab is gone.
        val base = if (pending?.target == Screen.SINGLE_REEL) this else copy(pending = null)
        val origin = origin(classification)
        val current = base.session

        return when {
            origin == ReelOrigin.EXPLORE -> base.block(at, Screen.SINGLE_REEL, Decision.BACK, "reel opened from Explore")
            origin == ReelOrigin.DM && !settings.allowSingleReel ->
                base.block(at, Screen.SINGLE_REEL, Decision.BACK, "reel shared in DM, single reels off")
            current == null -> {
                val recent = base.lastScroll?.takeIf { at - it.atMillis <= SETTLE_MS }
                val session = ViewerSession(at, origin, recent?.pagerKey, recent?.fromIndex)
                PolicyResult(base.copy(session = session), Decision.NONE, "first reel allowed (from $origin)")
            }
            // Also enforces a swipe whose BACK was held back by the cooldown.
            current.swipedAway -> base.block(at, Screen.SINGLE_REEL, Decision.BACK, "swiped to another reel")
            else -> PolicyResult(base, Decision.NONE, "first reel playing (from ${current.origin})")
        }
    }

    private fun onPagerScroll(input: PagerScrolled, settings: PolicySettings): PolicyResult {
        val index = input.fromIndex
        if (index < 0) return idle(input.atMillis, "pager scroll without index")

        val tracked = copy(lastScroll = input)
        val current = session
            ?: return PolicyResult(tracked, Decision.NONE, "pager scroll outside a reel viewer")
        if (!settings.blockingEnabled) return PolicyResult(tracked, Decision.NONE, "blocking disabled")

        val start = current.startIndex
        val restart = { why: String ->
            val session = current.copy(pagerKey = input.pagerKey, startIndex = index)
            PolicyResult(tracked.copy(session = session), Decision.NONE, "start index $index ($why)")
        }
        return when {
            current.swipedAway -> tracked.block(input.atMillis, Screen.SINGLE_REEL, Decision.BACK, "still swiping")
            input.atMillis - current.startedAt < SETTLE_MS -> restart("settling")
            start == null -> restart("adopted late")
            input.pagerKey != current.pagerKey -> restart("different pager")
            index == start -> PolicyResult(tracked, Decision.NONE, "on the opened reel")
            else -> tracked.copy(session = current.copy(swipedAway = true))
                .block(input.atMillis, Screen.SINGLE_REEL, Decision.BACK, "swiped from reel $start to $index")
        }
    }

    private fun origin(classification: Classification): ReelOrigin = when {
        classification.sharedInDm -> ReelOrigin.DM
        (classification.selectedTab ?: lastSelectedTab) == InstagramRules.SEARCH_TAB_ID -> ReelOrigin.EXPLORE
        else -> ReelOrigin.OTHER
    }

    /**
     * Acts against [target], escalating when the previous action against it was ineffective.
     * Called whenever the blocked screen is observed; observations inside the cooldown are
     * held back and retried at its end, so neither a slow UI nor a fast re-tap slips through.
     */
    private fun block(at: Long, target: Screen, startWith: Decision, why: String): PolicyResult {
        val paused = pausedUntil
        if (paused != null && at < paused) {
            return PolicyResult(this, Decision.NONE, "paused for ${paused - at} ms: detection may be broken ($why)", retryAtMillis = paused)
        }
        val last = lastActionAt
        if (last != null && at - last < COOLDOWN_MS) {
            return PolicyResult(this, Decision.NONE, "cooldown, retrying ($why)", retryAtMillis = last + COOLDOWN_MS)
        }

        val previous = pending?.takeIf { it.target == target && it.base == startWith }
        val homeFailed = previous?.last == Decision.GLOBAL_HOME
        val failures = globalHomeFailures.filter { at - it < BROKEN_WINDOW_MS } + listOfNotNull(at.takeIf { homeFailed })
        if (homeFailed && failures.size >= MAX_GLOBAL_HOME_FAILURES) {
            val pausedPolicy = copy(pending = null, globalHomeFailures = emptyList(), pausedUntil = at + PAUSE_MS)
            val reason = "detection may be broken: Home did not leave Instagram $MAX_GLOBAL_HOME_FAILURES times in 10 s, pausing 10 s"
            return PolicyResult(pausedPolicy, Decision.NONE, reason, retryAtMillis = at + PAUSE_MS)
        }

        val streak = if (previous == null) 0 else previous.streak + 1
        val ladder = ladder(startWith)
        val decision = ladder[minOf(streak, ladder.lastIndex)]
        val acted = copy(
            pending = PendingAction(target, startWith, decision, streak, at),
            lastActionAt = at,
            globalHomeFailures = failures,
            pausedUntil = null,
        )
        val reason = if (previous == null) why else "$why; ${previous.last} ineffective (streak $streak)"
        return PolicyResult(acted, decision, "$reason: $decision", retryAtMillis = at + COOLDOWN_MS)
    }

    /** No state change; if an action still awaits judgement, make sure a scan happens after its cooldown. */
    private fun idle(at: Long, reason: String): PolicyResult {
        val retry = pending?.let { it.actedAt + COOLDOWN_MS }?.takeIf { it > at }
        return PolicyResult(this, Decision.NONE, reason, retryAtMillis = retry)
    }

    companion object {
        const val SETTLE_MS: Long = 1_200
        const val COOLDOWN_MS: Long = 700
        const val BROKEN_WINDOW_MS: Long = 10_000
        const val PAUSE_MS: Long = 10_000
        const val MAX_GLOBAL_HOME_FAILURES: Int = 3

        /** Each rung is tried twice before moving on; the launcher is the last resort. */
        fun ladder(startWith: Decision): List<Decision> = when (startWith) {
            Decision.GO_HOME -> listOf(Decision.GO_HOME, Decision.GO_HOME, Decision.GLOBAL_HOME)
            Decision.BACK -> listOf(Decision.BACK, Decision.BACK, Decision.GLOBAL_HOME)
            Decision.GLOBAL_HOME, Decision.NONE -> listOf(Decision.GLOBAL_HOME)
        }
    }
}
