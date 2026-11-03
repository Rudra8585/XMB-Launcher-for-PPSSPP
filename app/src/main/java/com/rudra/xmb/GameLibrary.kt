package com.rudra.xmb

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import com.rudra.xmb.psp.ChannelSource
import com.rudra.xmb.psp.GameImage
import com.rudra.xmb.psp.ParamSfo
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

class Game(
    val uri: Uri,
    val fileName: String,
    val size: Long,
    val title: String,
    val discId: String,
    val version: String,
    private val dir: File,
    /** Set for an entry of the Saved Data Utility rather than a playable game. */
    val save: SaveInfo? = null,
) {
    val id: String get() = dir.name
    fun asset(name: String): File? = File(dir, name).takeIf { it.isFile && it.length() > 0 }
    val icon0 get() = asset("ICON0.PNG")
    val icon1 get() = asset("ICON1.PMF")
    val pic0 get() = asset("PIC0.PNG")
    val pic1 get() = asset("PIC1.PNG")
    val snd0 get() = asset("SND0.AT3")
}

/** A PPSSPP save folder (PSP/SAVEDATA/<id>), as the Saved Data Utility lists it. */
class SaveInfo(val folder: Uri, val name: String, val saveTitle: String, val detail: String, val modified: Long)

/** Finds PSP games in the user's folder and caches their XMB assets on disk. */
class GameLibrary(private val ctx: Context) {
    enum class State { NO_FOLDER, SCANNING, READY }

    var state = State.NO_FOLDER
        private set
    var games: List<Game> = emptyList()
        private set
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cacheRoot = File(ctx.filesDir, "games").apply { mkdirs() }
    private var generation = 0

    fun scan(trees: List<Uri>) {
        val gen = ++generation
        if (trees.isEmpty()) {
            state = State.NO_FOLDER; games = emptyList(); listener?.invoke(); return
        }
        state = State.SCANNING
        listener?.invoke()
        io.execute {
            val found = ArrayList<Game>()
            val seen = HashSet<String>() // overlapping folders list a game once
            for (tree in trees) {
                try {
                    val rootDoc = DocumentsContract.getTreeDocumentId(tree)
                    walk(tree, rootDoc, 0) { uri, name, size, modified ->
                        if (!seen.add("$name|$size|$modified")) return@walk
                        load(uri, name, size, modified)?.let { found += it }
                        // Show games as they appear on big folders.
                        if (found.size % 8 == 0) post(gen, found.sortedBy { it.title.lowercase() }, State.SCANNING)
                    }
                } catch (e: Exception) {
                    Log.w("GameLibrary", "Scan failed for $tree", e)
                }
            }
            post(gen, found.sortedBy { it.title.lowercase() }, State.READY)
        }
    }

    private fun post(gen: Int, list: List<Game>, s: State) = main.post {
        if (gen != generation) return@post
        games = list; state = s; listener?.invoke()
    }

    private fun walk(tree: Uri, docId: String, depth: Int, onFile: (Uri, String, Long, Long) -> Unit) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 4 && !name.startsWith(".")) walk(tree, id, depth + 1, onFile)
                    continue
                }
                val lower = name.lowercase()
                if (lower.endsWith(".iso") || lower.endsWith(".cso") || lower == "eboot.pbp") {
                    onFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, c.getLong(3), c.getLong(4))
                }
            }
        }
    }

    private fun load(uri: Uri, name: String, size: Long, modified: Long): Game? {
        val key = sha1("$uri|$size|$modified")
        val dir = File(cacheRoot, key)
        val meta = File(dir, "meta.json")
        if (!meta.isFile) {
            try {
                val assets = ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                    ChannelSource(FileInputStream(pfd.fileDescriptor)).use { GameImage.extract(it, name) }
                }
                dir.mkdirs()
                fun save(n: String, b: ByteArray?) { if (b != null) File(dir, n).writeBytes(b) }
                save("ICON0.PNG", assets.icon0)
                save("ICON1.PMF", assets.icon1)
                save("PIC0.PNG", assets.pic0)
                save("PIC1.PNG", assets.pic1)
                save("SND0.AT3", assets.snd0)
                val json = JSONObject()
                assets.sfo.forEach { (k, v) -> json.put(k, v) }
                meta.writeText(json.toString())
            } catch (e: Exception) {
                Log.w("GameLibrary", "Could not read $name", e)
                if (name.lowercase().endsWith(".pbp")) return null
                dir.mkdirs()
                meta.writeText("{}") // Still list it, by file name.
            }
        }
        val json = try { JSONObject(meta.readText()) } catch (e: Exception) { JSONObject() }
        val fallback = if (name.equals("EBOOT.PBP", true)) "Homebrew" else name.substringBeforeLast('.')
        return Game(
            uri = uri,
            fileName = name,
            size = size,
            title = json.optString("TITLE").ifBlank { fallback },
            discId = json.optString("DISC_ID"),
            version = json.optString("DISC_VERSION").ifBlank { json.optString("APP_VER") },
            dir = dir,
        )
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
}

