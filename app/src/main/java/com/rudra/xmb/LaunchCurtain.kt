package com.rudra.xmb

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * A black window over everything while PPSSPP starts a game, so the switch between apps and
 * PPSSPP's own loading screen never show: the game boot ends in black and the game appears out of it.
 * Needs "Display over other apps"; without it, launching works as before.
 */
class LaunchCurtain(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    val allowed: Boolean get() = Settings.canDrawOverlays(ctx)

    /** Covers the screen now, until [release] (or [MAX_MS] at the most). */
    fun show() {
        hide()
        if (!allowed) return
        val v = View(ctx).apply { setBackgroundColor(Color.BLACK) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not focusable, so the game gets every button press from the start. It does catch touches
            // for these few seconds: Android only lets touches through an overlay at 80% opacity or less,
            // and then PPSSPP's loading screen would show through it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
        try {
            wm.addView(v, lp)
        } catch (e: Exception) {
            return // permission withdrawn in the meantime
        }
        view = v
        handler.postDelayed({ fade() }, MAX_MS)
    }

    /** Holds [holdMs] more from now, then fades away. Called again, it starts the wait over. */
    fun release(holdMs: Long) {
        if (view == null) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ fade() }, holdMs)
    }

    private fun fade() {
        view?.animate()?.alpha(0f)?.setDuration(FADE_MS)?.withEndAction { hide() }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        view?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        view = null
    }

    companion object {
        private const val FADE_MS = 450L
        private const val MAX_MS = 20_000L
    }
}
