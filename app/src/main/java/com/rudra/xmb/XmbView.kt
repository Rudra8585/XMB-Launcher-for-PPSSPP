package com.rudra.xmb

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class XItem(
    val title: String,
    val icon: Bitmap? = null,
    val game: Game? = null,
    val subtitle: (() -> String?)? = null,
    val value: (() -> String?)? = null,
    /** Value picker: (key, label) pairs, the current key, and what to do on a pick. */
    val choices: (() -> List<Pair<String, String>>)? = null,
    val current: (() -> String)? = null,
    val onChoose: ((String) -> Unit)? = null,
    val action: (() -> Unit)? = null,
    val sub: XLevel? = null,
    /** A colour square shown beside the value, and beside each choice. */
    val swatch: (() -> Int)? = null,
    val choiceSwatch: ((String) -> Int)? = null,
)

class XLevel(
    val items: () -> List<XItem>,
    val emptyText: () -> String,
    val gameList: Boolean = false,
)

class XCategory(val title: String, val icon: Bitmap, val items: List<XItem>) {
    var sel = 0
    var anim = 0f
}

class XOption(val label: String, val action: () -> Unit)

interface XmbHost {
    fun sfx(id: Sfx.Id)
    fun onGameFocus(game: Game?)
    fun onLaunch(game: Game)
    fun gameOptions(game: Game): List<XOption>
    fun onIconRect(rect: RectF?)
    fun onExit()

    fun onSelectButton()
}

/**
 * The cross media bar. Everything is laid out on the PSP's 480x272 grid and scaled to the screen height.
 */
@SuppressLint("ViewConstructor")
class XmbView(context: Context, private val host: XmbHost, private val font: Typeface) : View(context) {
    private var cats: List<XCategory> = emptyList()
    private var cat = 0
    private var catAnim = 0f

    private class Sub(val parent: XItem, val level: XLevel) {
        var sel = 0
        var anim = 0f
    }

    private var sub: Sub? = null
    private var subShown: Sub? = null // stays while the level fades out
    private var depth = 0f

    private class Popup(
        val entries: List<String>, val checked: Int, val swatches: List<Int>? = null, val onPick: (Int) -> Unit,
    ) {
        var sel = max(0, checked)
        var scroll = sel.toFloat() // follows sel when the list is taller than the screen
    }

    private var popup: Popup? = null
    private var popupAnim = 0f

    private class Dialog(val message: String, val buttons: List<String>, val onButton: (Int) -> Unit) {
        var sel = 0
        val hit = ArrayList<RectF>()
    }

    private var dialog: Dialog? = null
    private var dialogAnim = 0f

    // Game backdrop (PIC1) and overlay (PIC0).
    private var pic1: Bitmap? = null
    private var pic0: Bitmap? = null
    private var picAlpha = 0f
    private var picTarget = 0f

    private var focusedGame: Game? = null
    private var focusSince = 0L
    private var lastIconRect: RectF? = null
    private var lastFrame = 0L
    private var s = 1f // pixels per PSP pixel
    private var vw = 480f // visible width in PSP pixels

    private val iconCache = HashMap<String, Bitmap?>()
    private val decoder = Executors.newSingleThreadExecutor()

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    // Linear, subpixel-positioned glyphs: hinted glyphs change their spacing when a label stops
    // animating and is re-rasterised at its final size.
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = font
        color = Color.WHITE
        setShadowLayer(2.5f, 0f, 0.8f, 0x99000000.toInt())
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clockFmt12 = SimpleDateFormat("M/d h:mm a", Locale.US)
    private val clockFmt24 = SimpleDateFormat("M/d H:mm", Locale.US)
    var clock24 = true

    private val gestures = GestureDetector(context, Gestures())

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---------------------------------------------------------------- public API

    fun setCategories(list: List<XCategory>, start: Int) {
        cats = list
        cat = start.coerceIn(0, list.size - 1)
        catAnim = cat.toFloat()
        invalidate()
    }

    /** Re-reads dynamic levels (the game list) after the library changes. */
    fun refresh() {
        sub?.let { s -> s.sel = s.sel.coerceIn(0, max(0, s.level.items().size - 1)) }
        updateFocus()
        invalidate()
    }

    fun showPics(p1: Bitmap?, p0: Bitmap?) {
        if (p1 != null || p0 != null) {
            pic1 = p1; pic0 = p0; picTarget = 1f
        } else picTarget = 0f
        invalidate()
    }

    fun showDialog(message: String, buttons: List<String>, selected: Int = 0, onButton: (Int) -> Unit) {
        dialog = Dialog(message, buttons, onButton).also { it.sel = selected }
        updateFocus()
        invalidate()
    }

    fun closeDialog() {
        dialog = null
        updateFocus()
        invalidate()
    }

    val hasDialog get() = dialog != null

    // Menu (icons, lists, game art) and clock opacity, so boot and game boot can fade them separately.
    private var menuAlpha = 1f
    private var menuTarget = 1f
    private var clockAlpha = 1f
    private var clockTarget = 1f
    private var chromeSpeed = 6f

