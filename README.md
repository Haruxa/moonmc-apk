<p align="center">
  <img src="docs/logo.png" width="112" alt="MoonMC">
</p>

<h1 align="center">MoonMC</h1>

<p align="center"><em>An Android client for Minecraft: Java Edition — Horizon engine, on-screen touch controls, Material 3 UI</em></p>

<p align="center">
  <a href="https://github.com/Haruxa/moonmc-apk/actions/workflows/apk-gradle.yml"><img src="https://github.com/Haruxa/moonmc-apk/actions/workflows/apk-gradle.yml/badge.svg" alt="apk-gradle"></a>
  <a href="https://github.com/Haruxa/moonmc-apk/releases"><img src="https://img.shields.io/badge/download-Releases-2EA44F?style=flat-square&logo=github&logoColor=white" alt="Releases"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-9BE8BE?style=flat-square" alt="GPL-3.0"></a>
  <img src="https://img.shields.io/badge/package-dev.haruxa.moonmc-1F6FEB?style=flat-square" alt="package">
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 10+">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-555?style=flat-square" alt="arm64-v8a">
  <img src="https://img.shields.io/badge/Minecraft%20files-none-critical?style=flat-square" alt="no Minecraft files">
  <a href="https://github.com/Haruxa/moonmc-apk/stargazers"><img src="https://img.shields.io/github/stars/Haruxa/moonmc-apk?style=flat-square&color=9BE8BE" alt="stars"></a>
</p>

> **Status: early public Beta.** MoonMC is a game client, not a launcher. It runs your own copy of
> Minecraft: Java Edition on Android 10+ (arm64-v8a) through the Horizon engine. Expect rough
> edges: first-run preparation can fail on unusual setups, some screens are still being polished,
> and behaviour may change between builds. Please report problems instead of assuming they are
> intended.

