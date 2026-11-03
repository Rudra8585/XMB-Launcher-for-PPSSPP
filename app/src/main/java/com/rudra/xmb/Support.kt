package com.rudra.xmb

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import java.util.Calendar

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("xmb", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun setBool(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    /** Folders scanned for games. The first version kept a single folder under "folder". */
    var gameFolders: List<Uri>
        get() = sp.getString("folders", null)?.split('\n')?.filter { it.isNotEmpty() }?.map(Uri::parse)
            ?: listOfNotNull(sp.getString("folder", null)?.let(Uri::parse))
        set(v) = sp.edit().putString("folders", v.joinToString("\n")).remove("folder").apply()
    var saveFolder: Uri?
        get() = sp.getString("saveFolder", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("saveFolder", v?.toString()).apply()
    /** "package/activity" of the home app Settings › Open Home App starts. */
    var otherHome: String?
        get() = sp.getString("otherHome", null)
        set(v) = sp.edit().putString("otherHome", v).apply()
    var theme: String
        get() = sp.getString("theme", "original")!!
        set(v) = sp.edit().putString("theme", v).apply()
    var emulator: String
        get() = sp.getString("emulator", "auto")!!
        set(v) = sp.edit().putString("emulator", v).apply()
    var sfx: Boolean
        get() = bool("sfx", true)
        set(v) = setBool("sfx", v)
    var gameMusic: Boolean
        get() = bool("gameMusic", true)
        set(v) = setBool("gameMusic", v)
    var iconVideo: Boolean
        get() = bool("iconVideo", true)
        set(v) = setBool("iconVideo", v)
    var bgm: Boolean
        get() = bool("bgm", false)
        set(v) = setBool("bgm", v)
    var bootAnim: Boolean
        get() = bool("bootAnim", true)
        set(v) = setBool("bootAnim", v)
    var launchAnim: Boolean
        get() = bool("launchAnim", true)
        set(v) = setBool("launchAnim", v)
    var clock24: Boolean
        get() = bool("clock24", true)
        set(v) = setBool("clock24", v)
}

/** XMB sound effects, from the imported [AssetPack]; missing sounds are silent. */
class Sfx(private val pack: AssetPack, private val prefs: Prefs) {
    // Confirm and back reuse the pack's "up" and "down" sounds, as on the PSP.
    enum class Id(val file: String) {
        UP("up"), DOWN("down"), OK("up"), CANCEL("down"), NOTICE("notice"), NOTICE_BACK("notice_back"),
        LAUNCH("launch"), BOOT("boot"),
    }

    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = HashMap<Id, Int>()
    private val ready = HashSet<Int>()
    private val pending = HashSet<Int>() // asked to play before loading finished
    private var bgmPlayer: MediaPlayer? = null

    init {
        pool.setOnLoadCompleteListener { _, sample, status ->
            if (status != 0) return@setOnLoadCompleteListener
            ready += sample
            if (pending.remove(sample)) pool.play(sample, 1f, 1f, 1, 0, 1f)
        }
        // Ids that share a file (confirm and cursor-up both use "up") share one loaded sample.
        val loaded = HashMap<String, Int>()
        for (id in Id.values()) {
            val file = pack.sound(id.file) ?: continue
            ids[id] = loaded.getOrPut(file.path) { pool.load(file.path, 1) }
        }
    }

    fun has(id: Id) = id in ids

    /** Sounds that are part of an animation play even with menu sounds off. */
    fun play(id: Id, force: Boolean = false) {
        if (!prefs.sfx && !force) return
        val sample = ids[id] ?: return
        if (sample in ready) pool.play(sample, 1f, 1f, 1, 0, 1f) else pending += sample
    }

    fun setBgm(on: Boolean) {
        val music = pack.sound("bgm")
        if (on && bgmPlayer == null && music != null) {
            bgmPlayer = MediaPlayer().apply {
                setDataSource(music.path)
                isLooping = true
                setVolume(0.5f, 0.5f)
                prepare()
                start()
            }
        } else if (!on) {
            bgmPlayer?.release(); bgmPlayer = null
        }
    }

    /** Lowers the background music while a game's SND0 plays. */
    fun duckBgm(duck: Boolean) {
        val v = if (duck) 0f else 0.5f
        bgmPlayer?.setVolume(v, v)
    }

    fun pauseBgm() = bgmPlayer?.pause()
    fun resumeBgm() = bgmPlayer?.start()

    fun release() {
        pool.release(); bgmPlayer?.release(); bgmPlayer = null
    }
}

/** Wave background palettes: top-left, middle and bottom-right of the diagonal gradient. */
object Themes {
    class Palette(val name: String, val c0: Int, val c1: Int, val c2: Int)

    private fun shade(base: Int, f: Float): Int {
        fun ch(s: Int) = (((base shr s) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun tint(base: Int, t: Float): Int {
        fun ch(s: Int) = (((base shr s) and 0xFF) + (255 - ((base shr s) and 0xFF)) * t).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun monthly(name: String, base: Int) = Palette(name, shade(base, 0.42f), shade(base, 0.85f), tint(base, 0.45f))

    // The PSP's "Original" theme changes color every month.
    val months = listOf(
        monthly("January", 0xC4C4C8), monthly("February", 0xD8B81E), monthly("March", 0x6DBB18),
        monthly("April", 0xE07D9C), monthly("May", 0x1E9C2A), monthly("June", 0x9A61C8),
        monthly("July", 0x00ACB4), monthly("August", 0x0F63D5), monthly("September", 0x7A3FB3),
        monthly("October", 0xD49416), monthly("November", 0x8D5A2B), monthly("December", 0xC82121),
    )

    // Sampled from the wave reference videos.
    val fixed = listOf(
        Palette("Blue", 0x0207B5, 0x0C85F3, 0x3EF0FB),
        Palette("Baby Blue", 0x39DAFA, 0x76F6FD, 0xB3FFFD),
        Palette("Lime Green", 0x40C805, 0x86E812, 0xBCFF4D),
        Palette("Violet", 0x260060, 0x3F00AA, 0xB900F6),
        Palette("White", 0xB8B6C4, 0xD6DEE8, 0xF4FAFE),
    )

    val choices: List<Pair<String, String>> =
        listOf("original" to "Original") + fixed.map { it.name to it.name } + months.map { it.name to it.name }

    fun current(key: String): Palette {
        if (key == "original") return months[Calendar.getInstance().get(Calendar.MONTH)]
        return (fixed + months).firstOrNull { it.name == key } ?: months[Calendar.getInstance().get(Calendar.MONTH)]
    }

    /** The colour square the PSP shows beside a theme. */
    fun swatch(key: String): Int = 0xFF000000.toInt() or current(key).c1

    fun label(key: String) = choices.firstOrNull { it.first == key }?.second ?: "Original"
}

/** Finds and launches PPSSPP. */
object Ppsspp {
    const val FREE = "org.ppsspp.ppsspp"
    const val GOLD = "org.ppsspp.ppssppgold"
    private const val ACTIVITY = "org.ppsspp.ppsspp.PpssppActivity"

    fun installed(ctx: Context): List<String> = listOf(GOLD, FREE).filter { pkg ->
        try {
            if (Build.VERSION.SDK_INT >= 33) ctx.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun version(ctx: Context, pkg: String): String? = try {
        @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(pkg, 0).versionName
    } catch (e: Exception) { null }

    /** The package to launch, honoring the user's choice when both editions are installed. */
    fun pick(ctx: Context, choice: String): String? {
        val all = installed(ctx)
        return when (choice) {
            "free" -> all.firstOrNull { it == FREE } ?: all.firstOrNull()
            "gold" -> all.firstOrNull { it == GOLD } ?: all.firstOrNull()
            else -> all.firstOrNull()
        }
    }

    fun launch(ctx: Context, pkg: String, game: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(game, "application/octet-stream")
            setClassName(pkg, ACTIVITY)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return try {
            ctx.startActivity(intent); true
        } catch (e: ActivityNotFoundException) {
            // Older builds: let PPSSPP's own launcher activity take the intent.
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(game, "application/octet-stream")
                    setPackage(pkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }); true
            } catch (e2: Exception) {
                false
            }
        } catch (e: SecurityException) {
            false
        }
    }

    fun openStore(ctx: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$FREE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.ppsspp.org/download/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
