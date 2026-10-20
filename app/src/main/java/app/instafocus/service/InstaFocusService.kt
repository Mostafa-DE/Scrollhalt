package app.instafocus.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private val scanRunnable = Runnable { scan() }
    private var scanPending = false
    private var scope: CoroutineScope? = null

    private var settings = AppSettings()
    private var policy = ReelPolicy()
    private var lastLogged: Pair<String, String>? = null
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
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                apply(PolicyInput.ViewClicked(now(), sourceViewId(event)), "click")
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
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
        scanPending = false
        scope?.cancel()
        scope = null
        ServiceState.setConnected(false)
    }

    private fun onScrolled(event: AccessibilityEvent) {
        val viewId = sourceViewId(event)
        if (InstagramRules.reelViewerIds.any { InstagramRules.matches(viewId, it) }) {
            apply(PolicyInput.PagerScrolled(now(), event.fromIndex), "pager scroll")
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
        } ?: return
        try {
            if (root.packageName?.toString() != InstagramRules.PACKAGE) return
            val snapshot = NodeSnapshot.of(root) ?: return
            if (settings.debugCapture) DebugLog.setSnapshot(snapshot.onScreenIds())
            apply(PolicyInput.ScreenSeen(now(), ScreenClassifier.classify(snapshot)), "scan")
        } finally {
            root.release()
        }
    }

    private fun apply(input: PolicyInput, eventType: String) {
        val policySettings = PolicySettings(settings.blockingEnabled, settings.allowSingleReel)
        val result = policy.reduce(input, policySettings)
        policy = result.policy
        val screen = (input as? PolicyInput.ScreenSeen)?.classification?.let(::describe) ?: "-"
        log(eventType, screen, result)
        perform(result)
    }

    private fun perform(result: PolicyResult) {
        val done = when (result.decision) {
            Decision.NONE -> return
            Decision.GO_HOME -> clickHomeTab() || performGlobalAction(GLOBAL_ACTION_BACK)
            Decision.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Decision.SNAP_BACK -> scrollPager(result.snapDirection) || performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (!done) log("action", "-", result.copy(reason = "action failed: ${result.decision}"))
        showToast(if (result.decision == Decision.SNAP_BACK) R.string.toast_single_reel else R.string.toast_blocked)
    }

    private fun clickHomeTab(): Boolean =
        withNode(listOf(InstagramRules.HOME_TAB_ID)) { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }

    private fun scrollPager(direction: SnapDirection?): Boolean {
        val action = when (direction) {
            SnapDirection.FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            SnapDirection.BACKWARD, null -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return withNode(InstagramRules.reelViewerIds) { it.performAction(action) }
    }

    /** Runs [block] on the first visible live node with one of [names]; false if none or stale. */
    private fun withNode(names: List<String>, block: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (_: RuntimeException) {
            null
        } ?: return false
        return try {
            names.firstNotNullOfOrNull { name ->
                val nodes = root.findAccessibilityNodeInfosByViewId(InstagramRules.fullId(name))
                try {
                    nodes.firstOrNull { it.isVisibleToUser }?.let(block)
                } finally {
                    nodes.forEach { it.release() }
                }
            } ?: false
        } catch (_: RuntimeException) {
            false
        } finally {
            root.release()
        }
    }

    private fun sourceViewId(event: AccessibilityEvent): String? = try {
        event.source?.let { source ->
            try {
                source.viewIdResourceName
            } finally {
                source.release()
            }
        }
    } catch (_: RuntimeException) {
        null
    }

    /** Logs actions always, and other results only when the screen or reason changes. */
    private fun log(eventType: String, screen: String, result: PolicyResult) {
        val key = screen to result.reason
        if (result.decision == Decision.NONE && key == lastLogged) return
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
        if (classification.reelsTabSelected) append(" +reelsTab")
        if (classification.dmOverlay) append(" +dm")
    }

    private fun now(): Long = SystemClock.uptimeMillis()

    private companion object {
        const val SCAN_DELAY_MS: Long = 100
        const val TOAST_INTERVAL_MS: Long = 5_000
    }
}
