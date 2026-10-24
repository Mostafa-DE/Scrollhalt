package app.scrollhalt.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import app.scrollhalt.R
import kotlin.math.roundToInt

/**
 * Scrollhalt's messages over Instagram: a small pill below the status bar, drawn as an
 * accessibility overlay (no permission). Toasts are not used because Android drops them for
 * apps whose notifications are off, and Scrollhalt has no notification permission. The banner
 * never takes touches or focus, shows one message at a time (a new one replaces it in place)
 * and hides itself after [VISIBLE_MS], except a message that is [hold]ed (the pause countdown).
 * What it shows or skips is logged (resource names only).
 */
class Banner(private val service: AccessibilityService) {
    private val windowManager: WindowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val fadeOutRunnable = Runnable { fadeOut() }
    private val lastShownAt = HashMap<Int, Long>()
    private var view: View? = null
    private var label: TextView? = null

    /** Shows [message] for [VISIBLE_MS], replacing the one on screen; the same message at most every [MIN_INTERVAL_MS]. */
    fun show(@StringRes message: Int) {
        val name = service.resources.getResourceEntryName(message)
        val now = SystemClock.uptimeMillis()
        val last = lastShownAt[message]
        if (last != null && now - last < MIN_INTERVAL_MS) {
            Log.d(LOG_TAG, "banner $name skipped: shown ${now - last} ms ago")
            return
        }
        lastShownAt[message] = now
        if (!display(service.getString(message))) return
        Log.d(LOG_TAG, "banner $name shown")
        handler.postDelayed(fadeOutRunnable, VISIBLE_MS)
    }

    /** Shows [text] until it is replaced or hidden: no timer and no rate limit. */
    fun hold(text: String): Boolean = display(text)

    /** Puts [text] on screen, in place if a banner is showing; false if the window could not be added. */
    private fun display(text: String): Boolean {
        handler.removeCallbacks(fadeOutRunnable)
        val current = view
        val currentLabel = label
        if (current != null && currentLabel != null) {
            currentLabel.text = text
            current.animate().cancel()
            current.alpha = 1f
            return true
        }
        return add(text)
    }

    fun hide() {
        handler.removeCallbacks(fadeOutRunnable)
        val current = view ?: return
        view = null
        label = null
        current.animate().cancel()
        try {
            windowManager.removeViewImmediate(current)
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "banner removal failed: ${e.javaClass.simpleName}")
        }
    }

    private fun fadeOut() {
        val current = view ?: return
        current.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction { if (view === current) hide() }.start()
    }

    private fun add(message: String): Boolean {
        val text = TextView(service).apply {
            setText(message)
            setTextColor(service.getColor(R.color.banner_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (service.resources.displayMetrics.widthPixels * MAX_WIDTH_FRACTION).roundToInt() - dp(MARK_DP + 40)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val pill = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(service.getColor(R.color.banner_background))
                cornerRadius = dp(24).toFloat()
            }
            elevation = dp(4).toFloat()
            setPadding(dp(8), dp(8), dp(16), dp(8))
            addView(mark(), LinearLayout.LayoutParams(dp(MARK_DP), dp(MARK_DP)))
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        }
        // Padding around the pill leaves room for its shadow inside the window.
        val root = FrameLayout(service).apply {
            setPadding(dp(8), dp(4), dp(8), dp(10))
            addView(pill)
            alpha = 0f
        }
        return try {
            windowManager.addView(root, layoutParams())
            view = root
            label = text
            root.animate().alpha(1f).setDuration(FADE_IN_MS).start()
            true
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "banner not added: ${e.javaClass.simpleName}")
            false
        }
    }

    /** The app mark as in the header: the launcher foreground, cropped to its middle, on brand green. */
    private fun mark(): View = FrameLayout(service).apply {
        background = GradientDrawable().apply {
            setColor(service.getColor(R.color.banner_mark))
            cornerRadius = dp(6).toFloat()
        }
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            ImageView(service).apply { setImageResource(R.drawable.ic_launcher_foreground) },
            FrameLayout.LayoutParams(dp(MARK_DP * 14 / 9), dp(MARK_DP * 14 / 9), Gravity.CENTER),
        )
    }

    private fun layoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topInset() + dp(4)
            title = "Scrollhalt banner"
        }

    /** Height of the status bar and any display cutout above it. */
    private fun topInset(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return dp(FALLBACK_TOP_DP)
        return try {
            windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "window insets failed: ${e.javaClass.simpleName}")
            dp(FALLBACK_TOP_DP)
        }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val VISIBLE_MS: Long = 2_500
        const val MIN_INTERVAL_MS: Long = 5_000
        const val FADE_IN_MS: Long = 150
        const val FADE_OUT_MS: Long = 200
        const val MAX_WIDTH_FRACTION: Float = 0.9f
        const val MARK_DP: Int = 22
        const val FALLBACK_TOP_DP: Int = 28
    }
}