    /** Fades the menu and clock towards the given opacities; [instant] jumps straight there. */
    fun setChrome(menu: Float, clock: Float, instant: Boolean = false, speed: Float = 6f) {
        menuTarget = menu; clockTarget = clock; chromeSpeed = speed
        if (instant) { menuAlpha = menu; clockAlpha = clock }
        invalidate()
    }

    fun showOptions(options: List<XOption>) {
        if (options.isEmpty()) return
        host.sfx(Sfx.Id.NOTICE)
        popup = Popup(options.map { it.label }, -1) { i -> options[i].action() }
        updateFocus()
        invalidate()
    }

    /** Opens [level] under [parent], for items whose action decides whether to enter it. */
    fun openLevel(parent: XItem, level: XLevel) {
        sub = Sub(parent, level)
        subShown = sub
        updateFocus()
        invalidate()
    }

    /** Leaves any sub level, e.g. when the game folder changes. */
    fun popToRoot() {
        sub = null
        popup = null
        updateFocus()
        invalidate()
    }

    // ---------------------------------------------------------------- navigation

    private fun items(): List<XItem> = sub?.level?.items?.invoke() ?: cats.getOrNull(cat)?.items ?: emptyList()

    private fun selectedIndex() = sub?.sel ?: cats.getOrNull(cat)?.sel ?: 0

    private fun setSelected(i: Int) {
        val s = sub
        if (s != null) s.sel = i else cats.getOrNull(cat)?.sel = i
    }

    private fun move(dy: Int) {
        val list = items()
        if (list.isEmpty()) return
        val next = (selectedIndex() + dy).coerceIn(0, list.size - 1)
        if (next == selectedIndex()) return
        setSelected(next)
        host.sfx(if (dy < 0) Sfx.Id.UP else Sfx.Id.DOWN)
        updateFocus()
        invalidate()
    }

    private fun moveCategory(dx: Int) {
        if (sub != null) return
        val next = (cat + dx).coerceIn(0, cats.size - 1)
        if (next == cat) return
        cat = next
        host.sfx(if (dx < 0) Sfx.Id.UP else Sfx.Id.DOWN)
        updateFocus()
        invalidate()
    }

    private fun confirm() {
        val item = items().getOrNull(selectedIndex()) ?: return
        when {
            item.game != null -> host.onLaunch(item.game)
            item.sub != null -> {
                host.sfx(Sfx.Id.OK)
                sub = Sub(item, item.sub)
                subShown = sub
            }
            item.action != null -> { host.sfx(Sfx.Id.OK); item.action.invoke() } // its choices are under △
            item.choices != null -> openChoices(item)
        }
        updateFocus()
        invalidate()
    }

    private fun openChoices(item: XItem) {
        val choices = item.choices?.invoke() ?: return
        val cur = item.current?.invoke()
        host.sfx(Sfx.Id.NOTICE)
        popup = Popup(
            choices.map { it.second }, choices.indexOfFirst { it.first == cur },
            item.choiceSwatch?.let { sw -> choices.map { sw(it.first) } },
        ) { i -> item.onChoose?.invoke(choices[i].first) }
    }

    private fun back() {
        when {
            dialog != null -> { dialog!!.let { d -> d.onButton(d.buttons.size - 1) } }
            popup != null -> { popup = null; host.sfx(Sfx.Id.NOTICE_BACK) }
            sub != null -> { sub = null; host.sfx(Sfx.Id.CANCEL) }
            else -> host.onExit()
        }
        updateFocus()
        invalidate()
    }

    private fun options() {
        if (dialog != null || popup != null) return
        val item = items().getOrNull(selectedIndex()) ?: return
        when {
            item.game != null -> showOptions(host.gameOptions(item.game))
            item.choices != null -> openChoices(item)
        }
        invalidate()
    }

    private fun popupPick() {
        val p = popup ?: return
        popup = null
        host.sfx(Sfx.Id.OK)
        p.onPick(p.sel)
        updateFocus()
        invalidate()
    }

    private fun updateFocus() {
        val s = sub
        val game = if (s != null && popup == null && dialog == null) s.level.items().getOrNull(s.sel)?.game else null
        if (game !== focusedGame) {
            focusedGame = game
            focusSince = System.nanoTime()
            host.onGameFocus(game)
        }
    }

