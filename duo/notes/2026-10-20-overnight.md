# Overnight 2026-10-19/20: community steps 3–5, prototypes, region view

Nothing below was run on the Thor (it was unplugged). Everything Android-side builds, lints and passes unit tests;
the Unity plugin compiles against Unity 2019 (Slime Rancher, Subnautica), 2021 (Risk of Rain 2) and 6 (Hollow Knight);
**the Stardew mod has not been compiled** (its assemblies are on the Thor).

## Test plan for tomorrow, in order

1. **Install the new APK** and **rebuild the Stardew mod**: pull `Stardew Valley.dll`, `MonoGame.Framework.dll`,
   `xTile.dll`, `StardewValley.GameData.dll`, `BmFont.dll` + SMAPI from the device (`run-as`), then
   `dotnet build -c Release -p:GamePath=...` in `duo/mods-src/stardew`, fix compile errors (written against Stardew
   1.6 / MonoGame 3.8 from memory), copy into `assets/duo/mods/stardew/mod/`, rebuild the APK.
2. **Slime Rancher regression** (plugin 0.3 rewrote camera placement and scans): crosshair on top, HUD below, native
   touch, layout editor. Then the **split resolution** at 75% (settings tab; set the in-game resolution to 1440x1620
   once) and compare the `Frames:` lines in `BepInEx/LogOutput.log` (logged every 30 s) at 100% vs 75%.
3. **Hollow Knight / Silksong** (Community → Install… on the game page): world on top, HUD/inventory/menus below;
   check the log for `Camera 'HudCamera' ... -> bottom screen` and `Game quirk patched: ForceCameraAspect...`. Then the
   layout editor on the HUD (sprite elements: masks, soul orb, geo).
4. **Risk of Rain 2** (Community → Install…, 75% resolution): HUD below, crosshair on top.
5. **Subnautica** (Dual screen switch; restart the game for plugin 0.3): UI camera to the bottom screen, PDA and
   inventory below, drag items with a finger.
6. **Isaac / Dead Cells** (Community → Apply the region-view preset, start the game): minimap on the bottom screen;
   two fingers → Choose bottom view → drag/pinch to fit it, Done. Then publish the measured regions (edit the
   presets' `region`).
7. **Community flows**: Share your layout (opens the GitHub form; the bot was tested end to end with a throwaway
   issue), ratings show 👍/👎 counts, Install/Remove a mod restores the game exactly.

## What was built

- **Community step 3 (share)**: issue form + bot (`share-layout.yml`, `tools/issue_to_package.py`) in the community repo;
  "Share your layout" in the app's Community sheet (`ShareLayout.kt`).
- **Step 4 (code mods)**: `CommunityModInstaller` (download + sha256 first, then `DuoModInstaller.enableDownloaded`, same
  backup/undo record as bundled mods; `install.sharedAssets` reuses the APK's BepInEx and plugin), consent dialog.
  `DuoModInstaller.disable(appId)` now reads everything it needs from the record.
- **Step 5 (ratings)**: one discussion per package (CI creates it), reactions folded into the index nightly; the app
  ranks by device match, then the Wilson lower bound, then date.
- **Unity plugin 0.3**: `CameraPlacer` (per-frame, write-on-change placement; tk2d support; game UI cameras by layer,
  name or quirk; orthographic widening for the bottom screen's shape), camera roots (sprite HUD elements), passive
  canvases (world-space or bound to the game's UI camera), event-driven scans (CanvasScaler.OnEnable, sceneLoaded,
  camera count) with a 2 s fallback, frame stats, Team Cherry quirk.
- **Split resolution** (100/75/50%): resizes the container and the mod's undo record on save.
- **Region view**: new split mode for any game, no mod; `RegionView` maths (unit-tested), `RegionPanel` pan/zoom editor;
  the renderer's second output re-crops in place (no swapchain rebuild).
- **Stardew adapter 0.2**: bridge, touch as the game's mouse (Mouse.GetState postfix), drag and drop, HUD rules with
  SpriteBatch transforms and inverse-mapped clicks, top-screen pass, element list.

## Slime Rancher's performance loss: causes, best guess first

1. **The tall window doubles full-screen work**: the native split renders a 1920x2160 window (4.1 MP, 2× a 1080p
   frame). The 3D view itself stays 1920x1080, but clears, full-screen image effects (Unity allocates their temporary
   targets at the window size), DXVK's present and the compositor all handle twice the pixels. The 75% split
   resolution brings it to 1440x1620 (2.3 MP, about one 1080p frame); the renderer scales it up for free.
2. **Scans**: the 0.2 plugin ran `FindObjectsOfType<Canvas>` and `Camera.allCameras` (allocating) twice a second. Now
   scans are event-driven with a 2 s fallback and cameras are enumerated without allocations.
3. Fixed earlier (0.2): the CanvasScaler patch invoked a reflection method every frame per canvas.
Verify with the `Frames:` log lines before guessing further.

## Not built: designs

- **Slay the Spire 2 (Godot 4 .NET, #2)**: the game loads C# + PCK mods from `mods/` (community tooling: Harmony,
  manifests). A Godot adapter would: make the window tall; render the game's world into the top part (root viewport
  content scale and a SubViewport, or Camera2D offsets), move `CanvasLayer` UI (hand of cards, map, relics) into the
  bottom part; ids are node paths (like Unity's); touches through `Input.ParseInputEvent(InputEventScreenTouch)`,
  which Godot's GUI handles natively (drag and drop of cards for free). Needs the game installed on the PC to study
  its scene tree (GDRE Tools for the PCK) and build against its assemblies. Brotato (Godot 3, official Godot Mod
  Loader) would share most of it.
- **Celeste (FNA, Everest)**: Everest patches Celeste.exe with its MiniInstaller (needs .NET under Wine); the game has
  almost no HUD. Low value for the effort; skip unless asked.
- **Isaac full split**: the vanilla Lua API cannot move the room in a tall window (room centred, HUD at the window's
  corners). REPENTOGON (Windows, C++) exposes rendering and the HUD; a real split would build on it. Region view is the
  practical answer meanwhile.
