package com.rudra.xmb

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/** An installed app that can be launched from Game › Android Apps. */
class AndroidApp(val pkg: String, val label: String, val icon: Bitmap)

/** Finds launchable apps and draws their icons as glossy bubbles. */
class AndroidApps(private val ctx: Context) {
    private val bubbles = HashMap<String, Bitmap>()

    /** Every app on the device that can be opened (phone or TV launcher entry), by name. Slow: run off the main thread. */
    fun installed(): List<AndroidApp> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(0)
            .filter { it.packageName != ctx.packageName && launchIntent(it.packageName) != null }
            .map { AndroidApp(it.packageName, it.loadLabel(pm).toString(), bubble(it.packageName, it.loadIcon(pm))) }
            .sortedBy { it.label.lowercase() }
    }

    private fun launchIntent(pkg: String): Intent? = ctx.packageManager.let {
        it.getLaunchIntentForPackage(pkg) ?: it.getLeanbackLaunchIntentForPackage(pkg)
    }

    /** The apps in [packages] that are still installed, in that order. */
    fun of(packages: List<String>): List<AndroidApp> {
        val pm = ctx.packageManager
        return packages.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                AndroidApp(pkg, info.loadLabel(pm).toString(), bubble(pkg, info.loadIcon(pm)))
            } catch (e: Exception) {
                null // uninstalled since it was added
            }
        }
    }

    fun launch(pkg: String): Boolean {
        val intent = launchIntent(pkg) ?: return false
        return try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) {
            false
        }
    }

    private fun bubble(pkg: String, icon: Drawable): Bitmap = synchronized(bubbles) {
        bubbles.getOrPut(pkg) { drawBubble(icon) }
    }

    /**
     * The app's icon inside a round bubble: the artwork fills the circle, with a soft gloss across
     * the top, a faint glow along the bottom and a bright rim, like a glass bead.
     */
    private fun drawBubble(icon: Drawable): Bitmap {
        val n = SIZE
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val cx = n / 2f
        val cy = n / 2f
        val r = n / 2f - 5f
        val circle = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Drop shadow under the bead.
        p.shader = RadialGradient(cx, cy + 3f, r + 4f, intArrayOf(0x66000000, 0x00000000), floatArrayOf(0.85f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy + 3f, r + 4f, p)
        p.shader = null

        c.save()
        c.clipPath(circle)
        if (icon is AdaptiveIconDrawable) {
            // Full-bleed layers, a little larger than the circle so the system's own mask shape is cut away.
            val grow = r * 0.42f
            icon.background?.let { it.setBounds((cx - r - grow).toInt(), (cy - r - grow).toInt(), (cx + r + grow).toInt(), (cy + r + grow).toInt()); it.draw(c) }
            icon.foreground?.let { it.setBounds((cx - r - grow).toInt(), (cy - r - grow).toInt(), (cx + r + grow).toInt(), (cy + r + grow).toInt()); it.draw(c) }
        } else {
            // Legacy icons keep their own shape, sitting on a pale glass backing.
            p.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0xFFF4F6FA.toInt(), 0xFFC9CFDA.toInt(), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            p.shader = null
            val inset = (r * 0.42f).toInt()
            icon.setBounds((cx - r).toInt() + inset, (cy - r).toInt() + inset, (cx + r).toInt() - inset, (cy + r).toInt() - inset)
            icon.draw(c)
        }
        // Edge shading, so the disc reads as a sphere.
        p.shader = RadialGradient(cx, cy - r * 0.15f, r * 1.05f, intArrayOf(0x00000000, 0x00000000, 0x70000000),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        // Light welling up inside the bottom edge.
        p.shader = RadialGradient(cx, cy + r * 1.05f, r * 0.85f, intArrayOf(0x90FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        // Glossy cap across the top, bright at its edge and fading towards the middle.
        p.shader = LinearGradient(0f, cy - r * 0.95f, 0f, cy - r * 0.05f, 0xE6FFFFFF.toInt(), 0x08FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - r * 0.8f, cy - r * 0.95f, cx + r * 0.8f, cy - r * 0.05f), p)
        // Small specular highlight.
        p.shader = RadialGradient(cx - r * 0.38f, cy - r * 0.55f, r * 0.2f, intArrayOf(0xF0FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx - r * 0.38f, cy - r * 0.55f, r * 0.2f, p)
        p.shader = null
        c.restore()

        // Rim.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.argb(200, 255, 255, 255)
        c.drawCircle(cx, cy, r - 1.5f, p)
        p.strokeWidth = 1.2f
        p.color = Color.argb(90, 0, 0, 0)
        c.drawCircle(cx, cy, r + 0.6f, p)
        return b
    }

    companion object {
        private const val SIZE = 128
    }
}
