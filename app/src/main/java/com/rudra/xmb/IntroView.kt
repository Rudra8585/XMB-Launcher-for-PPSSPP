package com.rudra.xmb

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Full-screen sequences drawn on top of the XMB, timed against recordings of a real PSP:
 * [Mode.BOOT] is the power-on sequence, [Mode.GAME] is the game boot shown when a game starts.
 * Positions are fractions of the screen so they hold on any 16:9-ish display.
 */
@SuppressLint("ViewConstructor")
class IntroView(
    context: Context,
    private val mode: Mode,
    /** Shown between the lines at power-on; from the asset pack's boot.txt, none without it. */
    private val bootText: String?,
    /** Under the game boot logo; from the asset pack's gameboot.txt. Without it there is no logo. */
    private val gameText: String?,
    private val onCue: () -> Unit, // play the sound at the right moment
    private val onDone: () -> Unit,
) : View(context) {
    enum class Mode { BOOT, GAME }

    private val start = System.nanoTime()

    /** Debug builds can pin the animation to one moment for inspection; < 0 means play normally. */
    var frozenAt = -1f
    private var cued = false
    private var done = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val sceFont = Typeface.create("sans-serif", Typeface.NORMAL)
    private val logoFont = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private val duration = if (mode == Mode.BOOT) BOOT_END else GAME_END

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true // both sequences always play out

    val isBoot get() = mode == Mode.BOOT

    private fun finish() {
        if (done) return
        done = true
        onDone()
    }

    override fun onDraw(canvas: Canvas) {
        val t = if (frozenAt >= 0f) frozenAt else (System.nanoTime() - start) / 1e9f
        if (!cued && t >= (if (mode == Mode.BOOT) BOOT_CUE else GAME_CUE)) { cued = true; onCue() }
        if (mode == Mode.BOOT) drawBoot(canvas, t) else drawGame(canvas, t)
        if (frozenAt >= 0f) return
        if (t >= duration) finish() else postInvalidateOnAnimation()
    }

    private fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    /** 0 before [a], 1 after [b], smooth in between. */
    private fun phase(t: Float, a: Float, b: Float) = ease((t - a) / (b - a))

    private fun argb(a: Float, rgb: Int) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)

    // -------------------------------------------------------------- power on

    /**
     * Only the "Sony Computer Entertainment" lettering. The black, the metal hairlines it sits between,
     * the theme-coloured glow and the lines turning into waves are all rendered by [WaveView], so the
     * lines can become the waves without a hand-over.
     */
    private fun drawBoot(c: Canvas, t: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val label = bootText ?: return
        val words = phase(t, 0.3f, 0.6f) * (1f - phase(t, 1.95f, 2.4f))
        if (words > 0f) {
            // Small and light enough to sit between the two hairlines, spaced out rather than stretched.
            text.typeface = sceFont
            text.textSize = h * 0.028f
            text.letterSpacing = 0.16f
            fitWidth(label, w * 0.37f)
            text.color = argb(words, 0xFFFFFF)
            c.drawText(label, w / 2, h * 0.4915f + text.textSize * 0.36f, text)
            text.textScaleX = 1f
        }
    }

    /** Stretches [text] horizontally so [s] spans [target] pixels, matching the PSP's wide lettering. */
    private fun fitWidth(s: String, target: Float) {
        text.textScaleX = 1f
        text.textScaleX = target / text.measureText(s)
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * f).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    // -------------------------------------------------------------- game boot

    /**
     * Over the menu-less waves: the scene washes out to white through a burst of soft sky-blue and
     * lime/yellow light, the PSP logo settles in grey on white and holds, then everything fades
     * through grey to black before the game starts.
     */
    private fun drawGame(c: Canvas, t: Float) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Light washing over the waves, ending in solid white.
        val wash = phase(t, 0.12f, 0.55f) * 0.72f + phase(t, 1.05f, 1.3f) * 0.28f
        val tint = blend(0xD4ECF8, 0xFFFFFF, phase(t, 1.05f, 1.3f)) // pale sky that settles on pure white
        paint.shader = LinearGradient(0f, 0f, w, h, argb(wash, tint), argb(wash, 0xFFFFFF), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Bright streaks sweeping across just before the burst.
        val streak = phase(t, 0.12f, 0.3f) * (1f - phase(t, 0.38f, 0.55f))
        if (streak > 0f) {
            val x = w * (-0.3f + 1.6f * phase(t, 0.12f, 0.55f))
            paint.shader = LinearGradient(x - w * 0.35f, 0f, x + w * 0.35f, h * 0.25f,
                intArrayOf(argb(0f, 0xFFFFFF), argb(streak * 0.8f, 0xFFFFFF), argb(0f, 0xFFFFFF)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        // The colour burst: large soft orbs that swell and fade into the white.
        val burst = phase(t, 0.55f, 0.85f) * (1f - phase(t, 1.0f, 1.22f))
        if (burst > 0f) {
            val grow = 0.85f + 0.3f * phase(t, 0.55f, 1.2f)
            orb(c, w * 0.16f, h * 0.18f, h * 0.75f * grow, 0x9ED6F2, burst * 0.85f)
            orb(c, w * 0.6f, h * 0.62f, h * 0.62f * grow, 0xB7E33A, burst)
            orb(c, w * 0.86f, h * 0.68f, h * 0.5f * grow, 0xF3EC55, burst * 0.95f)
            orb(c, w * 0.93f, h * 0.12f, h * 0.32f * grow, 0xFFFFFF, burst * 0.9f)
        }

        // Logo.
        val logo = phase(t, 0.42f, 0.8f)
        if (logo > 0f && gameText != null) drawPspLogo(c, w, h, logo, gameText)

        // Fade out through grey to black.
        val out = phase(t, 7.55f, 8.25f)
        if (out > 0f) {
            paint.color = argb(out, 0x000000)
            c.drawRect(0f, 0f, w, h, paint)
        }
    }

    private fun orb(c: Canvas, x: Float, y: Float, r: Float, rgb: Int, a: Float) {
        paint.shader = RadialGradient(x, y, r,
            intArrayOf(argb(a, rgb), argb(a * 0.75f, rgb), argb(0f, rgb)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, paint)
        paint.shader = null
    }

    /**
     * The PSP wordmark, built from thin bars:
     * P = top bar, full-height left stem, right side down to the middle, middle bar;
     * S = top bar on the right half, centre stem, bottom bar on the left half.
     */
    private fun drawPspLogo(c: Canvas, w: Float, h: Float, a: Float, caption: String) {
        val top = h * 0.400f
        val mid = h * 0.472f
        val bot = h * 0.537f
        val th = max(1.5f, h * 0.0032f) / 2
        bar.color = argb(a, 0x9A9A9A)
        fun hBar(x0: Float, x1: Float, y: Float) = c.drawRect(w * x0 - th, y - th, w * x1 + th, y + th, bar)
        fun vBar(x: Float, y0: Float, y1: Float) = c.drawRect(w * x - th, y0 - th, w * x + th, y1 + th, bar)
        fun p(x0: Float, x1: Float) {
            hBar(x0, x1, top); vBar(x0, top, bot); vBar(x1, top, mid); hBar(x0, x1, mid)
        }
        p(0.269f, 0.415f)
        hBar(0.4985f, 0.571f, top); vBar(0.4985f, top, bot); hBar(0.419f, 0.4985f, bot)
        p(0.597f, 0.737f)

        text.typeface = logoFont
        text.letterSpacing = 0f
        text.color = argb(a, 0xA2A2A2)
        // The caption's two words with the PSP's tiny, mid-height ® between them.
        val first = caption.substringBefore(' ')
        val second = caption.substringAfter(' ', "")
        text.textSize = h * 0.05f
        text.textAlign = Paint.Align.LEFT
        val gap = if (second.isEmpty()) 0f else h * 0.024f
        val words = text.measureText(first) + text.measureText(second)
        text.textScaleX = (w * 0.304f - gap) / words
        val a1 = text.measureText(first)
        val x0 = w * 0.501f - (a1 + gap + text.measureText(second)) / 2
        val base = h * 0.651f
        c.drawText(first, x0, base, text)
        c.drawText(second, x0 + a1 + gap, base, text)
        text.textScaleX = 1f
        text.textSize = h * 0.016f
        c.drawText("®", x0 + a1 + gap * 0.15f, base - h * 0.012f, text)
        text.textAlign = Paint.Align.CENTER
        text.textSize = h * 0.018f
        c.drawText("™", w * 0.748f, h * 0.548f, text)
    }

    companion object {
        /** When the black veil is gone and the waves show; the menu reveal is timed from here. */
        const val BOOT_END = 3.4f
        private const val BOOT_CUE = 0.15f
        const val GAME_END = 8.6f
        private const val GAME_CUE = 0.1f
    }
}
