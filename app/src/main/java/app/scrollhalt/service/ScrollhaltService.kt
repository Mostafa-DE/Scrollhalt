package app.scrollhalt.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import app.scrollhalt.R
import app.scrollhalt.data.AppSettings
import app.scrollhalt.data.DeviceClock
import app.scrollhalt.data.SettingsRepository
import app.scrollhalt.data.StatsRepository
import app.scrollhalt.debug.DebugEntry
import app.scrollhalt.debug.DebugLog
import app.scrollhalt.detect.Classification
import app.scrollhalt.detect.Decision
import app.scrollhalt.detect.InstagramRules
import app.scrollhalt.detect.PolicyInput
import app.scrollhalt.detect.PolicyResult
import app.scrollhalt.detect.PolicySettings
import app.scrollhalt.detect.ReelPolicy
import app.scrollhalt.detect.ScreenClassifier
import app.scrollhalt.detect.UiNode
import app.scrollhalt.detect.onScreenIds
import app.scrollhalt.protect.Clock
import app.scrollhalt.protect.Moment
import app.scrollhalt.protect.PauseState
import app.scrollhalt.protect.ProtectionMode
import app.scrollhalt.protect.protectionMode
import app.scrollhalt.stats.Front
import app.scrollhalt.stats.Mark
import app.scrollhalt.stats.UsageRules
import app.scrollhalt.stats.actionTaken
import app.scrollhalt.stats.coverTapped
import app.scrollhalt.stats.instagramAway
import app.scrollhalt.stats.instagramInFront
import app.scrollhalt.stats.pauseEnded
import app.scrollhalt.stats.pauseStarted
import app.scrollhalt.stats.policyEvents
import app.scrollhalt.stats.viewerGone
import app.scrollhalt.stats.viewerMode
import app.scrollhalt.stats.viewerScrolled
import app.scrollhalt.stats.viewerSeen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Logcat tag for adb diagnosis (`adb logcat -s Scrollhalt`). Ids and indexes only, never text. */
internal const val LOG_TAG: String = "Scrollhalt"

/**
 * Watches Instagram's view tree, covers the Reels tab button, and leaves reels when they
 * appear. All work runs on the main thread in response to events: no polling, nothing runs
 * while idle (timers exist only while a retry or a pause is pending). Events from other apps
 * are used only to notice that Instagram is no longer in front (their package name; nothing
 * else is read). Usage statistics are fed from the same events (see [StatsKeeper]).
 */
