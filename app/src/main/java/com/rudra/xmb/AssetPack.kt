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
 * The XMB's font, icons, sounds and branding text. None of it ships with the app: the user picks a
 * folder holding their own copies once, and the recognised files are copied into private storage.
 * Anything missing falls back to a plain stand-in (system font, outlined placeholder icon, silence).
 */
class AssetPack(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "pack")

    val installed: Boolean get() = File(dir, MARKER).isFile

    fun font(): Typeface = File(dir, FONT).takeIf { it.isFile }?.let {
        try { Typeface.createFromFile(it) } catch (e: Exception) { null }
    } ?: Typeface.DEFAULT

    fun icon(name: String): Bitmap =
        File(dir, "icons/$name.png").takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } ?: placeholder()

    fun sound(name: String): File? = AUDIO.map { File(dir, "sfx/$name.$it") }.firstOrNull { it.isFile }

    /** Optional text: "boot" is shown between the lines at power-on, "gameboot" under the game boot logo. */
    fun text(name: String): String? = File(dir, "$name.txt").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }

    /** "Font, 23 icons, 9 sounds", or "Not imported". */
    fun summary(): String {
        if (!installed) return "Not imported"
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

    /** A plain outlined square, so the menu still reads without icons. */
    private fun placeholder(): Bitmap {
        val size = 96
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            color = Color.WHITE
        }
        Canvas(b).drawRoundRect(RectF(14f, 14f, size - 14f, size - 14f), 14f, 14f, p)
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
    }
}
