# 2026-10-27 Hollow Knight: live map on the bottom screen (WIP)

Branch `duo/hollow-knight` (from `duo/balatro`). Hollow Knight is now a **bundled** mod (`DuoModCatalog`:
`unityGame("hollow-knight", STEAM_367520)` + its own plugin), installed by the Dual screen switch.

## What exists

- Generic Unity split already did: world on top, masks/soul/geo, inventory, pause menu on the bottom (tested, works).
- New plugin `duo/mods-src/unity/GameNativeDuo.HollowKnight` (compiled against the game's Assembly-CSharp, no
  reflection; uses the shared plugin's internals via `InternalsVisibleTo`). Asset: `assets/duo/mods/hollow-knight/plugin/`.
  - `LiveMap`: the game's own map (`Game_Map(Clone)` under HudCamera) drawn live by our camera below the HUD strip
    (`HudReservePx` 150). Content = `GameMap.SetupMap()` + `WorldMap()` (same as the inventory map; Knight icon only
    with Wayward Compass). Map objects move to a free layer (7 on the Thor) while ours is shown, back to UI (5) when the
    game uses the map. Zooms Knight / Area / World, smooth follow. Map button switches zoom (`ListenForQuickMap` patched,
    Knight never freezes). Touch: drag pan, pinch zoom, double-tap zoom, drifts back after 4 s. Soft fade at top edge.
  - Draw order: our clear camera -> map -> bottom UI camera (set to clear Depth; pause menu canvases) -> HudCamera.
    Paused / options / dialogue (`DialogueBox.hidden`) dims the map (0.72) instead of hiding it. Inventory hides it.
  - `WorldOnTop`: the game disables tk2dCamera while the inventory is open (game keeps running, a hit closes it);
    we keep it on so the top screen is not black.
  - `TestHarness`: only if `%AppData%\GameNativeDuo\hk-test.txt` exists at game start: **no saving, invincible**, commands
    from that file (zoom, dump, gates, scene, compass, fsm, event, pause, state, bounds). Use it for every automated test
    on the user's real save. Remove the file afterwards (done at end of this session) and restart the game.
- Shared plugin 0.4.1: `Split.TouchFilter` hook, `CameraPlacer.PluginCameras`, `UiRouter.BottomCamera`. The bundled
  DLL is built against **Slime Rancher's** Managed (Unity 2019, oldest target):
  `dotnet build -c Release -p:ManagedPath=<SlimeRancher_Data/Managed> -p:BepInExPath=app/src/main/assets/duo/mods/shared/bepinex/BepInEx/core`.
  HK plugin: `-p:ManagedPath=<Hollow Knight/hollow_knight_Data/Managed>` (PC Steam copy works).

## Tested on the Thor (save backed up first: `duo/agents/runs/2026-10-27-hk/save-backup/`, hash unchanged)

- Live map at load, Knight/Area/World zooms, World shows the whole mapped kingdom with pins (fixed: bounds from the 14
  areas only; pin groups have far-away unused children).
- Room change (map follows), inventory map opens normally on the bottom with world visible on top, map returns after.
- Touch double-tap / drag / recenter. Map button with real input (user) switches zoom.
- Pause menu readable over the dimmed map, undims after.

## Open / next

- Dialogue dimming with a real NPC (untested). Shops (uGUI? HudCamera?) untested.
- Steady-state frame time with the map on (baseline without: 60.0 fps, plugin 0.12 ms). Add LiveMap time to
  `Split.OwnTicks` so the stats include it.
- Slime Rancher / Subnautica smoke test with shared plugin 0.4.1.
- `MapButtonZooms=false` (vanilla quick map on the bottom) untested.
- Settings are a BepInEx cfg only; consider rows in the GameNative Duo tab.
- Area name label, ui-layout preset for the HUD, Godhome/dream areas, first-map-less early game.
- adb key events are unreliable here (one stuck input walked the Knight into spikes before test mode existed); drive
  tests with the harness, not keys.
- Community package `gamenative-duo.hollow-knight-dual-screen` is now superseded by the bundled mod: retire it.
