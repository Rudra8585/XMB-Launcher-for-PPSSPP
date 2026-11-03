# XMB Launcher for PPSSPP

An Android launcher that recreates the PSP's XrossMediaBar and starts games in PPSSPP. Built for the Retroid Pocket 5 (1920×1080, built-in controls).

## What it does

- **XMB**: Settings, Photo, Music, Video, Game, and Network categories. The font, icons and sounds come from an asset folder you provide (see below).
- **Wave background**: rendered live in an OpenGL shader, not a video. The *Original* theme changes color each month like the PSP, and the background dims at night. Fixed themes: Blue, Baby Blue, Lime Green, Violet, White (colors sampled from the reference videos).
- **Game › Memory Stick™**: lists `.iso`, `.cso` and `EBOOT.PBP` games the way the PSP does. On hover:
  - the game's `ICON0.PNG` icon is shown;
  - `PIC1.PNG` (backdrop) and `PIC0.PNG` (overlay) fade in;
  - `SND0.AT3` music plays, using the ATRAC3/ATRAC3+ decoder from PPSSPP;
  - the `ICON1.PMF` movie plays inside the icon.
- **Animations**: the power-on sequence plays at launch, and the game boot animation plays before handing off to PPSSPP.
- **PPSSPP handoff**: the selected game opens in PPSSPP (free or Gold). If PPSSPP is missing, a blocking prompt links to the Play Store.
- **Game › Saved Data Utility**: lists PPSSPP save data (`PSP/SAVEDATA`, not save states) with each save's icon, backdrop, music and movie; Information and Delete from the options menu.
- **Settings › Game Folder Settings**: the folders scanned for games (subfolders included). Add as many as you like; select one to remove it.

## Using it as the home screen

The app can replace the Android home screen. On the device, go to **Settings › Apps › Default apps › Home app** and pick **XMB Launcher** (or press Home and choose it with **Always**). As the home app, the power-on sequence plays once per device boot, and back on the top menu no longer offers to exit.

## Controls

| Button | Action |
| --- | --- |
| D-pad | Move |
| A (✕) | Select |
| B (○) | Back |
| Y (△) | Options menu |
| Select | Open the home app chosen in **Settings › Open Home App** |

## Asset folder

The app ships without any fonts, icons, sounds or logos. On first start it asks for a folder holding your own copies; you can change it later in **Settings › XMB Asset Folder**. The files are copied into the app, so the folder can be deleted afterwards. Subfolders are searched, and files are matched by name:

| What | File names |
| --- | --- |
| Font | any `.ttf` or `.otf` (a name containing `rodin` wins if there are several) |
| Icons (`.png`) | `cat_settings`, `cat_photo`, `cat_music`, `cat_video`, `cat_game`, `cat_network`, `memstick`, `memstick_fill`, `theme`, `theme_wallpaper`, `sound`, `system_settings`, `game_sharing`, `network_update`, `psp`, `umd`, `remote_play`, `date_time`, `folder_music`, `folder_photo`, `browser`, `rss`, `home` |
| Sounds (`.ogg`, `.flac`, `.wav` or `.mp3`) | `up`, `down` (cursor; also confirm and back), `notice`, `notice_back` (option menus), `boot` (power-on), `launch` (game boot), `bgm` (optional background music) |
| Text (`.txt`, optional) | `boot` — shown between the lines at power-on; `gameboot` — the caption under the game boot logo, which only appears when this file exists |

Anything missing falls back to a plain stand-in: the system font, an outlined square icon, or silence.


## Building

Every push to `main` builds the APK with GitHub Actions. Download it from the run's **xmb-launcher-apk** artifact.

To build locally, you need the Android SDK with NDK 27.2.12479018 and CMake 3.22.1:

```
./gradlew assembleRelease
```

## License

This project is licensed under the GNU General Public License v3.0; see [`LICENSE`](LICENSE).

## Third-party code

`app/src/main/cpp/at3_standalone` is PPSSPP's standalone extraction of FFmpeg's ATRAC3/ATRAC3+ decoders, under the GNU Lesser General Public License 2.1 or later; see [`COPYING.LGPL`](app/src/main/cpp/at3_standalone/COPYING.LGPL).

## Trademarks

PSP, PlayStation and XrossMediaBar are trademarks of Sony Interactive Entertainment. This project is not affiliated with or endorsed by Sony, and includes none of its software, fonts, artwork or sounds.
