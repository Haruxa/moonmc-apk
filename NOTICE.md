# Third-party notices — MoonMC

MoonMC is a rebranded fork. Almost all of the engine and the Android port are other people's
work, and this file says whose.

## Horizon — the engine

<https://github.com/horizon-rust/horizon>

MIT License, Copyright (c) 2026 Ludovic Marie and the Horizon contributors.

MoonMC builds Horizon at a pinned commit (`native_pin`) and applies the Android port on top of it.

## MargyMC — the original Android port

<https://git.narez.xyz/narezany/MargyMC>

MIT License, by **narezany** — the original author of the Android port (`java/`,
`crates/horizon-android`, the `/sdcard` game folder layout and the launcher-side JRE) that MoonMC
is based on. MoonMC keeps the MIT license and adds the rebranding patches in `ci/`.

## Google Material Design Icons

<https://github.com/google/material-design-icons>

Apache License 2.0, Copyright (c) Google LLC.

The Moon launcher uses these icon sets (through `androidx.compose.material.icons`) and follows the
Material 3 Expressive design guidance for its shapes, motion and the first-run screen.

## Other components

- **wgpu-hal** 30.0.1 (gfx-rs) — MIT or Apache-2.0. Ships inside Horizon; see the upstream
  `third_party/wgpu-hal` folder for both license texts.
- **Pixelify Sans** (The Pixelify Sans Project Authors) — SIL Open Font License 1.1. Horizon's
  in-game theme font.
- **Pixelarticons** (Gerrit Halfmann) — MIT. Horizon's pixel icon set.
- **ZalithLauncher 2 JRE** — the Android Java runtime (`jre-25`, GPL-2.0 **with the Classpath
  Exception**) that the port runs the game's Java parts with. It is downloaded at build time from
  the upstream repository and is **not** part of this source repository.
- **AndroidX / Jetpack Compose, Kotlin** — Apache License 2.0.

Upstream keeps its own notices in `THIRD_PARTY_NOTICES.md` of the Horizon tree — MoonMC does not
remove or modify them.

## Minecraft

Minecraft: Java Edition and Minecraft: Bedrock Edition are trademarks of Mojang Studios.
MoonMC ships **no** Minecraft files: no jars, no libraries, no assets. The game uses the copy that
is already on the player's phone (`/sdcard/MoonMC/minecraft`) and asks for it on first start.
MoonMC is not affiliated with, endorsed by or sponsored by Mojang Studios or Microsoft.
