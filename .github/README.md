<div align="center">

# GameNative Duo

**Your PC games, across both screens of your dual-screen Android handheld.**

A fork of [GameNative](https://github.com/utkarshdalal/GameNative) that turns the second screen into part of the game:
the world on top, the HUD, map and menus underneath — touchable, like a DS.

<!-- SCREENSHOT: hero photo of the AYN Thor running a game across both screens (~1600 px wide).
     Replace the line below with: <img src="docs-assets/hero.jpg" alt="GameNative Duo on the AYN Thor" width="100%"> -->
📷 *Photo coming soon: GameNative Duo on the AYN Thor*

</div>

---

## What it is

[GameNative](https://github.com/utkarshdalal/GameNative) runs the Windows games you own on Steam, Epic, GOG and Amazon
directly on Android (Wine, Box64/FEX, DXVK). It uses one screen.

**GameNative Duo** adds everything a dual-screen handheld needs. The game renders one tall picture that spans both
screens, small per-game mods move its interface to the bottom screen, and the bottom screen becomes a real touchscreen
for that interface. You decide where every piece of the UI goes, and the game keeps running at full speed while you do.

It installs **next to** official GameNative (its own app id, `app.gamenative.duo`), so you can keep both.

> Early and personal: built and tested on an AYN Thor. Expect rough edges; issues and test reports are welcome.

## Features

### One game, two screens
The game draws a single tall frame; the top part goes to the top screen and the bottom part to the bottom screen, from
the same GPU frame with no copies. Line the two screens up across the hinge (gap, pixel density, offset) once per device
and the picture reads as one.

<!-- SCREENSHOT: game world continuing across the hinge (e.g. Stardew Valley) -->
> 📷 *Screenshot coming soon: Screen split across the hinge*

### UI on the bottom screen, game on top
Per-game mods move the HUD, menus and inventory to the bottom screen and leave the top screen to the game. Things you
aim with, like crosshairs, stay on top automatically.

<!-- SCREENSHOT: top screen clean gameplay + bottom screen HUD (e.g. Slime Rancher) -->
> 📷 *Screenshot coming soon: HUD on the bottom screen*

### Native touch
The bottom screen behaves like a touchscreen built into the game, not a touchpad: no cursor, taps land exactly under
your finger, drag and drop and multi-touch work, and the game never flips its button prompts to keyboard and mouse or
mistakes a tap for a gameplay click.

### Make the layout yours
Hold two fingers on the bottom screen to open the layout editor while you play. Tap any part of the interface, then:

- send it to the **bottom** screen, the **top** screen, or **hide** it;
- **drag** it anywhere and **pinch** to resize it;
- find hidden or tiny elements in the **list**, and **reset** everything in one tap.

Changes show up instantly and are saved per game. Layouts are stored as fractions of the screen, not pixels, so the
same layout fits other devices, and the format is ready for shared community presets.

<!-- SCREENSHOT: layout editor open on the bottom screen, an element selected -->
> 📷 *Screenshot coming soon: Layout editor*

### One-tap mods
Games with a dual-screen mod get a **Dual screen** switch next to Play. It installs the mod, sets the right screen
size and restores everything when you switch it off. They're also gathered in their own **Dual screen** tab.

## Games

| Game | Engine | Status |
|---|---|---|
| Balatro | LÖVE (Lovely) | Works |
| Stardew Valley | MonoGame (SMAPI) | Works: world across both screens, UI below |
| Slime Rancher | Unity (BepInEx) | Works: HUD below, crosshair on top, native touch, layout editor |
| Subnautica | Unity (BepInEx) | In testing |

The Unity support is generic: most Unity games built with the Mono backend and Unity's standard UI (uGUI) can be added
with a single catalog entry, and quirks are handled per game when needed.

<!-- SCREENSHOT GRID: one shot per game above -->
| | |
|:---:|:---:|
| 📷 *Balatro* | 📷 *Stardew Valley* |
| 📷 *Slime Rancher* | 📷 *Subnautica* |

## Devices

| Device | Status |
|---|---|
| AYN Thor | Supported (top 1920x1080, bottom 1240x1080) |
| AYANEO Pocket DS, Retroid Dual Screen, others | Planned: device profiles keep "top" and "bottom" independent of how Android numbers the displays |

## Using it

1. Install the APK (it sits next to official GameNative) and sign in as you would in GameNative.
2. Install a game from the **Dual screen** tab and flip its **Dual screen** switch.
3. Press Play. For Unity games, set the in-game resolution to the tall size (1920x2160 on the Thor) once.
4. In game, hold **two fingers** on the bottom screen for the menu: **Edit UI layout** or **Line up screens**.

Container settings have a **GameNative Duo** tab (screen split, native touch, bottom-screen panel).

## For developers: a dual-screen devkit

The goal is that anyone can make their game dual-screen, whatever the engine, without touching the app. An in-game
mod (an *engine adapter*) and the app share a small, engine-neutral contract:

- **`layout.json`**: where each screen sits inside the game window.
- **`ui.json`**: the user's layout (element → screen, size, offset). The same format serves presets.
- **A local bridge** (UDP on 127.0.0.1, 16-byte touch packets): native touch, live layout updates, the element list
  for the editor. Idle cost is zero.

Implement those three things for an engine and the editor, storage, touch and presets work unchanged. Spec:
[`duo/docs/engine-adapter-contract.md`](https://github.com/nono5822/GameNativeDuo/blob/duo/slime-rancher/duo/docs/engine-adapter-contract.md). The Unity adapter
([`duo/mods-src/unity`](https://github.com/nono5822/GameNativeDuo/tree/duo/slime-rancher/duo/mods-src/unity)) is the reference implementation.

**Design rules.** The CPU is the bottleneck under emulation and the battery is small, so: no CPU copies of frames, no
polling, nothing drawn when nothing changes, and per-frame work only for the UI elements you actually changed.

**Roadmap.**
- More engine adapters: Godot, Unreal (UMG), Ren'Py, RPG Maker, Unity UI Toolkit.
- Community presets: a Git-based index of per-game layouts that apply automatically.
- An MCP server so AI agents can help build and test dual-screen mods (inspect the UI, move elements, screenshot both
  screens).
- Bottom-screen panels, region views (live crops of the game on the bottom screen), window routing.

**Build** (from the `devkit` branch, or a `duo/*` feature branch for the newest work):
```bash
./gradlew :app:assembleModernDebug     # APK in app/build/outputs/apk/modern/debug/
```
Developer notes, architecture and debugging tips: [`CLAUDE.md`](https://github.com/nono5822/GameNativeDuo/blob/duo/slime-rancher/CLAUDE.md) and [`duo/notes/`](https://github.com/nono5822/GameNativeDuo/tree/duo/slime-rancher/duo/notes).

## Staying close to GameNative

This fork tracks upstream closely: new code lives in its own package (`app.gamenative.duo`) and upstream files are only
touched with one-line hooks, so GameNative's improvements arrive here quickly. Fixes that help upstream too are offered
back.

## Credits and licence

- [GameNative](https://github.com/utkarshdalal/GameNative) and everyone behind it: this fork is built entirely on
  their work. Please support them.
- Mod loaders bundled unmodified, with their licences: [BepInEx](https://github.com/BepInEx/BepInEx) (MIT),
  [SMAPI](https://github.com/Pathoschild/SMAPI) (LGPL-3.0), [Lovely](https://github.com/ethangreen-dev/lovely-injector) (MIT).
- Licensed under the GPL-3.0, like GameNative.

Mods only change games you own, and nothing here bypasses or weakens DRM.