    // ---------------------------------------------------------------- input

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val d = dialog
        val p = popup
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> when {
                d != null -> {}
                p != null -> { p.sel = max(0, p.sel - 1); host.sfx(Sfx.Id.UP) }
                else -> move(-1)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                d != null -> {}
                p != null -> { p.sel = min(p.entries.size - 1, p.sel + 1); host.sfx(Sfx.Id.DOWN) }
                else -> move(1)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                d != null -> { d.sel = max(0, d.sel - 1); host.sfx(Sfx.Id.UP) }
                p != null -> {}
                sub != null -> back()
                else -> moveCategory(-1)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                d != null -> { d.sel = min(d.buttons.size - 1, d.sel + 1); host.sfx(Sfx.Id.DOWN) }
                p != null -> {}
                else -> moveCategory(1)
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> when {
                d != null -> d.onButton(d.sel)
                p != null -> popupPick()
                else -> confirm()
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> back()
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (d == null && p == null) { host.sfx(Sfx.Id.OK); host.onSelectButton() }
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_X -> {
                if (p != null) back() else options()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        invalidate()
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true
    }

    private inner class Gestures : GestureDetector.SimpleOnGestureListener() {
        private var accX = 0f
        private var accY = 0f
        private var axis = 0 // 0 undecided, 1 horizontal, 2 vertical

        override fun onDown(e: MotionEvent): Boolean {
            accX = 0f; accY = 0f; axis = 0
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (dialog != null) return true
            accX += dx; accY += dy
            if (axis == 0 && (abs(accX) > 12 * s || abs(accY) > 12 * s)) axis = if (abs(accX) > abs(accY)) 1 else 2
            val p = popup
            if (axis == 1 && p == null) {
                val step = 60 * s
                while (accX > step) { accX -= step; moveCategory(1) }
                while (accX < -step) {
                    accX += step
                    if (sub != null) { back(); accX = 0f } else moveCategory(-1)
                }
            } else if (axis == 2) {
                val step = (if (sub?.level?.gameList == true) 58 else 38) * s
                while (accY > step) {
                    accY -= step
                    if (p != null) { if (p.sel < p.entries.size - 1) { p.sel++; host.sfx(Sfx.Id.DOWN) } } else move(1)
                }
                while (accY < -step) {
                    accY += step
                    if (p != null) { if (p.sel > 0) { p.sel--; host.sfx(Sfx.Id.UP) } } else move(-1)
                }
            }
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x / s, e.y / s)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (popup == null && dialog == null) options()
        }
    }

    private fun tap(x: Float, y: Float) {
        dialog?.let { d ->
            d.hit.forEachIndexed { i, r -> if (r.contains(x, y)) { d.sel = i; d.onButton(i); return } }
            return
        }
        popup?.let { p ->
            if (x < vw - POPUP_W) { back(); return }
            val i = kotlin.math.floor((y - popupTop(p)) / POPUP_ROW + 0.5f).toInt()
            if (i in p.entries.indices) {
                p.sel = i
                popupPick()
            }
            return
        }
        val colX = colX()
        if (sub != null && x < SUB_LEFT - 4f) { back(); return }
        if (sub == null && abs(y - CAT_Y) < 30) {
            val i = ((x - colX) / CAT_SP + cat).roundToInt()
            if (i in cats.indices) {
                if (i == cat) return
                moveCategory(i - cat)
            }
            return
        }
        // Items: find the one under the finger.
        val list = items()
        val game = sub?.level?.gameList == true
        val sel = selectedIndex()
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in list.indices) {
            val iy = itemY(i - sel.toFloat(), game)
            val half = if (i == sel) (if (game) 42f else 28f) else (if (game) 28f else 20f)
            val d = abs(y - iy)
            if (d < half && d < bestD) { best = i; bestD = d }
        }
        if (best < 0) return
        if (best == sel) confirm() else {
            setSelected(best)
            host.sfx(if (best < sel) Sfx.Id.UP else Sfx.Id.DOWN)
            updateFocus()
            invalidate()
        }
    }

    // ---------------------------------------------------------------- layout

    private fun colX() = max(96f, vw * 0.233f)

    /** Vertical center of an item [rel] places from the selected one. */
    private fun itemY(rel: Float, game: Boolean): Float {
        val selY = SEL_Y
        val gapBelow = if (game) 72f else 50f
        val gapAbove = if (game) 74f else SEL_Y - CAT_Y + 44f
        val spacing = if (game) 58f else 42f
        return when {
            rel >= 1f -> selY + gapBelow + (rel - 1f) * spacing
            rel >= 0f -> selY + gapBelow * rel
            rel > -1f -> selY + gapAbove * rel
            else -> selY - gapAbove + (rel + 1f) * spacing
        }
    }

    private fun popupTop(p: Popup): Float {
        val full = (p.entries.size - 1) * POPUP_ROW
        return if (full <= SCREEN_H - 60f) SCREEN_H / 2 - full / 2 else SCREEN_H / 2 - p.scroll * POPUP_ROW
    }

    // ---------------------------------------------------------------- drawing

