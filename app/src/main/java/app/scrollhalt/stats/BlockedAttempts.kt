package app.scrollhalt.stats

import app.scrollhalt.detect.BlockCategory
import app.scrollhalt.detect.PolicyEvent
import app.scrollhalt.protect.Moment

/**
 * Blocked attempts per category, each counted as detected, then action taken (Back, Home or
 * the launcher was pressed and the press succeeded), then confirmed (the blocked screen was
 * left). Fed by [PolicyEvent]s, never by log text, and by taps on the Reels tab cover.
 */
object BlockedAttempts {
    /** Reels tab attempts this close together are one attempt (repeated taps). */
    const val MERGE_MS: Long = 1_000
}

fun StatsState.policyEvents(now: Moment, events: List<PolicyEvent>): StatsState =
    events.fold(this) { state, event -> state.policyEvent(now, event) }

/** The cover swallowed a tap on the Reels tab: acted on and confirmed by definition. */
fun StatsState.coverTapped(now: Moment): StatsState = attempt(now, BlockCategory.REELS_TAB, done = true)

/** An action against an attempt of [category] succeeded; counts once per attempt. */
fun StatsState.actionTaken(now: Moment, category: BlockCategory): StatsState {
    val marks = live.attempts
    if (category !in marks.awaitingAction) return this
    return outcome(now, category) { it.copy(actionTaken = it.actionTaken + 1) }
        .withMarks(marks.copy(awaitingAction = marks.awaitingAction - category))
}

private fun StatsState.policyEvent(now: Moment, event: PolicyEvent): StatsState = when (event) {
    is PolicyEvent.Attempt -> attempt(now, event.category, done = false)
    is PolicyEvent.Confirmed -> confirmed(now, event.category)
    PolicyEvent.SentToLauncher ->
        copy(days = days.update(Days.today(now)) { it.copy(blocked = it.blocked.copy(sentToLauncher = it.blocked.sentToLauncher + 1)) })
}

private fun StatsState.attempt(now: Moment, category: BlockCategory, done: Boolean): StatsState {
    val marks = live.attempts
    if (category != BlockCategory.REELS_TAB) return detected(now, category, done, marks)
    val last = marks.lastReelsTabAt
    val stamped = marks.copy(lastReelsTabAt = now.mark())
    val merged = last != null && millisBetween(last, now.mark()) <= BlockedAttempts.MERGE_MS
    return if (merged) withMarks(stamped) else detected(now, category, done, stamped)
}

private fun StatsState.detected(now: Moment, category: BlockCategory, done: Boolean, marks: AttemptMarks): StatsState =
    if (done) {
        outcome(now, category) { Outcome(it.detected + 1, it.actionTaken + 1, it.confirmed + 1) }.withMarks(marks)
    } else {
        outcome(now, category) { it.copy(detected = it.detected + 1) }.withMarks(
            marks.copy(awaitingAction = marks.awaitingAction + category, awaitingConfirmation = marks.awaitingConfirmation + category),
        )
    }

private fun StatsState.confirmed(now: Moment, category: BlockCategory): StatsState {
    val marks = live.attempts
    if (category !in marks.awaitingConfirmation) return this
    return outcome(now, category) { it.copy(confirmed = it.confirmed + 1) }
        .withMarks(marks.copy(awaitingConfirmation = marks.awaitingConfirmation - category))
}

private fun StatsState.outcome(now: Moment, category: BlockCategory, change: (Outcome) -> Outcome): StatsState =
    copy(days = days.update(Days.today(now)) { it.copy(blocked = it.blocked.with(category, change(it.blocked.of(category)))) })

private fun StatsState.withMarks(marks: AttemptMarks): StatsState = copy(live = live.copy(attempts = marks))
