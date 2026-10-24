package app.scrollhalt.detect

import app.scrollhalt.detect.PolicyInput.LeftApp
import app.scrollhalt.detect.PolicyInput.PagerScrolled
import app.scrollhalt.detect.PolicyInput.ProtectionPaused
import app.scrollhalt.detect.PolicyInput.ProtectionResumed
import app.scrollhalt.detect.PolicyInput.ScreenSeen
import app.scrollhalt.detect.PolicyInput.ViewClicked

/** Where a reel in front was opened from: the last non-reel screen before it. */
enum class ReelOrigin {
    /** Shared in a DM (modal activity or sender markers), or opened from a DM screen. */
    DM,

    /** The Explore grid, or an unknown screen inside the Search tab. Closed at once. */
    EXPLORE,

    /** A profile page, including one reached through search. */
    PROFILE,

    /** The feed or another screen outside the Search tab. */
    OTHER,
}

/**
 * Immutable state machine that turns what the service observes into a [Decision].
 *
 * - Reels tab: go Home (the service also covers the tab button, so this is the backup).
 * - Reels opened from the Explore grid: close at once. Reels from a DM, the feed or a
 *   profile: the first one plays, the first swipe to another reel closes it.
 * - It never acts on DM or other screens.
 *
 * Every attempt is blocked, however often the user retries. An action that did not work
 * (the first observation after the cooldown still shows the same blocked screen) escalates
 * to a stronger one, ending at the launcher. Only if even that fails repeatedly does it
 * pause, because then detection itself is probably broken.
 *
 * When protection resumes (a pause ends) while a reel is on screen, that reel finishes:
 * it plays as an allowed reel whatever its origin, and the first swipe closes it (Back for a
 * single reel, the Home tab on the Reels tab feed).
 */
