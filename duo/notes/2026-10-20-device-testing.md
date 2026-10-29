# 2026-10-20: overnight work tested on the Thor

Tested by launching games by intent and driving the bottom screen from adb (`input -d 4 tap/swipe`, `sendevent` for
the two-finger menu). Keys and top-screen touches cannot be injected into the games this way (no inventory/PDA tests).

## Results

| Area | Result |
|---|---|
| Stardew adapter 0.2 | Compiled first try against the real game (1.6.15, SMAPI 4.5.2); every patched member verified by reflection. Title, load menu, journal, dialogue on the bottom; taps work. UI editor: list, select, Top (clock drawn on the top screen), scale, drag, reset. |
| Slime Rancher, plugin 0.3 | No regression: world on top, HUD below, crosshair on top, touch, bridge. |
| Subnautica, plugin 0.3 | Menu and save load by touch; world + hand reticle + prompts on top, HUD below. 46 fps. |
| Dead Cells region view | Community preset applied from the app; region editor pans live; taps click through (menus driven from the bottom screen). Minimap is bottom-right, not top-right: measured region published (package 0.2.0). |
| Mod refresh after app updates | New: verified both paths (an app-installed plugin is replaced, no backup; a hand-changed file is left alone). |

## Bugs found and fixed

- **Stardew menus laid out for 1920 px after a window event** (load menu cut off): our resize postfix passed the world
  viewport to `gameWindowSizeChanged`; now UI-viewport bounds. Wine reports a same-size resize now and then (around
  taps): those are skipped entirely (they reallocated every full-window render target, a hitch).
- **Clicks on moved Stardew HUD elements did nothing**: Harmony does not write `__args` changes back to value
  arguments; the prefixes now take `ref int x, ref int y`. (Rule for every adapter: change arguments by `ref`.)
- **Toolbar box wrong in the editor**: `Toolbar` hides `yPositionOnScreen` with its own field (bottom edge + 8).
- **Editor map covered the clock**: the top-screen map now takes the top corner covering less UI, and taps under it
  fall through to bottom elements.
- Harmless warnings: inherited menu methods are no longer "patched" (Harmony refuses them), quirk types are looked up
  without HarmonyX's "Could not find type" warnings, "Line up screens" hidden in region view.
- **Installed mods never updated with the app** (games kept plugin 0.2): `DuoModInstaller.refreshIfUpdated` at session
  start, once per APK install (`apkStamp` in the record).

## Performance (Slime Rancher, measured)

Plugin 0.3.1 times itself (`this plugin x ms per frame` in the `Frames` lines, logged with the split off too):

- Main menu: 60 fps locked, plugin 0.08 ms/frame. In game: ~35 fps, p95 ~37 ms, plugin **0.11 ms/frame (0.4%)**.
- GPU only ~50% busy at 615 MHz; the game's main thread ~80% of a core and four `EnlightenWorker` (realtime GI)
  threads ~60% each: **CPU-bound by the game itself under Box64**. The plugin is not the slowdown, and the 75% split
  resolution will not help much (it relieves the GPU).
- Still open: the same spot in game without the split, for a true A/B (injected touches don't reach the top screen,
  so this needs a person: turn Dual screen off, play a minute, read the `Frames (no split)` lines).
  Candidates if it is slower with the split: DXVK present/compositing of the tall swapchain, or Enlighten updating
  for the larger window. Unity settings (Enlighten off, shadows) may be the real lever on this device.

## Other observations

- Stardew died at "Waiting for game to launch" on the **first launch after each APK install** (2 of 2), never on
  later launches (0 of 5). Not our mod (it dies before SMAPI reads manifests). Probably GameNative's post-update setup;
  retry works. Watch it.
- Subnautica's HUD is small on the 3.92" screen; a bundled UI preset with larger scales would help.
- Stardew's toolbar overlaps the clock on the narrow bottom screen by default; moving the clock fixes it (could ship
  as the Stardew preset).

## Not tested yet (needs a person)

Stardew inventory drag and drop, Slime Rancher at 75%, Hollow Knight / Silksong / Risk of Rain 2 (not installed on
the Thor), Isaac region, sharing a layout, ratings.

## Slime Rancher default layout, open bug

- The demo layout (`duo/branding/slime-rancher-demo-layout.json`) is now the bundled preset
  (`assets/duo/mods/slime-rancher/ui-layout.json`), with a dark colour background instead of the private image.
  Users with their own `files/duo/ui/STEAM_433340.json` keep it.
- Open: after many rules were applied live from the PC editor, some elements that had no rule before (day/time,
  meters) were drawn twice, the original beside the scaled copy. Clean after a game restart. Look at how `UiRouter`
  applies a first rule to an element live.
