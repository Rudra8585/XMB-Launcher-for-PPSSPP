package com.rudra.xmb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * The XMB's font, icons, sounds and branding text. The app ships its own original defaults (Material
 * Symbols icons, the M PLUS 1p font and synthesized sounds); a user can import their own copies from
 * a folder once, which are copied into private storage and take precedence piece by piece.
 */
class AssetPack(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "pack")

    val installed: Boolean get() = File(dir, MARKER).isFile

    fun font(): Typeface = File(dir, FONT).takeIf { it.isFile }?.let {
        try { Typeface.createFromFile(it) } catch (e: Exception) { null }
    } ?: try { ctx.resources.getFont(R.font.xmb_default) } catch (e: Exception) { Typeface.DEFAULT }

    fun icon(name: String): Bitmap =
        File(dir, "icons/$name.png").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } ?: defaultIcon(name)

    /** The imported sound file, if any; otherwise see [defaultSound]. */
    fun sound(name: String): File? = AUDIO.map { File(dir, "sfx/$name.$it") }.firstOrNull { it.isFile }

    /** The built-in sound's raw resource, for names that have one (all but background music). */
    fun defaultSound(name: String): Int? = DEFAULT_SOUNDS[name]

    /** Optional text: "boot" is shown between the lines at power-on, "gameboot" under the game boot logo. */
    fun text(name: String): String? = File(dir, "$name.txt").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    /** "Font, 23 icons, 9 sounds", or "Built-in" when nothing is imported. */
    fun summary(): String {
        if (!installed) return "Built-in"
        val icons = File(dir, "icons").list()?.size ?: 0
        val sounds = File(dir, "sfx").list()?.size ?: 0
        val parts = listOfNotNull(
            if (File(dir, FONT).isFile) "Font" else null,
            "$icons icons",
            "$sounds sounds",
        )
        return parts.joinToString(", ")
    }

    class Result(val font: Boolean, val icons: Int, val sounds: Int, val texts: Int) {
        val empty get() = !font && icons == 0 && sounds == 0 && texts == 0
    }

    /**
     * Copies the recognised files under [tree] (subfolders included) over the current pack.
     * Files are recognised by name; see [ICONS] and [SOUNDS]. Runs on the caller's thread.
     */
    fun import(tree: Uri): Result {
        val staging = File(ctx.filesDir, "pack.new").apply { deleteRecursively(); mkdirs() }
        var font: Doc? = null
        val icons = HashMap<String, Doc>()
        val sounds = HashMap<String, Doc>()
        val texts = HashMap<String, Doc>()
        walk(tree, DocumentsContract.getTreeDocumentId(tree), 0) { doc ->
            val lower = doc.name.lowercase()
            val base = lower.substringBeforeLast('.')
            val ext = lower.substringAfterLast('.', "")
            when {
                ext == "ttf" || ext == "otf" ->
                    // Prefer the XMB's own NewRodin when several fonts are present.
                    if (font == null || ("rodin" in base && "rodin" !in font!!.name.lowercase())) font = doc
                ext == "png" && base in ICONS -> icons[base] = doc
                ext in AUDIO && base in SOUNDS -> sounds.putIfAbsent(base, doc)
                ext == "txt" && base in TEXTS -> texts[base] = doc
            }
        }
        fun copy(doc: Doc, to: File) {
            to.parentFile?.mkdirs()
            ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, doc.id))!!.use { input ->
                to.outputStream().use { input.copyTo(it) }
            }
        }
        font?.let { copy(it, File(staging, FONT)) }
        icons.forEach { (name, doc) -> copy(doc, File(staging, "icons/$name.png")) }
        sounds.forEach { (name, doc) -> copy(doc, File(staging, "sfx/$name.${doc.name.substringAfterLast('.').lowercase()}")) }
        texts.forEach { (name, doc) -> copy(doc, File(staging, "$name.txt")) }
        val result = Result(font != null, icons.size, sounds.size, texts.size)
        if (result.empty) {
            staging.deleteRecursively()
            return result
        }
        File(staging, MARKER).writeText("1")
        dir.deleteRecursively()
        if (!staging.renameTo(dir)) Log.w("AssetPack", "Could not move the imported pack into place")
        return result
    }

    private class Doc(val id: String, val name: String)

    private fun walk(tree: Uri, docId: String, depth: Int, onFile: (Doc) -> Unit) {
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 3 && !name.startsWith(".")) walk(tree, c.getString(0), depth + 1, onFile)
                } else onFile(Doc(c.getString(0), name))
            }
        }
    }

    /**
     * The built-in icon for [name]: a white Material Symbol over a soft drop shadow, like the XMB's
     * own icons; a plain outlined square for anything without one.
     */
    private fun defaultIcon(name: String): Bitmap {
        val size = 128
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val res = DEFAULT_ICONS[name]
        val drawable = res?.let { ctx.getDrawable(it)?.mutate() }
        if (drawable == null) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f; color = Color.WHITE }
            c.drawRoundRect(RectF(20f, 20f, size - 20f, size - 20f), 18f, 18f, p)
            return b
        }
        val inset = 10
        drawable.setBounds(inset, inset, size - inset, size - inset)
        // Shadow: the same shape in translucent black, a little lower, softened.
        val shadow = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setTint(Color.BLACK)
        drawable.draw(Canvas(shadow))
        val blur = Paint().apply {
            alpha = 110
            maskFilter = android.graphics.BlurMaskFilter(5f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        c.drawBitmap(shadow.extractAlpha(), 0f, 4f, blur)
        shadow.recycle()
        drawable.setTint(Color.WHITE)
        drawable.draw(c)
        return b
    }

    companion object {
        private const val MARKER = ".imported"
        private const val FONT = "font.ttf"
        private val AUDIO = listOf("ogg", "flac", "wav", "mp3")

        /** Icon file names (.png) the menu uses. */
        val ICONS = setOf(
            "browser", "cat_game", "cat_music", "cat_network", "cat_photo", "cat_settings", "cat_video",
            "date_time", "folder_music", "folder_photo", "game_sharing", "home", "memstick", "memstick_fill",
            "network_update", "psp", "remote_play", "rss", "sound", "system_settings", "theme",
            "theme_wallpaper", "umd",
        )

        /** Sound file names: cursor (also confirm and back), option menus, game boot, power-on and background music. */
        val SOUNDS = setOf("up", "down", "notice", "notice_back", "launch", "boot", "bgm")

        private val TEXTS = setOf("boot", "gameboot")

        private val DEFAULT_ICONS = mapOf(
            "browser" to R.drawable.xmb_browser, "cat_game" to R.drawable.xmb_cat_game,
            "cat_music" to R.drawable.xmb_cat_music, "cat_network" to R.drawable.xmb_cat_network,
            "cat_photo" to R.drawable.xmb_cat_photo, "cat_settings" to R.drawable.xmb_cat_settings,
            "cat_video" to R.drawable.xmb_cat_video, "date_time" to R.drawable.xmb_date_time,
            "folder_music" to R.drawable.xmb_folder_music, "folder_photo" to R.drawable.xmb_folder_photo,
            "game_sharing" to R.drawable.xmb_game_sharing, "home" to R.drawable.xmb_home,
            "memstick" to R.drawable.xmb_memstick, "memstick_fill" to R.drawable.xmb_memstick_fill,
            "network_update" to R.drawable.xmb_network_update, "psp" to R.drawable.xmb_psp,
            "remote_play" to R.drawable.xmb_remote_play, "rss" to R.drawable.xmb_rss,
            "sound" to R.drawable.xmb_sound, "system_settings" to R.drawable.xmb_system_settings,
            "theme" to R.drawable.xmb_theme, "theme_wallpaper" to R.drawable.xmb_theme_wallpaper,
            "umd" to R.drawable.xmb_umd,
        )

        private val DEFAULT_SOUNDS = mapOf(
            "up" to R.raw.xmb_up, "down" to R.raw.xmb_down, "notice" to R.raw.xmb_notice,
            "notice_back" to R.raw.xmb_notice_back, "boot" to R.raw.xmb_boot, "launch" to R.raw.xmb_launch,
        )
    }
}