data class ReelPolicy(
    val session: ViewerSession? = null,
    /** Last pager scroll, kept so a scroll just before the viewer is classified still counts. */
    val lastScroll: PagerScrolled? = null,
    /** Selected tab from the last scan with the tab bar visible; viewers hide the tab bar. */
    val lastSelectedTab: String? = null,
    /** Last confidently classified non-reel screen: where the next reel comes from. */
    val lastSurface: Screen? = null,
    /** The last action whose outcome is not known yet. */
    val pending: PendingAction? = null,
    val lastActionAt: Long? = null,
    /** Times [Decision.GLOBAL_HOME] did not leave Instagram, within [BROKEN_WINDOW_MS]. */
    val globalHomeFailures: List<Long> = emptyList(),
    val pausedUntil: Long? = null,
    /** The attempt that led to the detection pause: the same blocked screen afterwards continues it. */
    val stuckAttempt: PendingAction? = null,
    /** The reel viewer of the last confident scan (UNKNOWN frames keep it), for a resumed session. */
    val viewerOnScreen: ViewerOnScreen? = null,
) {
    /** A Reels tab or single reel screen and the time it came on screen. */
    data class ViewerOnScreen(val classification: Classification, val since: Long)

    /**
     * A reel the user opened. Leaving it sets [swipedAway], and from then on the viewer is
     * closed until it is gone. Signals: a scroll of [pagerKey] reports an index other than
     * [startIndex], or the on-screen page [pageKey] is replaced by another page after a scroll.
     * With [startConfirmed] (the index came from Instagram positioning the pager while opening)
     * only the index counts, since Instagram also replaces the page while laying out the viewer.
     * A [resumed] session was on screen when protection resumed and plays whatever its origin;
     * with [reelsTab] it is the Reels tab feed, closed with the Home tab instead of Back.
     */
    data class ViewerSession(
        val startedAt: Long,
        val origin: ReelOrigin,
        val pagerKey: String? = null,
        val startIndex: Int? = null,
        val pageKey: String? = null,
        val swipedAway: Boolean = false,
        val startConfirmed: Boolean = false,
        val resumed: Boolean = false,
        val reelsTab: Boolean = false,
    )

    /**
     * An action taken against [target]. [base] is the first rung of its escalation ladder,
     * [last] the action actually taken, [streak] how many earlier actions were ineffective,
     * [category] what the attempt was (null for one the statistics do not count), [launched]
     * whether the attempt already went to the launcher.
     */
    data class PendingAction(
        val target: Screen,
        val base: Decision,
        val last: Decision,
        val streak: Int,
        val actedAt: Long,
        val category: BlockCategory? = null,
        val launched: Boolean = false,
    )

    fun reduce(input: PolicyInput, settings: PolicySettings): PolicyResult = when (input) {
        is ScreenSeen -> onScreen(input, settings)
        is ViewClicked -> onClick(input, settings)
        is PagerScrolled -> onPagerScroll(input, settings)
        is LeftApp -> PolicyResult(
            copy(session = null, pending = null, stuckAttempt = null, viewerOnScreen = null),
            Decision.NONE,
            "left Instagram",
            events = (pending ?: stuckAttempt).confirmation(),
        )
        is ProtectionPaused -> PolicyResult(protectionChanged(), Decision.NONE, "protection paused")
        is ProtectionResumed -> {
            val resumed = viewerOnScreen?.let { resumedSession(input.atMillis, it) }
            val reason = if (resumed == null) "protection resumed" else "protection resumed, current reel finishes"
            PolicyResult(protectionChanged().copy(session = resumed), Decision.NONE, reason)
        }
    }

    /**
     * Forgets the reel session and every escalation: an action from before the switch must
     * neither count as ineffective afterwards nor send anyone to the launcher.
     */
    private fun protectionChanged(): ReelPolicy =
        copy(session = null, pending = null, stuckAttempt = null, globalHomeFailures = emptyList(), pausedUntil = null)

    /**
     * The reel on screen when protection resumes keeps playing. Resuming is not a new opening:
     * the session starts from the pager index already seen for this viewer (no positioning
     * window), so the next swipe, however fast, closes it.
     */
    private fun resumedSession(at: Long, viewer: ViewerOnScreen): ViewerSession {
        val scroll = lastScroll?.takeIf { it.atMillis >= viewer.since - OPENING_SCROLL_MS }
        val classification = viewer.classification
        return ViewerSession(
            startedAt = at,
            origin = origin(classification),
            pagerKey = scroll?.pagerKey,
            startIndex = scroll?.fromIndex,
            pageKey = classification.reelPage,
            startConfirmed = scroll != null,
            resumed = true,
            reelsTab = classification.screen == Screen.REELS_TAB,
        )
    }

    private fun onClick(input: ViewClicked, settings: PolicySettings): PolicyResult = when {
        !InstagramRules.matches(input.viewId, InstagramRules.REELS_TAB_ID) -> idle(input.atMillis, "click ignored")
        !settings.blockingEnabled -> PolicyResult(this, Decision.NONE, "Reels tab clicked, blocking disabled")
        else -> {
            // A tap on the Reels tab after our last action is a new attempt by the user, not
            // proof that the action failed: start the ladder again instead of escalating.
            val fresh = pending?.takeIf { it.target == Screen.REELS_TAB && input.atMillis > it.actedAt } != null
            copy(session = null, pending = if (fresh) null else pending, stuckAttempt = null)
                .block(input.atMillis, Screen.REELS_TAB, Decision.GO_HOME, "Reels tab clicked", BlockCategory.REELS_TAB)
        }
    }

    private fun onScreen(input: ScreenSeen, settings: PolicySettings): PolicyResult {
        val classification = input.classification
        val tabSeen = if (classification.tabBarVisible && classification.selectedTab != null) {
            copy(lastSelectedTab = classification.selectedTab)
        } else {
            this
        }
        val seen = when (classification.screen) {
            Screen.UNKNOWN -> tabSeen
            Screen.SINGLE_REEL, Screen.REELS_TAB -> {
                val since = tabSeen.viewerOnScreen?.takeIf { it.classification.screen == classification.screen }?.since
                tabSeen.copy(viewerOnScreen = ViewerOnScreen(classification, since ?: input.atMillis))
            }
            Screen.DM, Screen.EXPLORE_GRID, Screen.PROFILE, Screen.OTHER -> tabSeen.copy(viewerOnScreen = null)
        }
        return when (classification.screen) {
            Screen.UNKNOWN -> seen.idle(input.atMillis, "not confident: reel pager without reel controls")
            Screen.DM, Screen.EXPLORE_GRID, Screen.PROFILE, Screen.OTHER -> PolicyResult(
                seen.copy(session = null, pending = null, stuckAttempt = null, lastSurface = classification.screen),
                Decision.NONE,
                "on ${classification.screen}",
                events = (pending ?: stuckAttempt).confirmation(),
            )
            Screen.REELS_TAB -> {
                val resumed = seen.session?.takeIf { it.reelsTab }
                when {
                    !settings.blockingEnabled ->
                        PolicyResult(seen.copy(session = null, pending = null, stuckAttempt = null), Decision.NONE, "Reels tab, blocking disabled")
                    // Also enforces a swipe whose Home tap was held back by the cooldown.
                    resumed?.swipedAway == true ->
                        seen.block(input.atMillis, Screen.REELS_TAB, Decision.GO_HOME, "swiped on the Reels tab", BlockCategory.NEXT_REEL)
                    resumed != null -> PolicyResult(seen, Decision.NONE, "Reels tab reel finishes after the pause")
                    else -> seen.copy(session = null)
                        .block(input.atMillis, Screen.REELS_TAB, Decision.GO_HOME, "Reels tab selected", BlockCategory.REELS_TAB)
                }
            }
            Screen.SINGLE_REEL -> seen.onReel(input.atMillis, classification, settings)
        }
    }

    private fun onReel(at: Long, classification: Classification, settings: PolicySettings): PolicyResult {
        if (!settings.blockingEnabled) return PolicyResult(copy(pending = null, stuckAttempt = null), Decision.NONE, "blocking disabled")
        // An action against the Reels tab that led here worked: the Reels tab is gone.
        val left = (pending ?: stuckAttempt)?.takeIf { it.target != Screen.SINGLE_REEL }
        val base = if (left == null) this else copy(pending = null, stuckAttempt = null)
        return base.onReelInFront(at, classification, settings).withEvents(left.confirmation())
    }

    private fun onReelInFront(at: Long, classification: Classification, settings: PolicySettings): PolicyResult {
        val origin = origin(classification)
        val current = session?.takeIf { !it.reelsTab }

        return when {
            // A reel already allowed stays allowed even if the remembered origin changes.
            origin == ReelOrigin.EXPLORE && current == null ->
                block(at, Screen.SINGLE_REEL, Decision.BACK, "reel opened from Explore", BlockCategory.EXPLORE_REEL)
            origin == ReelOrigin.DM && !settings.allowSingleReel && current?.resumed != true ->
                block(at, Screen.SINGLE_REEL, Decision.BACK, "reel shared in DM, single reels off")
            current == null ->
                PolicyResult(copy(session = newSession(at, origin, classification)), Decision.NONE, "first reel allowed (from $origin)")
            // Also enforces a swipe whose BACK was held back by the cooldown.
            current.swipedAway -> block(at, Screen.SINGLE_REEL, Decision.BACK, "swiped to another reel", BlockCategory.NEXT_REEL)
            current.pageKey == null && classification.reelPage != null ->
                PolicyResult(copy(session = current.copy(pageKey = classification.reelPage)), Decision.NONE, "reel page recorded")
            classification.reelPage != null && classification.reelPage != current.pageKey -> when {
                // A scroll to another index would already have closed the viewer, so every scroll
                // since the confirmed start reported the opened reel: Instagram swapped the page.
                current.startConfirmed ->
                    PolicyResult(copy(session = current.copy(pageKey = classification.reelPage)), Decision.NONE, "page replaced on the opened reel")
                lastScroll?.let { it.atMillis > current.startedAt } == true ->
                    copy(session = current.copy(swipedAway = true))
                        .block(at, Screen.SINGLE_REEL, Decision.BACK, "another reel page on screen after a scroll", BlockCategory.NEXT_REEL)
                // A swipe always scrolls the pager; a page swapped without one is Instagram's own.
                else -> PolicyResult(copy(session = current.copy(pageKey = classification.reelPage)), Decision.NONE, "page replaced without a scroll")
            }
            else -> PolicyResult(this, Decision.NONE, "first reel playing (from ${current.origin})")
        }
    }

    /**
     * Instagram positions the pager while opening, so a scroll just before this first scan of
     * the viewer (or shortly after it, see onPagerScroll) confirms the start index.
     */
    private fun newSession(at: Long, origin: ReelOrigin, classification: Classification): ViewerSession {
        val recent = lastScroll?.takeIf { at - it.atMillis <= OPENING_SCROLL_MS }
        return ViewerSession(at, origin, recent?.pagerKey, recent?.fromIndex, classification.reelPage, startConfirmed = recent != null)
    }

    private fun onPagerScroll(input: PagerScrolled, settings: PolicySettings): PolicyResult {
        val index = input.fromIndex
        if (index < 0) return idle(input.atMillis, "pager scroll without index")

        val tracked = copy(lastScroll = input)
        val current = session
            ?: return PolicyResult(tracked, Decision.NONE, "pager scroll outside a reel viewer")
        if (!settings.blockingEnabled) return PolicyResult(tracked, Decision.NONE, "blocking disabled")

        val start = current.startIndex
        // A resumed session was already on screen: nothing is being positioned.
        val positioning = !current.resumed && input.atMillis - current.startedAt <= LATE_POSITIONING_MS
        // The Reels tab feed is left with the Home tab; Back from a tab root would leave Instagram.
        val (target, action) = if (current.reelsTab) Screen.REELS_TAB to Decision.GO_HOME else Screen.SINGLE_REEL to Decision.BACK
        val adopt = { why: String ->
            val session = current.copy(pagerKey = input.pagerKey, startIndex = index, startConfirmed = positioning)
            PolicyResult(tracked.copy(session = session), Decision.NONE, "start index $index ($why)")
        }
        val swiped = { why: String ->
            tracked.copy(session = current.copy(swipedAway = true)).block(input.atMillis, target, action, why, BlockCategory.NEXT_REEL)
        }
        return when {
            current.swipedAway -> tracked.block(input.atMillis, target, action, "still swiping", BlockCategory.NEXT_REEL)
            start == null && positioning -> adopt("opening")
            start == null && current.pageKey != null ->
                // The page identity decides on the next scan; the index only becomes the baseline.
                adopt("page identity decides")
            start == null -> swiped("first scroll after the reel opened, index $index")
            input.pagerKey != current.pagerKey -> adopt("different pager")
            index == start -> PolicyResult(tracked, Decision.NONE, "on the opened reel")
            else -> swiped("swiped from reel $start to $index")
        }
    }

    private fun origin(classification: Classification): ReelOrigin = when {
        classification.sharedInDm -> ReelOrigin.DM
        lastSurface == Screen.EXPLORE_GRID -> ReelOrigin.EXPLORE
        lastSurface == Screen.PROFILE -> ReelOrigin.PROFILE
        lastSurface == Screen.DM -> ReelOrigin.DM
        // Origin unknown (or a non-grid screen) inside the Search tab: treat as Explore.
        (classification.selectedTab ?: lastSelectedTab) == InstagramRules.SEARCH_TAB_ID -> ReelOrigin.EXPLORE
        else -> ReelOrigin.OTHER
    }

    /**
     * Acts against [target], escalating when the previous action against it was ineffective.
     * Called whenever the blocked screen is observed; observations inside the cooldown are
     * held back and retried at its end, so neither a slow UI nor a fast re-tap slips through.
     */
    private fun block(at: Long, target: Screen, startWith: Decision, why: String, category: BlockCategory? = null): PolicyResult {
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
            val pausedPolicy = copy(pending = null, stuckAttempt = previous, globalHomeFailures = emptyList(), pausedUntil = at + PAUSE_MS)
            val reason = "detection may be broken: Home did not leave Instagram $MAX_GLOBAL_HOME_FAILURES times in 10 s, pausing 10 s"
            return PolicyResult(pausedPolicy, Decision.NONE, reason, retryAtMillis = at + PAUSE_MS)
        }

        val streak = if (previous == null) 0 else previous.streak + 1
        val ladder = ladder(startWith)
        val decision = ladder[minOf(streak, ladder.lastIndex)]
        // After the detection pause the ladder starts over, but it is still the same attempt.
        val continued = stuckAttempt?.takeIf { previous == null && it.target == target && it.base == startWith }
        val earlier = previous ?: continued
        val attempt = if (earlier == null) category else earlier.category
        val launchedBefore = earlier?.launched == true
        val acted = copy(
            pending = PendingAction(target, startWith, decision, streak, at, attempt, launchedBefore || decision == Decision.GLOBAL_HOME),
            stuckAttempt = null,
            lastActionAt = at,
            globalHomeFailures = failures,
            pausedUntil = null,
        )
        val reason = if (previous == null) why else "$why; ${previous.last} ineffective (streak $streak)"
        val events = listOfNotNull(
            attempt?.takeIf { earlier == null }?.let(PolicyEvent::Attempt),
            PolicyEvent.SentToLauncher.takeIf { decision == Decision.GLOBAL_HOME && !launchedBefore },
        )
        return PolicyResult(acted, decision, "$reason: $decision", retryAtMillis = at + COOLDOWN_MS, events = events)
    }

    /** The blocked screen of this pending action was left: the action worked. */
    private fun PendingAction?.confirmation(): List<PolicyEvent> =
        listOfNotNull(this?.category?.let(PolicyEvent::Confirmed))

    /** No state change; if an action still awaits judgement, make sure a scan happens after its cooldown. */
    private fun idle(at: Long, reason: String): PolicyResult {
        val retry = pending?.let { it.actedAt + COOLDOWN_MS }?.takeIf { it > at }
        return PolicyResult(this, Decision.NONE, reason, retryAtMillis = retry)
    }

    companion object {
        /** A scroll at most this long before the first viewer scan is Instagram positioning the pager. */
        const val OPENING_SCROLL_MS: Long = 1_200

        /** A scroll at most this long after the first viewer scan is still positioning: no swipe lands that fast. */
        const val LATE_POSITIONING_MS: Long = 300
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