    private fun approach(v: Float, target: Float, dt: Float, speed: Float = 11f): Float {
        val n = v + (target - v) * (1f - exp(-dt * speed))
        return if (abs(target - n) < 0.002f) target else n
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else min(0.05f, (now - lastFrame) / 1e9f)
        lastFrame = now
        s = height / SCREEN_H
        vw = width / s

        // Animations.
        catAnim = approach(catAnim, cat.toFloat(), dt)
        cats.forEach { it.anim = approach(it.anim, it.sel.toFloat(), dt) }
        sub?.let { it.anim = approach(it.anim, it.sel.toFloat(), dt) }
        depth = approach(depth, if (sub != null) 1f else 0f, dt, 9f)
        if (sub == null && depth == 0f) subShown = null
        popupAnim = approach(popupAnim, if (popup != null) 1f else 0f, dt, 14f)
        popup?.let { it.scroll = approach(it.scroll, it.sel.toFloat(), dt, 14f) }
        dialogAnim = approach(dialogAnim, if (dialog != null) 1f else 0f, dt, 14f)
        picAlpha = approach(picAlpha, picTarget, dt, 3.5f)
        if (picAlpha == 0f && picTarget == 0f) { pic1 = null; pic0 = null }
        menuAlpha = approach(menuAlpha, menuTarget, dt, chromeSpeed)
        clockAlpha = approach(clockAlpha, clockTarget, dt, chromeSpeed)

        glowing = false
        fading = false
        canvas.save()
        canvas.scale(s, s)
        if (menuAlpha > 0f) {
            val layer = if (menuAlpha < 1f) canvas.saveLayerAlpha(0f, 0f, vw, SCREEN_H, (menuAlpha * 255).toInt()) else -1
            drawPics(canvas)
            drawMain(canvas)
            subShown?.let { drawSub(canvas, it) }
            if (layer >= 0) canvas.restoreToCount(layer)
        }
        if (clockAlpha > 0f) {
            val layer = if (clockAlpha < 1f) canvas.saveLayerAlpha(0f, 0f, vw, 40f, (clockAlpha * 255).toInt()) else -1
            drawClock(canvas)
            if (layer >= 0) canvas.restoreToCount(layer)
        }
        if (menuAlpha > 0f) drawOptionsHint(canvas)
        if (popupAnim > 0f) drawPopup(canvas)
        if (dialogAnim > 0f) drawDialog(canvas)
        canvas.restore()

        reportIconRect()
        // Keep animating while anything moves; the clock and waves don't need this view to redraw often.
        when {
            isAnimating() -> postInvalidateOnAnimation()
            glowing -> postInvalidateDelayed(GLOW_FRAME_MS) // the breathing halo is smooth at 30 fps
            else -> postInvalidateDelayed(1000) // the clock
        }
    }

    private fun isAnimating(): Boolean {
        if (fading) return true
        if (catAnim != cat.toFloat() || popupAnim != (if (popup != null) 1f else 0f)) return true
        if (dialogAnim != (if (dialog != null) 1f else 0f) || picAlpha != picTarget) return true
        if (menuAlpha != menuTarget || clockAlpha != clockTarget) return true
        if (popup?.let { it.scroll != it.sel.toFloat() } == true) return true
        if (depth != (if (sub != null) 1f else 0f)) return true
        if (cats.any { it.anim != it.sel.toFloat() }) return true
        return sub?.let { it.anim != it.sel.toFloat() } ?: false
    }

    private fun drawPics(c: Canvas) {
        if (picAlpha <= 0f) return
        val a = (picAlpha * 255).toInt()
        pic1?.let { b ->
            // Fill the whole screen, as the PSP does; nothing is cropped.
            bmpPaint.alpha = a
            c.drawBitmap(b, null, RectF(0f, 0f, vw, SCREEN_H), bmpPaint)
        }
        pic0?.let { b ->
            bmpPaint.alpha = a
            val x = max(SUB_LEFT + 148f, vw - b.width - 6f)
            c.drawBitmap(b, null, RectF(x, 46f, x + b.width, 46f + b.height), bmpPaint)
        }
        bmpPaint.alpha = 255
    }