/**
 * Reads PPSSPP's save data (PSP/SAVEDATA, never its save states) so the Saved Data Utility can show
 * each save's ICON0, PIC1, SND0 and ICON1 the way the PSP does.
 */
class SaveLibrary(private val ctx: Context) {
    var state = GameLibrary.State.NO_FOLDER
        private set
    var saves: List<Game> = emptyList()
        private set
    var listener: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cacheRoot = File(ctx.filesDir, "saves").apply { mkdirs() }
    private var generation = 0

    fun scan(tree: Uri?) {
        val gen = ++generation
        if (tree == null) {
            state = GameLibrary.State.NO_FOLDER; saves = emptyList(); listener?.invoke(); return
        }
        state = GameLibrary.State.SCANNING
        listener?.invoke()
        io.execute {
            val found = ArrayList<Game>()
            try {
                var dirId = DocumentsContract.getTreeDocumentId(tree)
                // The user may pick PSP or the memory stick root instead of SAVEDATA itself.
                for (step in listOf("PSP", "SAVEDATA")) {
                    children(tree, dirId).firstOrNull { it.dir && it.name.equals(step, true) }?.let { dirId = it.id }
                }
                for (d in children(tree, dirId).filter { it.dir }) load(tree, d)?.let { found += it }
            } catch (e: Exception) {
                Log.w("SaveLibrary", "Scan failed", e)
            }
            // Newest first, like the PSP.
            val sorted = found.sortedByDescending { it.save!!.modified }
            main.post {
                if (gen != generation) return@post
                saves = sorted; state = GameLibrary.State.READY; listener?.invoke()
            }
        }
    }

    /** Removes [save] from the list after its folder was deleted. */
    fun forget(save: Game) {
        saves = saves.filter { it !== save }
        listener?.invoke()
    }

    private class Doc(val id: String, val name: String, val dir: Boolean, val size: Long, val modified: Long)

    private fun children(tree: Uri, docId: String): List<Doc> {
        val out = ArrayList<Doc>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += Doc(c.getString(0), name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    private fun load(tree: Uri, folder: Doc): Game? {
        val files = children(tree, folder.id).filter { !it.dir }
        val sfoDoc = files.firstOrNull { it.name.equals("PARAM.SFO", true) } ?: return null
        val modified = files.maxOf { it.modified }
        val dir = File(cacheRoot, sha1("${folder.id}|$modified|${files.sumOf { it.size }}"))
        val meta = File(dir, "PARAM.SFO")
        fun read(d: Doc) = ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, d.id))!!.use { it.readBytes() }
        if (!meta.isFile) {
            try {
                dir.mkdirs()
                for (name in listOf("ICON0.PNG", "ICON1.PMF", "PIC1.PNG", "SND0.AT3")) {
                    files.firstOrNull { it.name.equals(name, true) && it.size in 1..MAX_ASSET }?.let { File(dir, name).writeBytes(read(it)) }
                }
                meta.writeBytes(read(sfoDoc))
            } catch (e: Exception) {
                Log.w("SaveLibrary", "Could not read ${folder.name}", e)
                return null
            }
        }
        val sfo = ParamSfo.parse(meta.readBytes())
        fun str(k: String) = (sfo[k] as? String).orEmpty().trim()
        val title = str("TITLE").ifBlank { folder.name }
        return Game(
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, folder.id),
            fileName = folder.name,
            size = files.sumOf { it.size },
            title = title,
            discId = folder.name,
            version = "",
            dir = dir,
            save = SaveInfo(
                folder = DocumentsContract.buildDocumentUriUsingTree(tree, folder.id),
                name = folder.name,
                saveTitle = str("SAVEDATA_TITLE"),
                detail = str("SAVEDATA_DETAIL"),
                modified = modified,
            ),
        )
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    companion object {
        private const val MAX_ASSET = 8L * 1024 * 1024
    }
}
