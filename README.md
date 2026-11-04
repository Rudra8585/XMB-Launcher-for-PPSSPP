# XMB Launcher for PPSSPP

An Android launcher that recreates the PSP's XrossMediaBar and starts games in PPSSPP. Built for the Retroid Pocket 5 (1920×1080, built-in controls).

## Install

1. Install [PPSSPP](https://www.ppsspp.org/download/) (free or Gold).
2. Download the APK from the [latest release](../../releases/latest) and install it.
3. Open **XMB Launcher**, go to **Settings › Game Folder Settings › Add Folder** and pick the folder with your `.iso` / `.cso` games.
4. Optional: make it your home screen (see below), and import your own look (see **Asset folder**).

Updates install over the previous version and keep your settings. [Obtainium](https://github.com/ImranR98/Obtainium) can track this repo's releases for you.

## What it does

- **XMB**: Settings, Photo, Music, Video, Game, and Network categories, with a built-in original look (icons, font and sounds) that you can replace with your own (see **Asset folder**).
- **Wave background**: rendered live in an OpenGL shader, not a video. The *Original* theme changes color each month like the PSP, and the background dims at night. Fixed themes: Blue, Baby Blue, Lime Green, Violet, White (colors sampled from the reference videos).
- **Game › PSP Games**: lists `.iso`, `.cso` and `EBOOT.PBP` games the way the PSP does. On hover:
  - the game's `ICON0.PNG` icon is shown;
  - `PIC1.PNG` (backdrop) and `PIC0.PNG` (overlay) fade in;
  - `SND0.AT3` music plays, using the ATRAC3/ATRAC3+ decoder from PPSSPP;
  - the `ICON1.PMF` movie plays inside the icon.
- **Animations**: the power-on sequence plays at launch, and the game boot animation plays before handing off to PPSSPP.
- **PPSSPP handoff**: the selected game opens in PPSSPP (free or Gold). If PPSSPP is missing, a blocking prompt links to the Play Store.
- **Game › Android Apps**: the Android apps you pick (under **Choose Apps** in that list), shown as round, glossy bubbles and launched from the XMB.
- **Battery**: while charging, the battery bars fill up in a loop; with a fast charger (15 W or more, where the device reports it) a small lightning bolt appears beside it.
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

The app has its own original look: icons from [Material Symbols](https://fonts.google.com/icons), the [M PLUS 1p](https://fonts.google.com/specimen/M+PLUS+1p) font and synthesized sounds. To use a different font, icons or sounds, put your own files in a folder and import it from **Settings › XMB Asset Folder**. They're copied into the app, so the folder can be deleted afterwards, and anything you don't provide keeps the built-in version. **Settings › Theme Settings › Icons, Font and Sounds** switches between the imported set (*XMB Assets*) and the built-in one (*Original*) at any time. Subfolders are searched, and files are matched by name:

| What | File names |
| --- | --- |
| Font | any `.ttf` or `.otf` (a name containing `rodin` wins if there are several) |
| Icons (`.png`) | `cat_settings`, `cat_photo`, `cat_music`, `cat_video`, `cat_game`, `cat_network`, `memstick`, `memstick_fill`, `theme`, `theme_wallpaper`, `sound`, `system_settings`, `game_sharing`, `network_update`, `psp`, `umd`, `remote_play`, `date_time`, `folder_music`, `folder_photo`, `browser`, `rss`, `home`, `android_apps` |
| Sounds (`.ogg`, `.flac`, `.wav` or `.mp3`) | `up`, `down` (cursor; also confirm and back), `notice`, `notice_back` (option menus), `boot` (power-on), `launch` (game boot), `bgm` (optional background music) |
| Text (`.txt`, optional) | `boot` — shown between the lines at power-on; `gameboot` — the caption under the game boot logo, which only appears when this file exists |

Imported `cat_settings` and `system_settings` icons trade places: the Settings category shows the system-settings icon, and System Settings and Android Settings show the category icon.

## Building

Every push to `main` builds the APK with GitHub Actions. Download it from the run's **xmb-launcher-apk** artifact.

Pushing a tag such as `v1.0.0` builds the APK signed with the release key and publishes it as a GitHub Release. The key is read from the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`; builds without them (forks, pull requests) are signed with the debug key.

To build locally, you need the Android SDK with NDK 27.2.12479018 and CMake 3.22.1. For a release-signed local build, add a git-ignored `keystore.properties` with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

```
./gradlew assembleRelease
```

## License

This project is licensed under the GNU General Public License v3.0; see [`LICENSE`](LICENSE).

## Third-party code

- `app/src/main/cpp/at3_standalone` is PPSSPP's standalone extraction of FFmpeg's ATRAC3/ATRAC3+ decoders, under the GNU Lesser General Public License 2.1 or later; see [`COPYING.LGPL`](app/src/main/cpp/at3_standalone/COPYING.LGPL).
- The built-in icons (`app/src/main/res/drawable/xmb_*.xml`) are [Material Symbols](https://github.com/google/material-design-icons) by Google, under the Apache License 2.0; see [`licenses/Apache-2.0-material-symbols.txt`](licenses/Apache-2.0-material-symbols.txt).
- The built-in font (`app/src/main/res/font/xmb_default.ttf`) is M PLUS 1p Medium by the M+ Project, under the SIL Open Font License 1.1; see [`licenses/OFL-MPLUS1p.txt`](licenses/OFL-MPLUS1p.txt).
- The built-in sounds are synthesized by [`tools/make_sounds.py`](tools/make_sounds.py) and are part of this project.

## Trademarks

PSP, PlayStation and XrossMediaBar are trademarks of Sony Interactive Entertainment. This project is not affiliated with or endorsed by Sony, and includes none of its software, fonts, artwork or sounds.