    private fun drawMain(c: Canvas) {
        val colX = colX()
        // Opening a sub level slides the bar left until the category icon sits half off-screen.
        val parentX = if (subShown?.level?.gameList == true) 0f else PARENT_X
        val shift = -depth * (colX - parentX)
        val fade = 1f - depth

        // Item columns of the current (and, while sliding, the neighbouring) category,
        // drawn first so items scrolling up pass behind the category icons.
        for (i in cats.indices) {
            val rel = i - catAnim
            if (abs(rel) >= 1f) continue
            val catFade = 1f - abs(rel)
            val category = cats[i]
            val x = colX + rel * CAT_SP + shift
            for (j in category.items.indices) {
                val r = j - category.anim
                val y = itemY(r, false)
                if (y < -40 || y > SCREEN_H + 40) continue
                drawItem(c, category.items[j], x, y, r, catFade * fade)
            }
        }

        // Category row.
        for (i in cats.indices) {
            val rel = i - catAnim
            val x = colX + rel * CAT_SP + shift
            if (x < -60 || x > vw + 60) continue
            val f = (1f - abs(rel)).coerceIn(0f, 1f)
            val size = lerp(43f, 49f, f)
            val current = i == cat
            drawIcon(c, cats[i].icon, x, CAT_Y, size, lerp(0.8f, 1f, f) * (if (current) 1f else fade))
            if (f * fade > 0.01f) {
                text.textSize = 12f
                text.textAlign = Paint.Align.CENTER
                textAlpha((f * fade * 255).toInt())
                c.drawText(cats[i].title, x, CAT_Y + 31f, text)
            }
        }

        // The small marker the PSP leaves beside an open sub level.
        if (depth > 0.01f) {
            val a = (depth * 255).toInt()
            fill.color = Color.WHITE // full paint alpha, so the gradient alone sets the opacity
            fill.shader = LinearGradient(5.5f, 0f, 11f, 0f,
                intArrayOf(Color.argb(a, 255, 255, 255), Color.argb(a, 255, 255, 255), Color.argb(a / 5, 255, 255, 255)),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            arrow.reset()
            arrow.moveTo(11f, SEL_Y - 4.5f); arrow.lineTo(5.5f, SEL_Y); arrow.lineTo(11f, SEL_Y + 4.5f); arrow.close()
            c.drawPath(arrow, fill)
            fill.shader = null
        }
    }

    private val arrow = android.graphics.Path()

    private fun drawSub(c: Canvas, sub: Sub) {
        val list = sub.level.items()
        val slide = (1f - depth) * 50f
        val x = (if (sub.level.gameList) SUB_LEFT else SUB_ITEM_X) + slide
        val alpha = depth
        if (list.isEmpty()) {
            text.textSize = 13f
            text.textAlign = Paint.Align.LEFT
            textAlpha((alpha * 255).toInt())
            c.drawText(sub.level.emptyText(), SUB_ITEM_X - 10f + slide, SEL_Y + 5f, text)
            return
        }
        for (j in list.indices) {
            val r = j - sub.anim
            val y = itemY(r, sub.level.gameList)
            if (y < -60 || y > SCREEN_H + 60) continue
            if (sub.level.gameList) drawGame(c, list[j], x, y, r, alpha) else drawItem(c, list[j], x, y, r, alpha)
        }
    }

    private fun drawItem(c: Canvas, item: XItem, x: Float, y: Float, rel: Float, alpha: Float) {
        val f = (1f - abs(rel)).coerceIn(0f, 1f)
        val size = lerp(29f, 34f, f)
        item.icon?.let {
            if (f > 0.5f) drawIconGlow(c, it, x, y, size, alpha * (f - 0.5f) * 2f)
            drawIcon(c, it, x, y, size, alpha * lerp(0.8f, 1f, f))
        }
        val ta = alpha * lerp(0.82f, 1f, f)
        if (ta <= 0.01f) return
        text.textAlign = Paint.Align.LEFT
        text.textSize = lerp(14f, 16f, f)
        textAlpha((ta * 255).toInt())
        val tx = x + 32f
        val subtitle = if (f > 0.5f) item.subtitle?.invoke() else null
        if (subtitle == null) {
            c.drawText(item.title, tx, y + 5.5f, text)
        } else {
            // PSP detail block: title, a hairline out to the edge of the screen, then the detail.
            c.drawText(item.title, tx, y - 5f, text)
            fill.color = Color.WHITE
            fill.alpha = (ta * f * 210).toInt()
            c.drawRect(tx, y - 1.6f, vw - 12f, y - 0.9f, fill)
            text.textSize = 12.5f
            textAlpha((ta * f * 255).toInt())
            c.drawText(ellipsize(subtitle, vw - tx - 20f), tx, y + 13f, text)
        }
        item.value?.invoke()?.let { v ->
            text.textSize = 12f
            textAlpha((ta * 220).toInt())
            text.textAlign = Paint.Align.RIGHT
            val right = min(vw - 24f, tx + 300f)
            val vy = if (subtitle != null) y - 5f else y + 5f // on the title line, clear of the detail
            c.drawText(v, right, vy, text)
            item.swatch?.invoke()?.let { drawSwatch(c, it, right - text.measureText(v) - 15f, vy - 4.5f, ta) }
        }
    }

    private fun drawGame(c: Canvas, item: XItem, x: Float, y: Float, rel: Float, alpha: Float) {
        val f = (1f - abs(rel)).coerceIn(0f, 1f)
        val w = lerp(96f, 144f, f)
        val h = w * 80f / 144f
        val cx = x + 72f // unselected icons line up on the centre of the selected box
        val rect = RectF(cx - w / 2, y - h / 2, cx + w / 2, y + h / 2)
        val bmp = item.game?.let { gameIcon(it) }
        if (f > 0.5f) drawRectGlow(c, rect, alpha * (f - 0.5f) * 2f)
        bmpPaint.alpha = (alpha * 255).toInt()
        if (bmp != null) {
            c.drawBitmap(bmp, null, rect, bmpPaint)
        } else {
            // No ICON0: the PSP shows a plain icon for the content type.
            item.icon?.let { drawIcon(c, it, rect.centerX(), rect.centerY(), h * 0.9f, alpha) }
        }
        bmpPaint.alpha = 255
        // Only the hovered entry is named, a second after the cursor stops on it.
        if (item.game == null || item.game !== focusedGame) return
        val since = (System.nanoTime() - focusSince) / 1e9f
        var ta = alpha * ((since - TITLE_DELAY) / 0.35f).coerceIn(0f, 1f)
        if (since < TITLE_DELAY + 0.4f) fading = true // keep redrawing until the fade is done
        // PIC0 carries the game's own logo and blurb; the title steps aside while it is up.
        if (pic0 != null) ta *= 1f - picAlpha
        if (ta <= 0.01f) return
        text.textAlign = Paint.Align.LEFT
        text.textSize = 16f
        textAlpha((ta * 255).toInt())
        val tx = rect.right + 14f
        val save = item.game.save
        if (save == null || save.saveTitle.isEmpty()) {
            c.drawText(ellipsize(item.title, vw - tx - 16f), tx, y + 5.5f, text)
        } else {
            c.drawText(ellipsize(item.title, vw - tx - 16f), tx, y - 4f, text)
            text.textSize = 12.5f
            c.drawText(ellipsize(save.saveTitle, vw - tx - 16f), tx, y + 14f, text)
        }
    }

    private fun drawIcon(c: Canvas, src: Bitmap, cx: Float, cy: Float, size: Float, alpha: Float) {
        if (alpha <= 0f) return
        val b = metallic(src)
        val scale = size / max(b.width, b.height)
        val w = b.width * scale
        val h = b.height * scale
        bmpPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        c.drawBitmap(b, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), bmpPaint)
        bmpPaint.alpha = 255
    }

