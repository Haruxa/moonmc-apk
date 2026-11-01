<p align="center">
  <img src="docs/logo.png" width="112" alt="MoonMC">
</p>

<h1 align="center">MoonMC</h1>

<p align="center"><em>Build pipeline for the MoonMC Android client — Horizon engine, Material 3 Expressive first run, native <code>.hnmod</code> mods compiled in</em></p>

<p align="center">
  <a href="https://github.com/Haruxa/moonmc-apk/actions/workflows/apk.yml"><img src="https://github.com/Haruxa/moonmc-apk/actions/workflows/apk.yml/badge.svg" alt="apk"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-9BE8BE?style=flat-square" alt="GPL-3.0"></a>
  <img src="https://img.shields.io/badge/package-dev.moonmc.moonmc-1F6FEB?style=flat-square" alt="package">
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 10+">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-555?style=flat-square" alt="arm64-v8a">
  <img src="https://img.shields.io/badge/Minecraft%20files-none-critical?style=flat-square" alt="no Minecraft files">
  <a href="https://github.com/Haruxa/moonmc-apk/stargazers"><img src="https://img.shields.io/github/stars/Haruxa/moonmc-apk?style=flat-square&color=9BE8BE" alt="stars"></a>
</p>

MoonMC turns [Horizon](https://github.com/horizon-rust/horizon) plus the Android port
[MargyMC](https://git.narez.xyz/narezany/MargyMC) into a single APK: renamed, re‑iconed, with the
game folder on `/sdcard/MoonMC`, a Material 3 Expressive first‑run screen and any set of native
mods compiled into the engine. The launcher
[**Moon**](https://github.com/Haruxa/moon-launcher) drives this workflow from the phone.

**No Minecraft files are stored here.** No jars, no libraries, no assets, no server jar. The build
reuses the exporter classes from the previous run (artifact `exporters`) or works from an archive
you supply yourself in a Release. The game reads *your* copy of Minecraft from
`/sdcard/MoonMC/minecraft` on first start.

## What the build does

```
origin (Horizon @ native_pin)  →  cherry-pick the Android port  →  apply our patches
      ↓ cargo build -p horizon-android            (libhorizon.so, arm64-v8a)
      ↓ horizon-mixins apply --mods <selected>    (native .hnmod mods baked into the tree)
      ↓ javac + d8                                (HorizonActivity, MoonSplash)
      ↓ aapt2 + zip + apksigner                   →  horizon.apk
```

Artifacts: `horizon-apk` (the game) and `exporters` (reused by later builds).

## Repository layout

```
.github/workflows/apk.yml   the only workflow: build the APK
ci/build-apk-modded.sh      assembly: cargo → libhorizon.so → javac/d8 → aapt2 → apk + signing
ci/patch-game-home.py       game folder /sdcard/MoonMC, rebranding of paths, labels, title
ci/patch-branding.py        label MoonMC, adaptive + monochrome icons, first-run screen wiring
ci/patch-package.py         applicationId dev.moonmc.moonmc (JNI symbols included)
ci/patch-touch.py           on-screen controls from JSON profiles (touch/)
ci/touch_layout.rs          the Rust side of the touch profiles
ci/branding/**              generated icons (moon-launcher/tools/gen_icons.py --layout flat)
ci/splash/MoonSplash.java   the first-run screen: pixel moon, falling stars, shape loader
ci/bundles/*.hnmod          native mods available to the build list
docs/                       logo used by this page
```

## Build

Actions → **apk** → *Run workflow*:

| input | meaning |
| --- | --- |
| `profile` | `fast` (default) or `release` cargo profile |
| `mods` | build with the code-mods feature (`--features mods`) |
| `native_pin` | commit of `horizon-rust/horizon` the native mods are bound to (empty = plain MargyMC) |
| `mixins_rev` | commit of `horizon-rust/horizon-mixins` |
| `native_mods` | which `ci/bundles/*.hnmod` to compile in, comma separated (`none` = without native mods) |
| `mod_urls` | direct `.hnmod` URLs, space separated — may be combined with `native_mods` |
| `mc_asset` | name of your own Minecraft archive in the Release (optional) |
| `home_on_sdcard` | keep the game folder at `/sdcard/MoonMC/horizon` |

The APK is signed with the keystore from the `CI_KEYSTORE_B64` / `CI_KEYSTORE_PASS` secrets, so
builds upgrade each other in place.

## First start

The client asks where Minecraft is: put your own Minecraft: Java Edition 26.3 into
`/sdcard/MoonMC/minecraft` (`versions/26.3`, `libraries/`, `assets/`, `server.jar`). Resources are
prepared once from it; after that the game starts offline from `horizon-resources.json`.

## Ecosystem

- **[moon-launcher](https://github.com/Haruxa/moon-launcher)** — the launcher: catalogue, mods,
  build list, controls editor.

## Credits

- **[Horizon](https://github.com/horizon-rust/horizon)** — the engine, MIT © 2026 Ludovic Marie and
  the Horizon contributors.
- **[MargyMC](https://git.narez.xyz/narezany/MargyMC)** — the original Android port by **narezany**
  that MoonMC is a rebrand of.
- **[Material Design Icons](https://github.com/google/material-design-icons)** — Apache‑2.0,
  Google LLC; the design language of the launcher and the first‑run screen.
- Runtime JRE from **ZalithLauncher 2** (GPL‑2.0 with Classpath Exception), downloaded at build
  time and never committed.

Full list: [NOTICE.md](NOTICE.md).

## License

[GPL-3.0](LICENSE). Not affiliated with, endorsed by or sponsored by Mojang Studios or Microsoft.
Minecraft is a trademark of Mojang Studios.
