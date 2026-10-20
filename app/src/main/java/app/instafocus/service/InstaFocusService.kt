package app.instafocus.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.instafocus.R
import app.instafocus.data.AppSettings
import app.instafocus.data.SettingsRepository
import app.instafocus.debug.DebugEntry
import app.instafocus.debug.DebugLog
import app.instafocus.detect.Classification
import app.instafocus.detect.Decision
import app.instafocus.detect.InstagramRules
import app.instafocus.detect.PolicyInput
import app.instafocus.detect.PolicyResult
import app.instafocus.detect.PolicySettings
import app.instafocus.detect.ReelPolicy
import app.instafocus.detect.ScreenClassifier
import app.instafocus.detect.SnapDirection
import app.instafocus.detect.UiNode
import app.instafocus.detect.onScreenIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches Instagram's view tree and leaves the Reels feed when it appears. All work runs
 * on the main thread in response to events: there is no polling and nothing runs while idle.
 */
class InstaFocusService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = Runnable { safely("scan") { scan() } }
    private val retryRunnable = Runnable { safely("retry scan") { scan() } }
    private var scanPending = false
    private var scope: CoroutineScope? = null

    private var settings = AppSettings()
    private var policy = ReelPolicy()
    private var activity: String? = null
    private var lastLogged: Pair<String, String>? = null
    private var lastScreen: String? = null
    private var lastToastAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = serviceScope
        serviceScope.launch {
            SettingsRepository(this@InstaFocusService).settings.collect { settings = it }
        }
        ServiceState.setConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != InstagramRules.PACKAGE) return
        safely("event ${event.eventType}") { onInstagramEvent(event) }
    }

    private fun onInstagramEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClicked(event)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                trackActivity(event.className?.toString())
                scheduleScan()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleScan()
            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(retryRunnable)
        scanPending = false
        scope?.cancel()
        scope = null
        ServiceState.setConnected(false)
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
            Log.d(TAG, "activity=$className")
            DebugLog.add(DebugEntry(System.currentTimeMillis(), "activity", className.substringAfterLast('.'), "-", "activity changed"))
        }
    }

    private fun onClicked(event: AccessibilityEvent) {
        val viewId = withSource(event) { it.viewIdResourceName }
        if (verbose()) Log.v(TAG, "click event view=${viewId?.substringAfter(":id/") ?: "-"}")
        val result = apply(PolicyInput.ViewClicked(now(), viewId))
        log("click", "view=${viewId?.substringAfter(":id/") ?: "-"}", result)
        perform(result, pager = null)
    }

    private fun onScrolled(event: AccessibilityEvent) {
        withSource(event) { source ->
            val viewId = source.viewIdResourceName
            if (InstagramRules.reelViewerIds.any { InstagramRules.matches(viewId, it) }) {
                // AccessibilityNodeInfo equality is view identity (window + view id), so its
                // hash tells two pagers apart even though they share a resource id.
                val key = "${event.windowId}:${source.hashCode()}"
                val result = apply(PolicyInput.PagerScrolled(now(), key, event.fromIndex))
                val details = "act=${activityName()} from=${event.fromIndex} to=${event.toIndex} " +
                    "count=${event.itemCount} pager=$key"
                log("pager scroll", details, result, always = true)
                perform(result, pager = source)
            }
        }
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
        val root = try {
            rootInActiveWindow
        } catch (_: RuntimeException) {
            null
        }
        if (root == null) {
            if (verbose()) Log.v(TAG, "scan: no active window root")
            return
        }
        try {
            if (verbose()) Log.v(TAG, "scan: root package=${root.packageName} window=${root.windowId}")
            if (root.packageName?.toString() != InstagramRules.PACKAGE) {
                log("scan", "outside Instagram", apply(PolicyInput.LeftApp(now())))
                return
            }
            val snapshot = NodeSnapshot.of(root) ?: return
            if (verbose()) dumpTree(snapshot)
            if (settings.debugCapture) DebugLog.setSnapshot(snapshot.onScreenIds())
            val classification = ScreenClassifier.classify(snapshot, activity)
            val result = apply(PolicyInput.ScreenSeen(now(), classification))
            log("scan", describe(classification), result)
            perform(result, pager = null)
        } finally {
            root.release()
        }
    }

    private fun apply(input: PolicyInput): PolicyResult {
        val result = policy.reduce(input, PolicySettings(settings.blockingEnabled, settings.allowSingleReel))
        policy = result.policy
        result.retryAtMillis?.let(::scheduleRetry)
        return result
    }

    /** One scan at [atMillis] (uptime) to judge or retry an action, even if Instagram sends no events. */
    private fun scheduleRetry(atMillis: Long) {
        handler.removeCallbacks(retryRunnable)
        handler.postAtTime(retryRunnable, atMillis)
    }

    /** [pager] is the scroll event's source; only that pager may be scrolled back. */
    private fun perform(result: PolicyResult, pager: AccessibilityNodeInfo?) {
        val done = when (result.decision) {
            Decision.NONE -> return
            Decision.GO_HOME -> clickHomeTab() || performGlobalAction(GLOBAL_ACTION_BACK)
            Decision.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Decision.SNAP_BACK -> scrollPager(pager, result.snapDirection) || performGlobalAction(GLOBAL_ACTION_BACK)
            Decision.GLOBAL_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
        if (!done) log("action", "-", result.copy(reason = "action failed: ${result.decision}"), always = true)
        showToast(if (result.decision == Decision.SNAP_BACK) R.string.toast_single_reel else R.string.toast_blocked)
    }

    private fun clickHomeTab(): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (_: RuntimeException) {
            null
        } ?: return false
        return try {
            val nodes = root.findAccessibilityNodeInfosByViewId(InstagramRules.fullId(InstagramRules.HOME_TAB_ID))
            try {
                nodes.firstOrNull { it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            } finally {
                nodes.forEach { it.release() }
            }
        } catch (_: RuntimeException) {
            false
        } finally {
            root.release()
        }
    }

    private fun scrollPager(pager: AccessibilityNodeInfo?, direction: SnapDirection?): Boolean {
        val action = when (direction) {
            SnapDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            SnapDirection.BACKWARD, null -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return try {
            pager?.performAction(action) ?: false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Runs [block] on the event's source node and releases it; null if there is none or it is stale. */
    private fun <T> withSource(event: AccessibilityEvent, block: (AccessibilityNodeInfo) -> T): T? = try {
        event.source?.let { source ->
            try {
                block(source)
            } finally {
                source.release()
            }
        }
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Debug log: actions and [always] entries, other results only when the screen or reason
     * changes. Logcat (tag [TAG], for adb diagnosis): every action and every screen change.
     * Ids and indexes only, never node text or content descriptions.
     */
    private fun log(eventType: String, screen: String, result: PolicyResult, always: Boolean = false) {
        val screenChanged = eventType == "scan" && screen != lastScreen
        if (eventType == "scan") lastScreen = screen
        if (result.decision != Decision.NONE || screenChanged) {
            Log.d(TAG, "$eventType $screen decision=${result.decision} reason=${result.reason}")
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
        append(" tab=").append(classification.selectedTab ?: "none")
        append(" tabBar=").append(if (classification.tabBarVisible) "visible" else "hidden")
        append(" act=").append(activityName())
    }

    /** A bug here must not crash the service (Android would unbind it); log it instead. */
    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.e(TAG, "$what failed", e)
        }
    }

    /** Verbose diagnostics: debug capture on, or `adb shell setprop log.tag.InstaFocus VERBOSE`. */
    private fun verbose(): Boolean = settings.debugCapture || Log.isLoggable(TAG, Log.VERBOSE)

    /** Ids, flags and bounds of every node in the snapshot. Never text or content descriptions. */
    private fun dumpTree(root: UiNode) {
        fun visit(node: UiNode, depth: Int) {
            val b = node.bounds
            val flags = (if (node.isVisibleToUser) "V" else "-") + (if (node.isSelected) "S" else "-")
            val id = node.viewId?.substringAfter(":id/") ?: "."
            Log.v(TAG, "tree ${" ".repeat(depth)}$id $flags ${b.left},${b.top}-${b.right},${b.bottom} children=${node.children.size}")
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
    }

    private fun activityName(): String = activity?.substringAfterLast('.') ?: "unknown"


    private fun now(): Long = SystemClock.uptimeMillis()

    private companion object {
        const val SCAN_DELAY_MS: Long = 100
        const val TOAST_INTERVAL_MS: Long = 5_000
        const val TAG: String = "InstaFocus"
    }
}