    // XMB icons have a brushed-metal finish: bright at the top, shading to a cool silver lower down.
    private val metalCache = HashMap<Bitmap, Bitmap>()

    private fun metallic(src: Bitmap): Bitmap = metalCache.getOrPut(src) {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val cv = Canvas(out)
        val h = out.height.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, h * 0.08f, out.width * 0.25f, h * 0.92f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFF1F3F8.toInt(), 0xFFD3D8E4.toInt(), 0xFFAFB6C9.toInt()),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        // Multiply keeps the icon's own alpha and drop shadow and only shades the white face.
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY)
        cv.drawRect(0f, 0f, out.width.toFloat(), h, p)
        out
    }

    // The selected item's soft halo, gently breathing.
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        maskFilter = android.graphics.BlurMaskFilter(7f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val iconGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
        maskFilter = android.graphics.BlurMaskFilter(7f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val alphaCache = HashMap<Bitmap, Bitmap>()
    private val glowBitmaps = HashMap<Any, Bitmap>()
    private val glowDraw = Paint(Paint.FILTER_BITMAP_FLAG)
    private var glowScale = 0f
    private var glowing = false // only the halo is breathing: a lower frame rate is enough
    private var fading = false // a title is fading in

    /**
     * A halo blurred once, at the current screen scale, then drawn as a plain bitmap each frame:
     * blurring on every frame of the breathing animation costs far more GPU time and battery.
     * [w] x [h] is the shape's size in PSP pixels; the bitmap has a [GLOW_PAD] margin on every side.
     */
    private fun glowBitmap(key: Any, w: Float, h: Float, shape: (Canvas, Paint) -> Unit): Bitmap {
        if (glowScale != s) { glowBitmaps.values.forEach { it.recycle() }; glowBitmaps.clear(); glowScale = s }
        return glowBitmaps.getOrPut(key) {
            val b = Bitmap.createBitmap(((w + 2 * GLOW_PAD) * s).toInt().coerceAtLeast(1), ((h + 2 * GLOW_PAD) * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            cv.scale(s, s)
            cv.translate(GLOW_PAD, GLOW_PAD)
            shape(cv, Paint(glowPaint).apply { setAlpha(255) })
            b
        }
    }

    private fun glowStrength(): Float {
        val t = (System.nanoTime() % GLOW_PERIOD_NS) / 1e9f
        return 0.62f + 0.38f * kotlin.math.sin(t * 2f * Math.PI.toFloat() / (GLOW_PERIOD_NS / 1e9f))
    }

    private fun drawRectGlow(c: Canvas, r: RectF, alpha: Float) {
        if (alpha <= 0.01f) return
        // Drawn for the full-size selected box and scaled with it while it grows.
        val b = glowBitmap(RECT_GLOW, 151f, 87f) { cv, p -> cv.drawRoundRect(RectF(0f, 0f, 151f, 87f), 3f, 3f, p) }
        val k = r.width() / 144f
        val pad = (3.5f + GLOW_PAD) * k
        glowDraw.alpha = (alpha * glowStrength() * 255).toInt()
        c.drawBitmap(b, null, RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad), glowDraw)
        glowing = true
    }

    private fun drawIconGlow(c: Canvas, src: Bitmap, cx: Float, cy: Float, size: Float, alpha: Float) {
        if (alpha <= 0.01f) return
        // Drawn for the selected icon size (34) and scaled with the icon while it grows.
        val full = 34f / max(src.width, src.height) * 1.08f
        val fw = src.width * full
        val fh = src.height * full
        val b = glowBitmap(src, fw, fh) { cv, _ ->
            val mask = alphaCache.getOrPut(src) { src.extractAlpha() }
            val p = Paint(iconGlowPaint).apply { setAlpha(255) }
            val r = RectF(0f, 0f, fw, fh)
            cv.drawBitmap(mask, null, r, p)
            cv.drawBitmap(mask, null, r, p) // twice: a solid halo, not a faint haze
        }
        val k = size / 34f
        val w = (fw + 2 * GLOW_PAD) * k
        val h = (fh + 2 * GLOW_PAD) * k
        glowDraw.alpha = (alpha * glowStrength() * 255).toInt()
        c.drawBitmap(b, null, RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), glowDraw)
        glowing = true
    }

    /** A small rounded colour square with a thin light rim, centred on ([cx], [cy]). */
    private fun drawSwatch(c: Canvas, color: Int, cx: Float, cy: Float, alpha: Float) {
        val r = RectF(cx - 5f, cy - 5f, cx + 5f, cy + 5f)
        fill.color = color
        fill.alpha = (alpha * 255).toInt()
        c.drawRoundRect(r, 1.5f, 1.5f, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 0.8f
        fill.color = Color.WHITE
        fill.alpha = (alpha * 200).toInt()
        c.drawRoundRect(r, 1.5f, 1.5f, fill)
        fill.style = Paint.Style.FILL
    }

    /** The PSP's "△ Options" hint, shown while the selected entry has an options menu. */
    private fun drawOptionsHint(c: Canvas) {
        val item = items().getOrNull(selectedIndex()) ?: return
        val has = item.game != null || item.choices != null
        val a = (if (has && popup == null && dialog == null) 1f else 0f) * menuAlpha
        if (a <= 0f) return
        text.textSize = 11f
        text.textAlign = Paint.Align.LEFT
        val label = "\u25B3 Options"
        val tw = text.measureText(label)
        val r = RectF(vw - tw - 26f, SCREEN_H - 30f, vw - 10f, SCREEN_H - 12f)
        fill.color = Color.BLACK
        fill.alpha = (a * 0x99).toInt()
        c.drawRoundRect(r, 4f, 4f, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 0.7f
        fill.color = Color.WHITE
        fill.alpha = (a * 90).toInt()
        c.drawRoundRect(r, 4f, 4f, fill)
        fill.style = Paint.Style.FILL
        textAlpha((a * 255).toInt())
        c.drawText(label, r.left + 8f, r.bottom - 5f, text)
    }

    private fun drawClock(c: Canvas) {
        val now = Date()
        val str = (if (clock24) clockFmt24 else clockFmt12).format(now)
        text.textSize = 12f
        text.textAlign = Paint.Align.RIGHT
        textAlpha(255)
        val right = vw - 40f
        c.drawText(str, right, 22f, text)
        // Battery, PSP style: outlined cell with up to three bars.
        val level = batteryLevel()
        val bx = right + 8f
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 1.4f
        fill.color = Color.WHITE
        fill.setShadowLayer(2f, 0f, 0.5f, 0x88000000.toInt())
        c.drawRoundRect(RectF(bx, 13f, bx + 22f, 24f), 1.5f, 1.5f, fill)
        fill.style = Paint.Style.FILL
        c.drawRect(bx + 22f, 16f, bx + 24f, 21f, fill)
        val bars = when { level > 66 -> 3; level > 33 -> 2; level > 10 -> 1; else -> 0 }
        for (i in 0 until bars) c.drawRect(bx + 2.5f + i * 6.5f, 15.5f, bx + 7.5f + i * 6.5f, 21.5f, fill)
        fill.clearShadowLayer()
    }

    private var batteryCache = 100
    private var batteryReadAt = 0L

    private fun batteryLevel(): Int {
        val t = System.currentTimeMillis()
        if (t - batteryReadAt > 30_000) {
            batteryReadAt = t
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
                val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0) batteryCache = l * 100 / max(1, sc)
            }
        }
        return batteryCache
    }

    private fun drawPopup(c: Canvas) {
        val p = popup
        val a = popupAnim
        val left = vw - POPUP_W * a
        fill.shader = LinearGradient(left, 0f, left + 40f, 0f, 0x00000000, 0xB0000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(left, 0f, vw, SCREEN_H, fill)
        fill.shader = null
        if (p == null) return
        val top = popupTop(p)
        for (i in p.entries.indices) {
            val y = top + i * POPUP_ROW
            if (y < 6f || y > SCREEN_H - 2f) continue
            if (i == p.sel) {
                fill.color = 0x30FFFFFF
                c.drawRect(left + 14f, y - 11f, vw, y + 11f, fill)
            }
            text.textAlign = Paint.Align.LEFT
            text.textSize = 13f
            val rowAlpha = (if (i == p.sel) 1f else 0.7f) * a
            var tx = left + 34f
            p.swatches?.getOrNull(i)?.let { drawSwatch(c, it, tx + 5f, y + 0.5f, rowAlpha); tx += 16f }
            textAlpha((rowAlpha * 255).toInt())
            c.drawText(ellipsize(p.entries[i], vw - tx - 16f), tx, y + 5f, text)
            if (i == p.checked) {
                fill.color = Color.WHITE
                fill.alpha = (a * 255).toInt()
                c.drawCircle(left + 24f, y, 2.5f, fill)
            }
        }
    }

    private fun drawDialog(c: Canvas) {
        val d = dialog
        val a = dialogAnim
        fill.color = Color.BLACK
        fill.alpha = (a * 150).toInt()
        c.drawRect(0f, 0f, vw, SCREEN_H, fill)
        fill.color = Color.WHITE
        fill.alpha = (a * 200).toInt()
        c.drawRect(0f, 60f, vw, 61f, fill)
        c.drawRect(0f, 211f, vw, 212f, fill)
        if (d == null) return
        text.textAlign = Paint.Align.CENTER
        text.textSize = 13f
        textAlpha((a * 255).toInt())
        val lines = wrap(d.message, min(vw - 80f, 420f))
        val top = 128f - (lines.size - 1) * 9f - (if (d.buttons.isEmpty()) 0f else 12f)
        lines.forEachIndexed { i, l -> c.drawText(l, vw / 2, top + i * 18f, text) }
        d.hit.clear()
        val bw = 90f
        val total = d.buttons.size * bw + (d.buttons.size - 1) * 16f
        var bx = vw / 2 - total / 2
        for ((i, label) in d.buttons.withIndex()) {
            val r = RectF(bx, 168f, bx + bw, 192f)
            d.hit += r
            fill.color = if (i == d.sel) 0x55FFFFFF else 0x22FFFFFF
            c.drawRoundRect(r, 4f, 4f, fill)
            textAlpha(((if (i == d.sel) 1f else 0.75f) * a * 255).toInt())
            c.drawText(label, r.centerX(), r.centerY() + 4.5f, text)
            bx += bw + 16f
        }
    }

    /** Tells the host where the selected game's ICON0 sits, so the ICON1 video can cover it. */
    private fun reportIconRect() {
        val sb = sub
        var rect: RectF? = null
        if (sb != null && sb.level.gameList && depth == 1f && sb.anim == sb.sel.toFloat() && popup == null && dialog == null && menuAlpha == 1f) {
            rect = RectF(SUB_LEFT * s, (SEL_Y - 40f) * s, (SUB_LEFT + 144f) * s, (SEL_Y + 40f) * s)
        }
        if (rect != lastIconRect) {
            lastIconRect = rect
            host.onIconRect(rect)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun gameIcon(g: Game): Bitmap? {
        if (iconCache.containsKey(g.id)) return iconCache[g.id]
        iconCache[g.id] = null
        decoder.execute {
            val b = g.icon0?.let { BitmapFactory.decodeFile(it.path) }
            post {
                iconCache[g.id] = b
                invalidate()
            }
        }
        return null
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 1 && text.measureText(s, 0, end) + text.measureText("…") > maxW) end--
        return s.substring(0, end) + "…"
    }

    private fun wrap(msg: String, maxW: Float): List<String> {
        val out = ArrayList<String>()
        for (para in msg.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val t = if (line.isEmpty()) word else "$line $word"
                if (text.measureText(t) > maxW && line.isNotEmpty()) { out += line; line = word } else line = t
            }
            out += line
        }
        return out
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Sets text opacity; the drop shadow fades with it so faded labels leave no ghost behind. */
    private fun textAlpha(a: Int) {
        val c = a.coerceIn(0, 255)
        text.alpha = c
        text.setShadowLayer(2.5f, 0f, 0.8f, Color.argb(c * 0x99 / 255, 0, 0, 0))
    }

    companion object {
        const val SCREEN_H = 272f
        // Measured from a real PSP screen (480x272).
        const val CAT_Y = 72f
        const val SEL_Y = 136f
        const val CAT_SP = 86f
        const val PARENT_X = 14f // where the category icon sits while a sub level is open
        const val SUB_LEFT = 24f // left edge of a sub level's game icons
        const val SUB_ITEM_X = 50f // centre of a sub level's item icons
        const val POPUP_W = 170f
        const val POPUP_ROW = 26f
        const val TITLE_DELAY = 1f // seconds on a game before its title fades in
        const val GLOW_PERIOD_NS = 1_300_000_000L
        const val GLOW_FRAME_MS = 33L
        const val GLOW_PAD = 21f // three blur radii: room for the halo to fade out
        private val RECT_GLOW = Any()
    }
}
