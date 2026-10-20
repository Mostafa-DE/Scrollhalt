package app.scrollhalt.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import app.scrollhalt.detect.Bounds
import app.scrollhalt.detect.InstagramRules

/**
 * An opaque patch drawn over Instagram's Reels tab button so it cannot be tapped. It is an
 * accessibility overlay window, which needs no extra permission, and it swallows taps inside
 * its bounds only. Its colour matches Instagram's tab bar, which follows the system theme.
 */
class ReelsTabCover(private val service: AccessibilityService, private val onTap: () -> Unit) {
    private val windowManager: WindowManager = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var placed: Bounds? = null
    private var placedColor: Int? = null

    /** Places the cover over [bounds] (screen pixels), or moves it there. */
    fun show(bounds: Bounds) {
        val color = tabBarColor()
        val current = view
        if (current != null && bounds == placed && color == placedColor) return
        val params = layoutParams(bounds)
        try {
            if (current == null) {
                val created = View(service).apply {
                    setBackgroundColor(color)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setOnClickListener { onTap() }
                    setOnLongClickListener { true }
                }
                windowManager.addView(created, params)
                view = created
            } else {
                current.setBackgroundColor(color)
                windowManager.updateViewLayout(current, params)
            }
            placed = bounds
            placedColor = color
            Log.d(LOG_TAG, "cover shown at ${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}")
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "cover failed: ${e.javaClass.simpleName}")
            view = null
            placed = null
        }
    }

    fun hide() {
        val current = view ?: return
        view = null
        placed = null
        try {
            windowManager.removeViewImmediate(current)
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "cover removal failed: ${e.javaClass.simpleName}")
        }
        Log.d(LOG_TAG, "cover removed")
    }

    private fun layoutParams(bounds: Bounds): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            bounds.width,
            bounds.height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
            title = "Scrollhalt Reels cover"
        }

    private fun tabBarColor(): Int {
        val night = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) InstagramRules.TAB_BAR_COLOR_DARK else InstagramRules.TAB_BAR_COLOR_LIGHT
    }
}
