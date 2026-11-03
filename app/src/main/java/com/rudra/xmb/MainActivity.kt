package com.rudra.xmb

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.rudra.xmb.media.PmfPlayer
import com.rudra.xmb.media.Snd0Player
import java.util.concurrent.Executors

class MainActivity : Activity(), XmbHost {
    private lateinit var prefs: Prefs
    private lateinit var pack: AssetPack
    private lateinit var sfx: Sfx
    private lateinit var library: GameLibrary
    private lateinit var saveLibrary: SaveLibrary
    private lateinit var root: FrameLayout
    private lateinit var waves: WaveView
    private lateinit var xmb: XmbView
    private lateinit var video: TextureView
    private lateinit var font: Typeface
    private var intro: IntroView? = null
    private var launching = false

    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val snd0 = Snd0Player()
    private val icon1 = PmfPlayer()
    private var videoSurface: Surface? = null
    private var iconRect: RectF? = null
    private var videoPlaying = false
    private var hoverGame: Game? = null
    private val hoverToken = Any()
    private var installDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Every device boot starts the app from scratch, with the power-on sequence; nothing is restored.
        newBoot = isNewBoot()
        val restored = if (newBoot) null else savedInstanceState
        super.onCreate(restored)
        // Show straight away at power-on instead of after the (empty) lock screen.
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        // At power-on Android runs this (direct boot aware) home screen before the user is unlocked,
        // instead of its placeholder home with the wallpaper and status bar. Settings and the game
        // cache aren't readable until the unlock, which follows a moment later, so stay black till then.
        val users = getSystemService(android.os.UserManager::class.java)
        if (users != null && !users.isUserUnlocked) {
            setContentView(View(this).apply { setBackgroundColor(android.graphics.Color.BLACK) })
            goFullscreen()
            unlockReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: Intent) {
                    unregisterReceiver(this); unlockReceiver = null
                    start(restored)
                    if (isResumed()) onResume()
                }
            }
            registerReceiver(unlockReceiver, android.content.IntentFilter(Intent.ACTION_USER_UNLOCKED))
            return
        }
        start(restored)
    }

    private var newBoot = false

    /**
     * True the first time this runs after the device booted. Kept in device-protected storage, which
     * (unlike the normal settings) can be read before the user is unlocked at power-on.
     */
    private fun isNewBoot(): Boolean {
        val count = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        val store = createDeviceProtectedStorageContext().getSharedPreferences("boot", MODE_PRIVATE)
        if (count < 0 || store.getInt("bootCount", -2) == count) return false
        store.edit().putInt("bootCount", count).apply()
        return true
    }

    private var started = false
    private var resumedNow = false
    private var unlockReceiver: android.content.BroadcastReceiver? = null
    private fun isResumed() = resumedNow

    private fun start(savedInstanceState: Bundle?) {
        started = true
        prefs = Prefs(this)
        pack = AssetPack(this)
        sfx = Sfx(this, pack, prefs)
        font = pack.font()
        library = GameLibrary(this)
        saveLibrary = SaveLibrary(this)

        root = FrameLayout(this)
        waves = WaveView(this).apply {
            setPalette(Themes.current(prefs.theme))
            setAnimated(true)
        }
        xmb = XmbView(this, this, font).apply { clock24 = prefs.clock24 }
        video = TextureView(this).apply {
            alpha = 0f
            isOpaque = false
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    videoSurface = Surface(st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    icon1.stop()
                    videoSurface?.release(); videoSurface = null
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(waves, FrameLayout.LayoutParams(match, match))
        root.addView(xmb, FrameLayout.LayoutParams(match, match))
        root.addView(video, FrameLayout.LayoutParams(144, 80))
        setContentView(root)
        goFullscreen() // needs the decor view, so only after setContentView

        xmb.setCategories(buildMenu(), start = 4)
        library.listener = { xmb.refresh(); refreshFreeSpace() }
        library.scan(prefs.gameFolders)
        saveLibrary.listener = { xmb.refresh() }
        saveLibrary.scan(prefs.saveFolder)
        sfx.setBgm(prefs.bgm)

        val debugIntro = if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringExtra("debugIntro") // e.g. "boot@2.0" or "game@1.9"
        } else null
        if (debugIntro != null) {
            val mode = if (debugIntro.startsWith("game")) IntroView.Mode.GAME else IntroView.Mode.BOOT
            showIntro(mode, onCue = {}) {}
            val at = debugIntro.substringAfter('@').toFloatOrNull() ?: 1f
            intro?.frozenAt = at
            xmb.setChrome(0f, 0f, instant = true)
            if (mode == IntroView.Mode.BOOT) waves.freezeBoot(at)
        } else if (prefs.bootAnim && (newBoot || (savedInstanceState == null && !isHomeApp()))) {
            playBoot()
        } else {
            checkEmulator()
        }
        xmb.requestFocus()
    }

    private val bootToken = Any()

    /** The power-on sequence. As on the PSP: the waves come first, then the clock, then the menu. */
    private fun playBoot() {
        handler.removeCallbacksAndMessages(bootToken)
        bootLocked = true
        xmb.setChrome(0f, 0f, instant = true)
        waves.startBoot()
        showIntro(IntroView.Mode.BOOT, onCue = { sfx.play(Sfx.Id.BOOT, force = true) }) {
            removeIntro()
            val now = SystemClock.uptimeMillis()
            handler.postAtTime({ xmb.setChrome(0f, 1f, speed = 5f) }, bootToken, now + CLOCK_AFTER_BOOT_MS)
            handler.postAtTime({
                xmb.setChrome(1f, 1f, speed = 5f)
                bootLocked = false // input comes back the moment the menu appears
                checkEmulator()
            }, bootToken, now + MENU_AFTER_BOOT_MS)
        }
    }


    override fun onResume() {
        if (!resumedNow) super.onResume()
        resumedNow = true
        goFullscreen()
        if (!started) return
        waves.onResume()
        sfx.resumeBgm()
        if (launching) {
            launching = false
            removeIntro()
            xmb.setChrome(1f, 1f)
        }
        if (intro == null) checkEmulator()
        hoverGame?.let { startHover(it) }
        refreshFreeSpace()
        homeAppsCache = null
        // Back from a game: its saves may have changed.
        if (!launching && intro == null && prefs.saveFolder != null) saveLibrary.scan(prefs.saveFolder)
    }

    override fun onPause() {
        super.onPause()
        resumedNow = false
        if (!started) return
        waves.onPause()
        sfx.pauseBgm()
        stopPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        unlockReceiver?.let { unregisterReceiver(it) }
        if (!started) return
        stopPreview()
        sfx.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    /** True from power-on until the menu appears. */
    private var bootLocked = false

    /**
     * No input while an intro plays or the menu is still appearing after power-on; the sequences
     * can't be skipped.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!started || intro != null || bootLocked) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!started || intro != null || bootLocked) return true
        return super.dispatchTouchEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!started) return
        // The XMB handles back itself (it arrives as KEYCODE_BACK).
        xmb.onKeyDown(android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent(0, android.view.KeyEvent.KEYCODE_BACK))
    }

    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // ------------------------------------------------------------ menu

    private fun icon(name: String): Bitmap = pack.icon(name)

    private val onOff = listOf("on" to "On", "off" to "Off")

    private fun toggle(title: String, icon: Bitmap, get: () -> Boolean, set: (Boolean) -> Unit) = XItem(
        title = title, icon = icon,
        value = { if (get()) "On" else "Off" },
        choices = { onOff }, current = { if (get()) "on" else "off" },
        onChoose = { set(it == "on") },
    )

    private fun level(vararg items: XItem) = XLevel(items = { items.toList() }, emptyText = { "" })

    private var gameItems: List<XItem> = emptyList()
    private var gameItemsFor: List<Game>? = null
    private var saveItems: List<XItem> = emptyList()
    private var saveItemsFor: List<Game>? = null

    private fun buildMenu(): List<XCategory> {
        val gameIcon = icon("cat_game")
        val memstick = icon("memstick_fill") // the PSP's Memory Stick icon is a filled card
        val savedData = icon("memstick") // same card as Game Folder Settings

        val gameList = XLevel(
            items = {
                if (gameItemsFor !== library.games) {
                    gameItemsFor = library.games
                    gameItems = library.games.map { XItem(title = it.title, icon = gameIcon, game = it) }
                }
                gameItems
            },
            emptyText = {
                when (library.state) {
                    GameLibrary.State.NO_FOLDER -> "No game folder is set."
                    GameLibrary.State.SCANNING -> "Please wait..."
                    GameLibrary.State.READY -> "There are no games."
                }
            },
            gameList = true,
        )
        lateinit var memstickItem: XItem
        memstickItem = XItem(title = "Memory Stick™", icon = memstick, subtitle = { freeSpace }, action = {
            if (prefs.gameFolders.isEmpty()) {
                xmb.showDialog("No game folder is set.\nSelect the folder that contains your ISO / CSO files?", listOf("Yes", "No")) { b ->
                    xmb.closeDialog()
                    if (b == 0) pickFolder()
                }
            } else xmb.openLevel(memstickItem, gameList)
        })

        val settings = XCategory(
            "Settings", icon("cat_settings"), listOf(
                XItem(
                    title = "Game Folder Settings", icon = icon("memstick"),
                    subtitle = {
                        val folders = prefs.gameFolders
                        when (folders.size) {
                            0 -> "Not set"
                            1 -> folderName(folders[0])
                            else -> "${folders.size} folders"
                        }
                    },
                    sub = folderLevel(),
                ),
                XItem(
                    title = "Theme Settings", icon = icon("theme"), sub = level(
                        XItem(
                            title = "Theme", icon = icon("theme"),
                            value = { Themes.label(prefs.theme) },
                            swatch = { Themes.swatch(prefs.theme) },
                            choices = { Themes.choices }, current = { prefs.theme },
                            choiceSwatch = { Themes.swatch(it) },
                            onChoose = { prefs.theme = it; waves.setPalette(Themes.current(it)) },
                        ),
                        toggle("Wave Animation", icon("theme_wallpaper"), { waveAnimated }, {
                            waveAnimated = it; waves.setAnimated(it)
                        }),
                    )
                ),
                XItem(
                    title = "Sound Settings", icon = icon("sound"), sub = level(
                        toggle("Menu Sound Effects", icon("sound"), { prefs.sfx }, { prefs.sfx = it }),
                        toggle("Game Music Preview", icon("folder_music"), { prefs.gameMusic }, {
                            prefs.gameMusic = it
                            if (!it) snd0.stop()
                        }),
                        toggle("XMB Background Music", icon("cat_music"), { prefs.bgm }, { prefs.bgm = it; sfx.setBgm(it) }),
                    )
                ),
                XItem(
                    title = "System Settings", icon = icon("system_settings"), sub = level(
                        toggle("Power-On Animation", icon("psp"), { prefs.bootAnim }, { prefs.bootAnim = it }),
                        toggle("Game Boot Animation", icon("umd"), { prefs.launchAnim }, { prefs.launchAnim = it }),
                        toggle("Animated Game Icons", icon("remote_play"), { prefs.iconVideo }, {
                            prefs.iconVideo = it
                            if (!it) stopVideo()
                        }),
                        XItem(
                            title = "Time Format", icon = icon("date_time"),
                            value = { if (prefs.clock24) "24-Hour Clock" else "12-Hour Clock" },
                            choices = { listOf("24" to "24-Hour Clock", "12" to "12-Hour Clock") },
                            current = { if (prefs.clock24) "24" else "12" },
                            onChoose = { prefs.clock24 = it == "24"; xmb.clock24 = prefs.clock24 },
                        ),
                    )
                ),
                XItem(
                    title = "Emulator Settings", icon = icon("game_sharing"), sub = level(
                        XItem(
                            title = "Emulator", icon = icon("game_sharing"),
                            value = { emulatorLabel(prefs.emulator) },
                            choices = { listOf("auto" to "Automatic", "free" to "PPSSPP", "gold" to "PPSSPP Gold") },
                            current = { prefs.emulator },
                            onChoose = { prefs.emulator = it },
                        ),
                        XItem(title = "Get PPSSPP", icon = icon("network_update"), action = { Ppsspp.openStore(this) }),
                    )
                ),
                XItem(
                    title = "System Information", icon = icon("psp"), sub = level(
                        XItem(title = "System Software", icon = icon("psp"), value = { "Version ${appVersion()}" }),
                        XItem(title = "PPSSPP", icon = icon("game_sharing"), value = {
                            Ppsspp.pick(this, prefs.emulator)?.let { pkg ->
                                (if (pkg == Ppsspp.GOLD) "Gold " else "") + (Ppsspp.version(this, pkg) ?: "")
                            } ?: "Not installed"
                        }),
                        XItem(title = "Games", icon = icon("memstick"), value = { library.games.size.toString() }),
                    )
                ),
                XItem(title = "Rescan Games", icon = icon("network_update"), action = { rescan() }),
                XItem(
                    title = "XMB Asset Folder", icon = icon("theme_wallpaper"),
                    subtitle = { pack.summary() },
                    action = { pickAssetFolder() },
                ),
                XItem(
                    title = "Open Home App", icon = icon("home"),
                    subtitle = { "Also opens with Select. △ changes the app." },
                    value = { homeApps().firstOrNull { it.first == prefs.otherHome }?.second ?: "Not set" },
                    choices = { homeApps() }, current = { prefs.otherHome ?: "" },
                    onChoose = { prefs.otherHome = it; openOtherHome() },
                    action = { openOtherHome() },
                ),
                XItem(title = "Android Settings", icon = icon("system_settings"), action = { openAndroidSettings() }),
            )
        )
        val photo = XCategory(
            "Photo", icon("cat_photo"),
            listOf(XItem("Memory Stick™", memstick, sub = XLevel({ emptyList() }, { "There are no images." })))
        )
        val music = XCategory(
            "Music", icon("cat_music"),
            listOf(XItem("Memory Stick™", memstick, sub = XLevel({ emptyList() }, { "There are no tracks." })))
        )
        val videoCat = XCategory(
            "Video", icon("cat_video"),
            listOf(XItem("Memory Stick™", memstick, sub = XLevel({ emptyList() }, { "There are no videos." })))
        )
        val saveList = XLevel(
            items = {
                if (saveItemsFor !== saveLibrary.saves) {
                    saveItemsFor = saveLibrary.saves
                    saveItems = saveLibrary.saves.map { XItem(title = it.title, icon = savedData, game = it) }
                }
                saveItems
            },
            emptyText = {
                when (saveLibrary.state) {
                    GameLibrary.State.NO_FOLDER -> "The PPSSPP SAVEDATA folder is not set."
                    GameLibrary.State.SCANNING -> "Please wait..."
                    GameLibrary.State.READY -> "There is no saved data."
                }
            },
            gameList = true,
        )
        lateinit var savedDataItem: XItem
        savedDataItem = XItem(title = "Saved Data Utility", icon = savedData, action = {
            if (prefs.saveFolder == null) {
                xmb.showDialog(
                    "Select PPSSPP's save data folder (PSP/SAVEDATA on the storage PPSSPP uses).\nSave states are not shown.",
                    listOf("Select", "Cancel"),
                ) { b ->
                    xmb.closeDialog()
                    if (b == 0) pickSaveFolder()
                }
            } else {
                saveLibrary.scan(prefs.saveFolder) // saves change while playing
                xmb.openLevel(savedDataItem, saveList)
            }
        })
        // Saved Data Utility sits above Memory Stick, which stays selected.
        val game = XCategory("Game", gameIcon, listOf(savedDataItem, memstickItem)).apply { sel = 1; anim = 1f }
        val network = XCategory(
            "Network", icon("cat_network"), listOf(
                XItem("Internet Browser", icon("browser"), action = {
                    openUrl("https://www.google.com/")
                }),
                XItem("PPSSPP Website", icon("rss"), action = { openUrl("https://www.ppsspp.org/") }),
            )
        )
        return listOf(settings, photo, music, videoCat, game, network)
    }

    private var waveAnimated = true

    /** "Free Space 119 GB" for the volume holding the games folder, shown under Memory Stick. */
    private var freeSpace: String? = null

    private fun refreshFreeSpace() {
        val tree = prefs.gameFolders.firstOrNull() ?: run { freeSpace = null; return }
        loader.execute {
            val bytes = try {
                val volume = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
                val dir = if (volume.equals("primary", true)) android.os.Environment.getExternalStorageDirectory()
                else java.io.File("/storage/$volume")
                android.os.StatFs(dir.path).availableBytes
            } catch (e: Exception) {
                -1L
            }
            val label = when {
                bytes < 0 -> null
                bytes >= 1L shl 30 -> "Free Space ${bytes / (1L shl 30)} GB"
                else -> "Free Space ${bytes / (1L shl 20)} MB"
            }
            runOnUiThread { freeSpace = label; xmb.invalidate() }
        }
    }

    private fun emulatorLabel(key: String) = when (key) {
        "free" -> "PPSSPP"
        "gold" -> "PPSSPP Gold"
        else -> "Automatic"
    }

    private fun appVersion(): String = try {
        @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) { "1.0" }

    private fun openUrl(url: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {}

    private fun folderName(uri: Uri): String = try {
        DocumentsContract.getTreeDocumentId(uri).substringAfter(':').ifEmpty { "Internal storage" }
    } catch (e: Exception) { uri.toString() }

    // ------------------------------------------------------------ game folder

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        // Start where PSP games usually live; the picker falls back to its default if it doesn't exist.
        intent.putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            prefs.gameFolders.lastOrNull() ?: Uri.parse("content://com.android.externalstorage.documents/document/primary%3APSP%2FGAME"),
        )
        try {
            @Suppress("DEPRECATION") startActivityForResult(intent, REQ_FOLDER)
        } catch (e: Exception) {
            xmb.showDialog("This device has no folder picker.", listOf("OK")) { xmb.closeDialog() }
        }
    }

    /**
     * The app works with its built-in look. Once, on first run, it mentions that a folder with your
     * own font, icons and sounds can replace it.
     */
    private fun checkAssetPack() {
        if (pack.installed || prefs.packOffered || xmb.hasDialog) return
        prefs.packOffered = true
        xmb.showDialog(
            "You can replace the built-in font, icons and sounds with your own.\n" +
                "Select a folder with them now, or later in Settings › XMB Asset Folder.",
            listOf("Select", "Later"),
            selected = 1,
        ) { b ->
            xmb.closeDialog()
            if (b == 0) pickAssetFolder()
        }
    }

    private fun pickAssetFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION") startActivityForResult(intent, REQ_ASSETS)
        } catch (e: Exception) {
            xmb.showDialog("This device has no folder picker.", listOf("OK")) { xmb.closeDialog() }
        }
    }

    private fun importAssets(tree: Uri) {
        xmb.showDialog("Importing...", emptyList()) {}
        loader.execute {
            val result = try { pack.import(tree) } catch (e: Exception) { null }
            runOnUiThread {
                xmb.closeDialog()
                when {
                    result == null -> xmb.showDialog("The folder could not be read.", listOf("OK")) { xmb.closeDialog() }
                    result.empty -> xmb.showDialog(
                        "No XMB files were found in that folder.\nSee the README for the file names.", listOf("OK"),
                    ) { xmb.closeDialog() }
                    // Font, icons and sounds are loaded at start: rebuild the screen with them.
                    else -> recreate()
                }
            }
        }
    }

    private fun pickSaveFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        intent.putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            prefs.saveFolder ?: Uri.parse("content://com.android.externalstorage.documents/document/primary%3APSP%2FSAVEDATA"),
        )
        try {
            @Suppress("DEPRECATION") startActivityForResult(intent, REQ_SAVES)
        } catch (e: Exception) {
            xmb.showDialog("This device has no folder picker.", listOf("OK")) { xmb.closeDialog() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ASSETS && resultCode == RESULT_OK) {
            data?.data?.let { importAssets(it) }
            return
        }
        if (requestCode == REQ_SAVES && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e2: SecurityException) {}
            }
            prefs.saveFolder = uri
            xmb.popToRoot()
            saveLibrary.scan(uri)
            return
        }
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {}
        if (uri !in prefs.gameFolders) prefs.gameFolders = prefs.gameFolders + uri
        xmb.refresh()
        rescan()
    }

    private fun rescan() {
        library.scan(prefs.gameFolders)
    }

    private var folderItems: List<XItem> = emptyList()
    private var folderItemsFor: List<Uri>? = null

    /** Settings › Game Folder Settings: every scanned folder (select one to remove it), then Add Folder. */
    private fun folderLevel(): XLevel {
        val card = icon("memstick")
        val add = XItem(title = "Add Folder", icon = icon("folder_photo"), action = { pickFolder() })
        return XLevel(items = {
            val folders = prefs.gameFolders
            if (folders != folderItemsFor) {
                folderItemsFor = folders
                folderItems = folders.map { uri ->
                    XItem(title = folderName(uri), icon = card, subtitle = { "Select to remove" }, action = { removeFolder(uri) })
                } + add
            }
            folderItems
        }, emptyText = { "" })
    }

    private fun removeFolder(uri: Uri) {
        xmb.showDialog("Remove this folder from the game list?\n${folderName(uri)}", listOf("Yes", "No"), selected = 1) { b ->
            xmb.closeDialog()
            if (b != 0) return@showDialog
            prefs.gameFolders = prefs.gameFolders - uri
            try {
                contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {}
            xmb.refresh()
            rescan()
            refreshFreeSpace()
        }
    }

    // ------------------------------------------------------------ PPSSPP

    /** PPSSPP is required: until it is installed the user can't get past this dialog. */
    private fun checkEmulator() {
        val installed = Ppsspp.installed(this).isNotEmpty()
        if (installed) {
            if (installDialogShown) { installDialogShown = false; xmb.closeDialog() }
            checkAssetPack()
            return
        }
        if (installDialogShown) return
        installDialogShown = true
        xmb.showDialog(
            "PPSSPP is not installed.\nThis system needs the PPSSPP emulator to play games. Install it to continue.",
            listOf("Install", "Check Again"),
        ) { b ->
            if (b == 0) Ppsspp.openStore(this)
            else if (Ppsspp.installed(this).isNotEmpty()) { installDialogShown = false; xmb.closeDialog() }
        }
    }

    override fun onLaunch(game: Game) {
        if (game.save != null) {
            // Saved data isn't playable; the confirm button opens its options, as on the PSP.
            xmb.showOptions(gameOptions(game))
            return
        }
        val pkg = Ppsspp.pick(this, prefs.emulator)
        if (pkg == null) { checkEmulator(); return }
        stopPreview()
        val go = {
            launching = true
            if (!Ppsspp.launch(this, pkg, game.uri)) {
                launching = false
                removeIntro()
                xmb.setChrome(1f, 1f)
                xmb.showDialog("Could not start PPSSPP.", listOf("OK")) { xmb.closeDialog() }
            }
        }
        if (prefs.launchAnim) {
            // The menu and clock drop away first, leaving only the waves for the game boot to wash over.
            xmb.setChrome(0f, 0f, speed = 25f)
            showIntro(IntroView.Mode.GAME, onCue = { sfx.play(Sfx.Id.LAUNCH, force = true) }) { go() }
        } else {
            sfx.play(Sfx.Id.LAUNCH)
            go()
        }
    }

    private fun showIntro(mode: IntroView.Mode, onCue: () -> Unit, onDone: () -> Unit) {
        removeIntro()
        val v = IntroView(this, mode, pack.text("boot"), pack.text("gameboot"), onCue, onDone)
        intro = v
        goFullscreen()
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun removeIntro() {
        intro?.let { root.removeView(it) }
        intro = null
        xmb.requestFocus()
    }

    private var homeAppsCache: List<Pair<String, String>>? = null

    /** Other installed home screens as ("package/activity", label); re-read when the app comes back. */
    private fun homeApps(): List<Pair<String, String>> = homeAppsCache ?: queryHomeApps().also { homeAppsCache = it }

    private fun queryHomeApps(): List<Pair<String, String>> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        return packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != "com.android.settings" } // Settings' is only a boot placeholder
            .map { "${it.packageName}/${it.name}" to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    /** Starts the chosen home app, or asks which one when none is chosen yet. */
    private fun openOtherHome() {
        val chosen = prefs.otherHome?.takeIf { c -> homeApps().any { it.first == c } }
        if (chosen == null) {
            val apps = homeApps()
            if (apps.isEmpty()) {
                xmb.showDialog("No other home app is installed.", listOf("OK")) { xmb.closeDialog() }
                return
            }
            xmb.showOptions(apps.map { (key, label) -> XOption(label) { prefs.otherHome = key; openOtherHome() } })
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(chosen.substringBefore('/'), chosen.substringAfter('/'))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            xmb.showDialog("That home app could not be opened.", listOf("OK")) { xmb.closeDialog() }
        }
    }

    private fun isHomeApp(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val info = packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == packageName
    }

    // ------------------------------------------------------------ XmbHost

    override fun sfx(id: Sfx.Id) = sfx.play(id)

    /** The Select button: the home app chosen under Settings › Open Home App. */
    override fun onSelectButton() = openOtherHome()

    private fun openAndroidSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        } catch (e: Exception) {
            xmb.showDialog("Android Settings could not be opened.", listOf("OK")) { xmb.closeDialog() }
        }
    }

    override fun onExit() {
        if (installDialogShown) return
        if (isHomeApp()) return // the home screen has nothing to exit to
        xmb.showDialog("Do you want to exit?", listOf("Yes", "No"), selected = 1) { b ->
            xmb.closeDialog()
            if (b == 0) finish()
        }
    }

    override fun gameOptions(game: Game): List<XOption> = game.save?.let { saveOptions(game, it) } ?: listOf(
        XOption("Information") {
            val size = "%.1f MB".format(game.size / 1048576.0)
            val lines = buildString {
                append(game.title)
                if (game.discId.isNotEmpty()) append("\n").append(game.discId)
                if (game.version.isNotEmpty()) append("   Version ").append(game.version)
                append("\n").append(game.fileName).append("   ").append(size)
            }
            xmb.showDialog(lines, listOf("OK")) { xmb.closeDialog() }
        },
        XOption("Add Game Folder") { pickFolder() },
        XOption("Rescan Games") { rescan() },
    )

    private fun saveOptions(game: Game, save: SaveInfo): List<XOption> = listOf(
        XOption("Information") {
            val date = java.text.SimpleDateFormat("yyyy/MM/dd  H:mm", java.util.Locale.US).format(java.util.Date(save.modified))
            val size = if (game.size >= 1048576) "%.1f MB".format(game.size / 1048576.0) else "${(game.size + 1023) / 1024} KB"
            val lines = buildString {
                append(game.title)
                if (save.saveTitle.isNotEmpty()) append("\n").append(save.saveTitle)
                if (save.detail.isNotEmpty()) append("\n").append(save.detail.replace('\n', ' ').take(160))
                append("\n").append(save.name).append("   ").append(date).append("   ").append(size)
            }
            xmb.showDialog(lines, listOf("OK")) { xmb.closeDialog() }
        },
        XOption("Delete") {
            xmb.showDialog("Do you want to delete this saved data?\n${game.title}", listOf("Yes", "No"), selected = 1) { b ->
                xmb.closeDialog()
                if (b == 0) deleteSave(game, save)
            }
        },
        XOption("Change Save Data Folder") { pickSaveFolder() },
    )

    private fun deleteSave(game: Game, save: SaveInfo) {
        loader.execute {
            val ok = try {
                DocumentsContract.deleteDocument(contentResolver, save.folder)
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    saveLibrary.forget(game)
                    xmb.showDialog("The saved data was deleted.", listOf("OK")) { xmb.closeDialog() }
                } else {
                    xmb.showDialog(
                        "The saved data could not be deleted.\nSelect the SAVEDATA folder again to allow changes.",
                        listOf("OK"),
                    ) { xmb.closeDialog() }
                }
            }
        }
    }

    /** The PSP waits a moment on a game before showing its backdrop and playing its music and icon movie. */
    override fun onGameFocus(game: Game?) {
        hoverGame = game
        handler.removeCallbacksAndMessages(hoverToken)
        stopPreview()
        xmb.showPics(null, null)
        if (game != null) startHover(game)
    }

    private fun startHover(game: Game) {
        handler.removeCallbacksAndMessages(hoverToken)
        val now = SystemClock.uptimeMillis()
        handler.postAtTime({
            loader.execute {
                val p1 = game.pic1?.let { BitmapFactory.decodeFile(it.path) }
                val p0 = game.pic0?.let { BitmapFactory.decodeFile(it.path) }
                runOnUiThread { if (hoverGame === game) xmb.showPics(p1, p0) }
            }
        }, hoverToken, now + 400)
        handler.postAtTime({ startPreview(game) }, hoverToken, now + 900)
    }

    private fun startPreview(game: Game) {
        loader.execute {
            val sound = if (prefs.gameMusic) game.snd0?.readBytes() else null
            val movie = if (prefs.iconVideo) game.icon1?.readBytes() else null
            runOnUiThread {
                if (hoverGame !== game || launching) return@runOnUiThread
                if (sound != null) {
                    sfx.duckBgm(true)
                    snd0.play(sound)
                }
                val surface = videoSurface
                if (movie != null && surface != null) {
                    icon1.play(movie, surface) {
                        runOnUiThread {
                            if (hoverGame === game) {
                                videoPlaying = true
                                placeVideo()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopVideo() {
        icon1.stop()
        videoPlaying = false
        video.alpha = 0f
    }

    private fun stopPreview() {
        snd0.stop()
        sfx.duckBgm(false)
        stopVideo()
    }

    override fun onIconRect(rect: RectF?) {
        iconRect = rect
        placeVideo()
    }

    private fun placeVideo() {
        val r = iconRect
        if (r == null || !videoPlaying) {
            video.alpha = 0f
            return
        }
        val lp = video.layoutParams
        if (lp.width != r.width().toInt() || lp.height != r.height().toInt()) {
            lp.width = r.width().toInt(); lp.height = r.height().toInt()
            video.layoutParams = lp
        }
        video.translationX = r.left
        video.translationY = r.top
        video.alpha = 1f
    }

    companion object {
        private const val REQ_FOLDER = 1
        private const val REQ_SAVES = 2
        private const val REQ_ASSETS = 3

        // Measured from a real PSP: after the boot veil lifts, the clock appears, then the menu.
        private const val CLOCK_AFTER_BOOT_MS = 2750L
        private const val MENU_AFTER_BOOT_MS = 3450L
    }
}