class ScrollhaltService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = Runnable { safely("scan") { scan() } }
    private val retryRunnable = Runnable { safely("retry scan") { scan() } }
    private val pauseEndRunnable = Runnable {
        safely("pause end") {
            reconcileProtection()
            schedulePauseTimers()
        }
    }
    private val headsUpRunnable = Runnable { safely("pause heads-up") { pauseHeadsUp() } }
    private var scanPending = false
    /** Between onServiceConnected and the first shutdown(); shutdown runs once per connection. */
    private var running = false
    private var scope: CoroutineScope? = null
    private var cover: ReelsTabCover? = null
    private val clock: Clock by lazy { DeviceClock(this) }
    private val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    private val stats: StatsKeeper by lazy { StatsKeeper(clock, StatsRepository(this)) }
    private var screenOn = true
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            safely("screen ${intent.action}") {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                }
            }
        }
    }

    private var settings = AppSettings()
    private var settingsLoaded = false
    /** Whether Reels were blocked at the last check; null before the first one. */
    private var protecting: Boolean? = null
    private var clearRequested: PauseState? = null
    private var policy = ReelPolicy()
    private var activity: String? = null
    private var inInstagram = false
    private var lastLogged: Pair<String, String>? = null
    private var lastScreen: String? = null
    private var lastToastAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        cover = ReelsTabCover(this) {
            showToast(R.string.toast_tab_hidden)
            stats.update { coverTapped(it) }
        }
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = serviceScope
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        registerScreenReceiver()
        stats.start(serviceScope)
        serviceScope.launch {
            settingsRepository.settings.collect {
                settings = it
                settingsLoaded = true
                safely("settings") {
                    reconcileProtection()
                    schedulePauseTimers()
                    if (!blocking()) cover?.hide()
                }
            }
        }
        ServiceState.setConnected(true, this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() == InstagramRules.PACKAGE) {
            safely("event ${event.eventType}") { onInstagramEvent(event) }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            // Another window changed (launcher, other app, keyboard, shade): only check
            // whether Instagram is still in front, and refresh the cover.
            safely("window change") { onOtherWindowChanged() }
        }
    }

    override fun onInterrupt() {
        cover?.hide()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Called from both onUnbind and onDestroy; only the first call does anything. */
    private fun shutdown() {
        if (!running) return
        running = false
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(pauseEndRunnable)
        handler.removeCallbacks(headsUpRunnable)
        scanPending = false
        cover?.hide()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        stats.stop()
        scope?.cancel()
        scope = null
        ServiceState.setConnected(false, this)
    }

    /** Screen on/off broadcasts are protected: only the system can send them. */
    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    /**
     * The screen went off: the Instagram session and any Reels viewer stop counting. Writing
     * here (and when the screen comes on) also keeps the tracked time of days without any
     * Instagram use, so such a day is not shown as "Not tracked".
     */
    private fun onScreenOff() {
        screenOn = false
        stats.update { instagramAway(it).viewerGone(it) }
        stats.write()
    }

    private fun onScreenOn() {
        screenOn = true
        stats.write()
    }

    private fun onInstagramEvent(event: AccessibilityEvent) {
        reconcileProtection()
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClicked(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                trackActivity(event.className?.toString())
                scheduleScan()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleScan()
            else -> Unit
        }
        stats.maybeWrite()
    }

    private fun onOtherWindowChanged() {
        val counting = stats.counting
        if (!inInstagram && !counting) return
        val front = activePackage()
        if (counting) trackFront(front)
        if (!inInstagram) return
        if (front != InstagramRules.PACKAGE) leftInstagram() else scheduleScan()
    }

    /**
     * Ends the Instagram session when another app or the launcher is in front. The shade,
     * keyboards and dialogs over Instagram do not end it (see [UsageRules]).
     */
    private fun trackFront(packageName: String?) {
        if (UsageRules.front(packageName, inputMethod = false) != Front.ELSEWHERE || inputMethodActive()) return
        if (stats.update { instagramAway(it).viewerGone(it) }) stats.write()
    }

    private fun leftInstagram() {
        inInstagram = false
        cover?.hide()
        log("window", "left Instagram", apply(PolicyInput.LeftApp(now())), always = true)
    }

    /** Window-state events also fire for dialogs and popups; only real activities count. */
    private fun trackActivity(className: String?) {
        if (className == null) return
        val isActivity = try {
            packageManager.getActivityInfo(ComponentName(InstagramRules.PACKAGE, className), 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (isActivity && className != activity) {
            activity = className
            Log.d(LOG_TAG, "activity=$className")
            DebugLog.add(DebugEntry(System.currentTimeMillis(), "activity", className.substringAfterLast('.'), "-", "activity changed"))
        }
    }

    private fun onClicked(event: AccessibilityEvent) {
        val viewId = withSource(event) { it.viewIdResourceName }
        if (verbose()) Log.v(LOG_TAG, "click event view=${viewId?.substringAfter(":id/") ?: "-"}")
        val result = apply(PolicyInput.ViewClicked(now(), viewId))
        log("click", "view=${viewId?.substringAfter(":id/") ?: "-"}", result)
        perform(result)
    }

    private fun onScrolled(event: AccessibilityEvent) {
        val result = withSource(event) { source ->
            val viewId = source.viewIdResourceName
            if (InstagramRules.reelViewerIds.none { InstagramRules.matches(viewId, it) }) return@withSource null
            // AccessibilityNodeInfo equality is view identity (window + view id), so its hash
            // tells two pagers apart even though they share a resource id.
            val key = "${event.windowId}:${source.hashCode()}"
            val scrolled = apply(PolicyInput.PagerScrolled(now(), key, event.fromIndex))
            stats.update { viewerScrolled(it, key, event.fromIndex) }
            val details = "act=${activityName()} from=${event.fromIndex} to=${event.toIndex} " +
                "count=${event.itemCount} pager=$key"
            log("pager scroll", details, scrolled, always = true)
            scrolled
        }
        result?.let(::perform)
        scheduleScan()
    }

    /**
     * Coalesces bursts of content changes into one scan [SCAN_DELAY_MS] after the first.
     * A throttle, not a trailing debounce: a playing reel changes content non-stop and
     * would otherwise postpone the scan forever.
     */
    private fun scheduleScan() {
        if (scanPending) return
        scanPending = true
        handler.postDelayed(scanRunnable, SCAN_DELAY_MS)
    }

    private fun scan() {
        scanPending = false
        reconcileProtection()
        val root = activeRoot()
        if (root == null) {
            if (verbose()) Log.v(LOG_TAG, "scan: no active window root")
            return
        }
        try {
            val packageName = root.packageName?.toString()
            if (packageName != InstagramRules.PACKAGE) {
                trackFront(packageName)
                if (inInstagram) leftInstagram()
                return
            }
            inInstagram = true
            if (screenOn) stats.update { instagramInFront(it) }
            val snapshot = NodeSnapshot.of(root) ?: return
            if (verbose()) dumpTree(snapshot)
            if (settings.debugCapture) DebugLog.setSnapshot(snapshot.onScreenIds())
            val classification = ScreenClassifier.classify(snapshot, activity)
            if (screenOn) stats.update { viewerSeen(it, classification, mode()) }
            updateCover(classification)
            val result = apply(PolicyInput.ScreenSeen(now(), classification))
            log("scan", describe(classification), result)
            perform(result)
            stats.maybeWrite()
        } finally {
            root.release()
        }
    }

    /** Covers the Reels tab while the tab bar is on screen and no keyboard could be under the cover. */
    private fun updateCover(classification: Classification) {
        val bounds = classification.reelsTabBounds
        if (blocking() && bounds != null && !keyboardShown()) cover?.show(bounds) else cover?.hide()
    }

    private fun mode(): ProtectionMode = protectionMode(settings.blockingEnabled, settings.pause, clock.now())

    /**
     * Reels are blocked: Block Reels on and no pause running, as of the last
     * [reconcileProtection], so the policy never acts across a switch it was not told about.
     */
    private fun blocking(): Boolean = protecting ?: (mode() == ProtectionMode.PROTECTED)

    /**
     * Applies a change of [blocking] (a pause starting or ending, Block Reels switched) once,
     * wherever it is noticed first: the pause timer, a settings change or any Instagram event.
     * The timer alone is not enough, since Handler time stands still while the phone sleeps.
     */
    private fun reconcileProtection() {
        val now = clock.now()
        settings.pause?.takeIf { !it.isActive(now) && it != clearRequested }?.let { expired ->
            clearRequested = expired
            scope?.launch { settingsRepository.clearPause(expected = expired) }
        }
        val mode = protectionMode(settings.blockingEnabled, settings.pause, now)
        stats.update { viewerMode(it, mode) }
        if (settingsLoaded) trackPause(now, mode)
        val active = mode == ProtectionMode.PROTECTED
        val previous = protecting
        protecting = active
        if (previous == null || previous == active) return
        if (active) {
            log("protection", "-", apply(PolicyInput.ProtectionResumed(now())), always = true)
            if (inInstagram) scheduleScan()
        } else {
            cover?.hide()
            log("protection", "-", apply(PolicyInput.ProtectionPaused(now())), always = true)
        }
    }

    /** Counts a pause once when it starts and its time when it ends; both are written at once. */
    private fun trackPause(now: Moment, mode: ProtectionMode) {
        val pause = settings.pause?.takeIf { mode == ProtectionMode.PAUSED }
        val changed = stats.update {
            if (pause == null) {
                pauseEnded(now)
            } else {
                pauseStarted(now, pause.endWallMillis - pause.durationMillis, Mark(pause.endElapsedMillis, pause.endWallMillis, pause.bootCount))
            }
        }
        if (changed) stats.write()
    }

    /** Timers for the end of a pause and the heads-up before it; none without a pause. */
    private fun schedulePauseTimers() {
        handler.removeCallbacks(pauseEndRunnable)
        handler.removeCallbacks(headsUpRunnable)
        val pause = settings.pause?.takeIf { settings.blockingEnabled } ?: return
        val remaining = pause.remaining(clock.now())
        if (remaining <= 0) return
        handler.postDelayed(pauseEndRunnable, remaining)
        if (remaining > HEADS_UP_MS) handler.postDelayed(headsUpRunnable, remaining - HEADS_UP_MS)
    }

    private fun pauseHeadsUp() {
        if (mode() == ProtectionMode.PAUSED && instagramInFront()) {
            Toast.makeText(this, R.string.toast_resumes_soon, Toast.LENGTH_SHORT).show()
        }
    }

    private fun apply(input: PolicyInput): PolicyResult {
        val result = policy.reduce(input, PolicySettings(blocking(), settings.allowSingleReel))
        policy = result.policy
        result.retryAtMillis?.let(::scheduleRetry)
        if (result.events.isNotEmpty()) stats.update { policyEvents(it, result.events) }
        return result
    }

    /** One scan at [atMillis] (uptime) to judge or retry an action, even if Instagram sends no events. */
    private fun scheduleRetry(atMillis: Long) {
        handler.removeCallbacks(retryRunnable)
        handler.postAtTime(retryRunnable, atMillis)
    }

    private fun perform(result: PolicyResult) {
        if (result.decision == Decision.NONE) return
        // Events can arrive late: never press Back or Home in another app.
        if (!instagramInFront()) {
            log("action", "-", result.copy(reason = "skipped ${result.decision}: Instagram not in front"), always = true)
            return
        }
        val done = when (result.decision) {
            Decision.NONE -> return
            Decision.GO_HOME -> clickHomeTab() || performGlobalAction(GLOBAL_ACTION_BACK)
            Decision.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Decision.GLOBAL_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
        if (!done) log("action", "-", result.copy(reason = "action failed: ${result.decision}"), always = true)
        val category = result.policy.pending?.category
        if (done && category != null) stats.update { actionTaken(it, category) }
        showToast(R.string.toast_blocked)
    }

    private fun clickHomeTab(): Boolean {
        val root = activeRoot() ?: return false
        return try {
            val nodes = root.findAccessibilityNodeInfosByViewId(InstagramRules.fullId(InstagramRules.HOME_TAB_ID))
            try {
                nodes.firstOrNull { it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            } finally {
                nodes.forEach { it.release() }
            }
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "home tab click failed: ${e.javaClass.simpleName}")
            false
        } finally {
            root.release()
        }
    }

    private fun instagramInFront(): Boolean = activePackage() == InstagramRules.PACKAGE

    /** Package of the active window (nothing else about it is read). */
    private fun activePackage(): String? {
        val root = activeRoot() ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.release()
        }
    }

    /** A keyboard window is on screen (window type only; nothing else about it is read). */
    private fun keyboardShown(): Boolean = anyWindow { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    /** The active window is a keyboard. */
    private fun inputMethodActive(): Boolean = anyWindow { it.isActive && it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    private fun anyWindow(predicate: (AccessibilityWindowInfo) -> Boolean): Boolean = try {
        val all = windows
        try {
            all.any(predicate)
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                all.forEach { it.recycle() }
            }
        }
    } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "window list failed: ${e.javaClass.simpleName}")
        false
    }

    private fun activeRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "active window root failed: ${e.javaClass.simpleName}")
        null
    }

    /** Runs [block] on the event's source node and releases it; null if there is none or it is stale. */
    private fun <T> withSource(event: AccessibilityEvent, block: (AccessibilityNodeInfo) -> T?): T? = try {
        event.source?.let { source ->
            try {
                block(source)
            } finally {
                source.release()
            }
        }
    } catch (e: RuntimeException) {
        Log.w(LOG_TAG, "event source failed: ${e.javaClass.simpleName}")
        null
    }

    /**
     * Debug log: actions and [always] entries, other results only when the screen or reason
     * changes. Logcat: every action and every screen change. Ids and indexes only.
     */
    private fun log(eventType: String, screen: String, result: PolicyResult, always: Boolean = false) {
        val screenChanged = eventType == "scan" && screen != lastScreen
        if (eventType == "scan") lastScreen = screen
        if (result.decision != Decision.NONE || screenChanged || always) {
            Log.d(LOG_TAG, "$eventType $screen decision=${result.decision} reason=${result.reason}")
        }
        val key = screen to result.reason
        if (!always && result.decision == Decision.NONE && key == lastLogged) return
        lastLogged = key
        DebugLog.add(DebugEntry(System.currentTimeMillis(), eventType, screen, result.decision.name, result.reason))
    }

    private fun showToast(messageRes: Int) {
        val now = now()
        if (now - lastToastAt < TOAST_INTERVAL_MS) return
        lastToastAt = now
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun describe(classification: Classification): String = buildString {
        append(classification.screen.name)
        if (classification.sharedInDm) append(" shared")
        classification.reelPage?.let { append(" page=").append(it) }
        append(" tab=").append(classification.selectedTab ?: "none")
        append(" tabBar=").append(if (classification.tabBarVisible) "visible" else "hidden")
        append(" act=").append(activityName())
    }

    /** A bug here must not crash the service (Android would unbind it); log it instead. */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.e(LOG_TAG, "$what failed", e)
        }
    }

    /** Verbose diagnostics: debug capture on, or `adb shell setprop log.tag.Scrollhalt VERBOSE`. */
    private fun verbose(): Boolean = settings.debugCapture || Log.isLoggable(LOG_TAG, Log.VERBOSE)

    /** Ids, flags and bounds of every node in the snapshot. Never text or content descriptions. */
    private fun dumpTree(root: UiNode) {
        fun visit(node: UiNode, depth: Int) {
            val b = node.bounds
            val flags = (if (node.isVisibleToUser) "V" else "-") + (if (node.isSelected) "S" else "-")
            val id = node.viewId?.substringAfter(":id/") ?: "."
            Log.v(LOG_TAG, "tree ${" ".repeat(depth)}$id $flags ${b.left},${b.top}-${b.right},${b.bottom} key=${node.key} children=${node.children.size}")
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
    }

    private fun activityName(): String = activity?.substringAfterLast('.') ?: "unknown"

    private fun now(): Long = SystemClock.uptimeMillis()

    private companion object {
        const val SCAN_DELAY_MS: Long = 100
        const val TOAST_INTERVAL_MS: Long = 5_000

        /** The "blocking resumes" toast comes this long before a pause ends. */
        const val HEADS_UP_MS: Long = 10_000
    }
}
