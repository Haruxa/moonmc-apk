# MoonMC — build pipeline

GitHub Actions that turns the [Horizon](https://github.com/horizon-rust/horizon) engine plus the
Android port into the **MoonMC** game APK, with native `.hnmod` mods compiled in.

MoonMC is a rebrand of the Android port [MargyMC](https://git.narez.xyz/narezany/MargyMC) with a
new name, package (`dev.moonmc.moonmc`), icons and a Material 3 Expressive first-run screen.

**This repository contains no Minecraft files.** No jars, no libraries, no assets, no server jar.
The build either reuses the exporter classes produced earlier (`exporters` artifact) or works from
an archive you supply yourself in this repository's Release (`mc_asset` input). The game reads the
player's own Minecraft from the phone (`/sdcard/MoonMC/minecraft`) at first start — nothing is
redistributed here.

## What is here

```
.github/workflows/apk.yml   the only workflow: build the APK
ci/build-apk-modded.sh      assembly: cargo → libhorizon.so → javac/d8 → aapt2 → apk + signing
ci/patch-game-home.py       game folder on /sdcard/MoonMC, rebranding of paths and labels
ci/patch-branding.py        label MoonMC, adaptive + monochrome icons, first-run screen
ci/patch-package.py         applicationId dev.moonmc.moonmc (JNI symbols included)
ci/patch-touch.py           on-screen controls from JSON profiles (touch/)
ci/touch_layout.rs          the Rust side of the touch profiles
ci/branding/**              generated icons (moon-launcher/tools/gen_icons.py --layout flat)
ci/splash/MoonSplash.java   the first-run / preparation screen (drawn in code)
ci/bundles/*.hnmod          native mods baked into the APK (selectable per build)
```

## Build

Actions → **apk** → *Run workflow*:

| input | meaning |
| --- | --- |
| `profile` | `fast` (default) or `release` cargo profile |
| `mods` | build with the code mods feature (`--features mods`) |
| `native_pin` | commit of `horizon-rust/horizon` the native mods are bound to (empty = plain MargyMC) |
| `mixins_rev` | commit of `horizon-rust/horizon-mixins` |
| `native_mods` | which `ci/bundles/*.hnmod` to compile in, comma separated (`none` = without native mods) |
| `mod_urls` | alternative: direct `.hnmod` URLs, space separated |
| `mc_asset` | name of your own Minecraft archive in the Release (optional) |
| `home_on_sdcard` | keep the game folder at `/sdcard/MoonMC/horizon` |

The result is the `horizon-apk` artifact (`horizon.apk`, signed with `CI_KEYSTORE_B64`).
The Moon launcher drives the same workflow from its **Build** tab.

## License

MIT — see [LICENSE](LICENSE). Third-party components and credits: [NOTICE.md](NOTICE.md).
Minecraft is a trademark of Mojang Studios; this project is not affiliated with or endorsed by
Mojang or Microsoft.
