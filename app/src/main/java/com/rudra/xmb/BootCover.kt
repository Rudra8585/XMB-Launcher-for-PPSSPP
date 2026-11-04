package com.rudra.xmb

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Holds the screen black at power-on, over the status bar, until the menu is up.
 *
 * Between the boot animation and the unlock Android shows its own placeholder home with the status bar,
 * and this app's home screen can't run before the unlock (Android would then forget the user's choice of
 * home app). An accessibility service that runs before the unlock is started by Android itself, early,
 * and only an accessibility overlay can sit above the status bar. It reads nothing: it asks for no
 * events and no window content. It has to be turned on in Settings › Accessibility.
 *
 * Everything here is wrapped so that it can never take the app down with it.
 */
class BootCover : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    override fun onServiceConnected() {
        current = this
        try {
            // Only at power-on: Android also connects the service when it is turned on, or after a crash.
            if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS || menuUp || !wanted(this)) return
            val v = View(this).apply { setBackgroundColor(Color.BLACK) }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            )
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
            getSystemService(WindowManager::class.java).addView(v, lp)
            view = v
            Log.i(TAG, "Boot cover up")
            handler.postDelayed({ remove() }, MAX_MS) // in case the menu never comes
        } catch (e: Exception) {
            Log.w(TAG, "Boot cover not shown", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        remove()
        if (current === this) current = null
        super.onDestroy()
    }

    private fun remove() {
        handler.removeCallbacksAndMessages(null)
        try {
            view?.let {
                getSystemService(WindowManager::class.java).removeView(it)
                Log.i(TAG, "Boot cover down")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Boot cover not removed", e)
        }
        view = null
    }

    companion object {
        private const val TAG = "BootCover"
        private const val KEY = "coverAtBoot"
        /** The cover only goes up this soon after the device started. */
        private const val BOOT_WINDOW_MS = 90_000L
        private const val MAX_MS = 15_000L
        /** Lets the menu's first frame reach the screen before the cover goes. */
        private const val HANDOVER_MS = 250L

        private var current: BootCover? = null
        private var menuUp = false

        /** The menu is on screen: the cover goes. */
        fun dismiss() {
            menuUp = true
            current?.let { s -> s.handler.postDelayed({ s.remove() }, HANDOVER_MS) }
        }

        /**
         * Remembers, where it can be read before the unlock, whether the next power-on should be covered:
         * only when this app is the home screen, or the cover would sit there with no menu to follow.
         */
        fun setWanted(context: Context, on: Boolean) {
            try {
                val sp = context.createDeviceProtectedStorageContext().getSharedPreferences("boot", Context.MODE_PRIVATE)
                if (sp.getBoolean(KEY, false) != on) sp.edit().putBoolean(KEY, on).apply()
            } catch (e: Exception) {}
        }

        private fun wanted(context: Context) = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("boot", Context.MODE_PRIVATE).getBoolean(KEY, false)
    }
}