MoonMC is the Android game client/project built in this repository: the Horizon engine plus the
MargyMC Android port, rebranded and extended with a Kotlin/Compose in-game layer (touch controls,
profiles, macros, IME). It is *not* a Minecraft launcher, downloader or account service: there is
no catalogue, no mod store and no bundled game — MoonMC only runs the game files you already own.
The separate [moon-launcher](https://github.com/Haruxa/moon-launcher) project is an editor/launcher
around this workflow and is not required to play.

## Features

- **Horizon engine** — native Rust core (`libhorizon.so`, arm64-v8a) built from a pinned upstream
  commit, with the Android port applied on top.
- **Touch controls** based on [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2)
  (LayerController / InputMap): buttons, joysticks, text widgets, layers, styles and visibility
  rules.
- **Control profile editor** — create, duplicate and tune profiles in-game; profiles live in
  `/sdcard/MoonMC/controls` as plain JSON.
- **Zalith / Pojav profile import** — import an existing `.json` layout or a launcher `.zip`
  export; files are read only, never extracted to disk.
- **Local accounts** — offline profiles stored in `/sdcard/MoonMC/accounts.json`; no login server,
  no credentials are uploaded anywhere.
- **IME keyboard** — a real Android keyboard layer for chat, signs and the server-address field,
  with Enter/Done handling and correct HUD restore after the keyboard closes.
- **Macros** — recorded key/click sequences and text steps in `/sdcard/MoonMC/macros.json`, edited
  from the in-game panel.
- **Material 3 UI** — Compose panels, editor and first-run screen follow Material 3
  (Expressive-style shapes and motion).

## Download

Install only the APK from **[GitHub Releases](https://github.com/Haruxa/moonmc-apk/releases)**:

1. Open the latest release.
2. Download the asset named **`MoonMC.apk`**.
3. Open it on the device and allow installation from this source when Android asks.

Do **not** download Actions artifacts (a ZIP of `horizon-apk-gradle` / `horizon-apk`) — those are
build outputs for maintainers, not installable packages. There is no Play Store listing and no
third-party mirror. Release builds are signed with the project keystore; if Android refuses to
update and offers to uninstall, the installed build came from a different signing key — back up
`/sdcard/MoonMC` before uninstalling.

## Requirements

| | |
| --- | --- |
| Device | Android 10 or newer, **arm64-v8a** (`arm64-v8a` only — no armeabi-v7a, no x86) |
| Game files | Your own **Minecraft: Java Edition 26.3** — this is the only supported version |
| Permissions | "All files access" (`MANAGE_EXTERNAL_STORAGE`), required for `/sdcard/MoonMC` |
| Storage | A few GB free for the game files, resources and the unpacked JRE |

MoonMC does not include, download or patch Minecraft. You must already own a legal copy of
Minecraft: Java Edition 26.3.

## Setup

1. **Copy your own Minecraft 26.3 folder** to `/sdcard/MoonMC/minecraft/`. It must contain:

   ```
   /sdcard/MoonMC/minecraft/
   ├── versions/26.3/26.3.json
   ├── versions/26.3/26.3.jar
   ├── libraries/
   └── assets/
       ├── indexes/
       └── objects/
   ```

   `server.jar` is also needed for the first resource preparation — keep it in the same folder.

2. **Grant all-files access** to MoonMC when prompted (Android 11+ opens *Special app access →
   All files access*). Without it the client cannot read `/sdcard/MoonMC` and setup will fail.

3. **Launch once and wait.** The first start prepares resources from your Minecraft folder and
   unpacks the Java runtime; this takes a few minutes and shows progress on screen. Later starts
   are offline and much faster.

4. **Do not put a JRE on `/sdcard`.** The client installs and unpacks its own JRE into app-private
   storage; a `jre/` folder on the SD card is ignored and a partially written one only causes
   confusing "java not found" states.

### Where MoonMC keeps things

| Path | Contents |
| --- | --- |
| `/sdcard/MoonMC/minecraft/` | your Minecraft 26.3 files (read by the first preparation) |
| `/sdcard/MoonMC/horizon/` | the game home: worlds, options, mods, `horizon-resources.json` |
| `/sdcard/MoonMC/controls/` | control profiles (`default` is built in) |
| `/sdcard/MoonMC/accounts.json` | local (offline) accounts |
| `/sdcard/MoonMC/macros.json` | macros |
| `/sdcard/MoonMC/logs/` | reports: `moonmc-setup.txt`, `moonmc-jre.txt`, IME and layer logs |

If a report cannot be written to `/sdcard/MoonMC/logs/`, a fallback copy lands next to the game
home (`/sdcard/MoonMC/horizon/`) or in the app's own files directory. Attach the files from
`/sdcard/MoonMC/logs/` when reporting an issue.

## Troubleshooting & feedback

Bugs and feature requests go to **[GitHub Issues](https://github.com/Haruxa/moonmc-apk/issues)**.
A report that can be reproduced and read is a report that gets fixed:

- device model and RAM;
- Android version and ROM (stock / custom);
- exact steps you took, in order;
- what you expected and what happened;
- a screenshot or screen recording where possible;
- logs from `/sdcard/MoonMC/logs/` (`moonmc-setup.txt` and `moonmc-jre.txt` cover most start-up
  failures).

Common causes of a failed first start: missing or renamed files under
`/sdcard/MoonMC/minecraft/`, a version other than 26.3, no all-files access, or too little free
space. The `moonmc-setup.txt` report names the step that failed.

## Repository layout

```
.github/workflows/apk.yml          native build: cargo → libhorizon.so → javac/d8 → aapt2 → APK
.github/workflows/apk-gradle.yml   build of the Kotlin/Compose in-game layer
ci/build-apk-modded.sh             assembly script used by apk.yml
ci/patch-branding.py               label, icons, first-run wiring, Android port patches
ci/patch-game-home.py              game folder /sdcard/MoonMC, reports, JRE checks
ci/patch-package.py                applicationId dev.haruxa.moonmc (JNI symbols included)
ci/patch-touch.py, touch_layout.rs native side of the touch profiles
ci/patch-ime.py, moon_ime.rs       IME queue between the keyboard and the engine
ci/port/port.tgz                   snapshot of the Android port applied to the engine
ci/bundles/*.hnmod                 native mods available to the build
game/app/                          Kotlin/Compose layer: overlay, editor, import, macros, IME HUD
game/layercontroller/              LayerController (Zalith Launcher 2, GPL-3.0)
game/inputmap/                     InputMap key mapping (Zalith Launcher 2, GPL-3.0)
docs/                              logo used by this page
```

## Building from source

Maintainers build with Actions → **apk-gradle** → *Run workflow* (Kotlin/Compose layer plus the
native tree) or **apk** for the native-only pipeline. The runs need the keystore secrets
(`CI_KEYSTORE_B64`, `CI_KEYSTORE_PASS`) and, for resource preparation, a Minecraft 26.3 archive.
The result is a CI artifact, not a public download — releases are published from it manually.

## Credits

MoonMC is a rebranded fork built on other people's work.

- **[Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2)** — touch controls,
  **LayerController** and **InputMap**, by **MovTery** and the Zalith Launcher contributors.
  GPL-3.0. MoonMC's editor, import and in-game wiring are built on top of it.
- **[Horizon](https://github.com/horizon-rust/horizon)** — the engine, MIT © 2026 Ludovic Marie and
  the Horizon contributors. Built at a pinned commit; upstream notices are kept.
- **[MargyMC](https://git.narez.xyz/narezany/MargyMC)** — the original Android port by **narezany**
  that MoonMC is a rebrand of.
- **[Material Design Icons](https://github.com/google/material-design-icons)** — Apache-2.0, Google
  LLC.
- **ZalithLauncher 2 JRE** — the Android Java runtime (`jre-25`, GPL-2.0 with the Classpath
  Exception), unpacked into app-private storage, never committed and never placed on `/sdcard`.

Third-party licenses and the full component list: [NOTICE.md](NOTICE.md).

**Minecraft files are not distributed with MoonMC.** The repository, releases and CI artifacts
contain no jars, libraries, assets or server jar. Minecraft: Java Edition and Minecraft: Bedrock
Edition are trademarks of Mojang Studios; MoonMC is not affiliated with, endorsed by or sponsored
by Mojang Studios or Microsoft.

## License

[GPL-3.0](LICENSE) for MoonMC's own code. Third-party components remain under their respective
licenses, listed in [NOTICE.md](NOTICE.md).
