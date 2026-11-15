package io.pooly.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import java.util.function.Consumer
import kotlin.math.min
import kotlin.math.roundToInt

/** Bounded frosted windows; every panel blurs only its own background. */
class NavigationPanelWindow(private val activity: Activity, private val web: WebView) {
    private val windows = activity.applicationContext.getSystemService(WindowManager::class.java)
    private var parent: ViewGroup? = null
    private var originalLayout: ViewGroup.LayoutParams? = null
    private var scale = 1f
    private var originX = 0
    private var originY = 0
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var glassDialog: Dialog? = null
    private var blurListener: Consumer<Boolean>? = null
    var mode = ""
        private set
    var backgroundBlurApplied = false
        private set
    val visible get() = mode.isNotEmpty()

    fun show(requestedMode: String) {
        require(requestedMode in setOf("create", "moving"))
        val first = !visible
        if (first) {
            // A hidden WebView can retain its previous small overlay measurement.
            // The activity's original container keeps the full AAOS viewport.
            val host = web.parent as ViewGroup
            val position = IntArray(2); host.getLocationOnScreen(position)
            val width = host.width.takeIf { it > 0 } ?: activity.window.decorView.width
            val height = host.height.takeIf { it > 0 } ?: activity.window.decorView.height
            originX = position[0]; originY = position[1]
            viewportWidth = width; viewportHeight = height
            scale = min(width / 1194f, height / 834f).coerceAtLeast(.45f)
            parent = host; originalLayout = web.layoutParams
            parent?.removeView(web); web.setBackgroundColor(Color.TRANSPARENT)
        }
        val moving = requestedMode == "moving"
        val designWidth = if (moving) 497 else 420
        val designHeight = if (moving) 175 else 680
        val params = WindowManager.LayoutParams(
            (designWidth * scale).roundToInt(), (designHeight * scale).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or (if (moving) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = if (moving) originX + viewportWidth - ((designWidth + 12) * scale).roundToInt()
                else originX + (12 * scale).roundToInt()
            y = if (moving) originY + (39 * scale).roundToInt()
                else originY + (viewportHeight - ((designHeight + 12) * scale).roundToInt()).coerceAtLeast(0)
            // Keep the glass panel at its original bounds. The IME overlays the bottom instead.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "Pooly navigation $requestedMode panel"
        }
        try {
            val sameSurface = !first && mode == requestedMode
            if (!first && !sameSurface) removeSurface()
            if (sameSurface) {
                glassDialog?.window?.attributes = params
            } else {
                val dialog = Dialog(ContextThemeWrapper(activity.applicationContext, R.style.NavigationPanelTheme))
                glassDialog = dialog
                dialog.setCancelable(false); dialog.setCanceledOnTouchOutside(false)
                dialog.setOnKeyListener { _, keyCode, keyEvent ->
                    if (keyCode != KeyEvent.KEYCODE_BACK) false else {
                        if (keyEvent.action == KeyEvent.ACTION_UP) web.evaluateJavascript("window.dispatchEvent(new CustomEvent('pooly:back'));", null)
                        true
                    }
                }
                dialog.setContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                dialog.window?.apply { attributes = params; decorView.setPadding(0, 0, 0, 0) }
                dialog.show()
                dialog.window?.attributes = params
                if (Build.VERSION.SDK_INT >= 31) {
                    val listener = Consumer<Boolean> { available -> applyGlass(available, moving) }
                    blurListener = listener; windows.addCrossWindowBlurEnabledListener(activity.mainExecutor, listener)
                    applyGlass(windows.isCrossWindowBlurEnabled, moving)
                } else applyGlass(false, moving)
            }
            mode = requestedMode
            web.evaluateJavascript("if(window.PoolyPanelMode)PoolyPanelMode('$requestedMode');", null)
            web.onResume()
        } catch (error: Exception) {
            if (glassDialog != null || web.parent != null && web.parent !== parent) try { removeSurface() } catch (_: Exception) {}
            if (web.parent == null) parent?.addView(web, originalLayout)
            parent = null; mode = ""; web.setBackgroundColor(Color.WHITE)
            throw error
        }
    }

    private fun applyGlass(available: Boolean, moving: Boolean) {
        val window = glassDialog?.window ?: return
        backgroundBlurApplied = Build.VERSION.SDK_INT >= 31 && available
        // The left panel covers a dark navigation toolbar, so its text needs more contrast.
        val opacity = if (moving) {
            if (backgroundBlurApplied) 55 else 205
        } else 232
        window.setBackgroundDrawable(GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24 * scale
            setColor(Color.argb(opacity, 248, 250, 253))
        })
        if (Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(if (backgroundBlurApplied) (32 * scale).roundToInt() else 0)
    }

    private fun removeSurface() {
        if (Build.VERSION.SDK_INT >= 31) blurListener?.let { windows.removeCrossWindowBlurEnabledListener(it) }
        blurListener = null; backgroundBlurApplied = false
        val dialog = glassDialog
        if (dialog != null) {
            (web.parent as? ViewGroup)?.removeView(web); dialog.dismiss(); glassDialog = null
        } else windows.removeViewImmediate(web)
    }

    fun close() {
        if (!visible) return
        removeSurface(); parent?.addView(web, originalLayout)
        parent = null; mode = ""; web.setBackgroundColor(Color.WHITE)
        web.evaluateJavascript("if(window.PoolyPanelMode)PoolyPanelMode('');", null)
    }
}
