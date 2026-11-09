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
- Shared plugin 0.4.1 (0.4.2 adds plugin elements): `Split.TouchFilter` hook, `CameraPlacer.PluginCameras`, `UiRouter.BottomCamera`. The bundled
  DLL is built against **Slime Rancher's** Managed (Unity 2019, oldest target):
  `dotnet build -c Release -p:ManagedPath=<SlimeRancher_Data/Managed> -p:BepInExPath=app/src/main/assets/duo/mods/shared/bepinex/BepInEx/core`.
  HK plugin: `-p:ManagedPath=<Hollow Knight/hollow_knight_Data/Managed>` (PC Steam copy works).

## Tested on the Thor (save backed up first: `duo/agents/runs/2026-10-27-hk/save-backup/`, hash unchanged)

- Live map at load, Knight/Area/World zooms, World shows the whole mapped kingdom with pins (fixed: bounds from the 14
  areas only; pin groups have far-away unused children).
- Room change (map follows), inventory map opens normally on the bottom with world visible on top, map returns after.
- Touch double-tap / drag / recenter. Map button with real input (user) switches zoom.
- Pause menu readable over the dimmed map, undims after.

## 2026-10-28 (agent queue, two sessions): done

- Map button: never opens a second map (all `ListenForQuickMap` FSMs; the live map owns the button whenever it is in
  charge); the live map shows exactly what the inventory map shows (layer moves restore each object's own layer).
  Overlap pass of every pane and overlay; shops, dialogue, pause, options fixed or verified. See
  `duo/notes/2026-10-28-agent-run.md` and the commit messages.
- Settings are the **"Live map" element of the UI layout** (BepInEx cfg gone): on/off, rectangle (scale/shift of the
  area below the HUD strip), start zoom, after a pan, under menus, Knight icon, map button, top screen in inventory.
  Contract: `engine-adapter-contract.md` "Plugin elements". Preset `assets/duo/mods/hollow-knight/ui-layout.json`.
- Cost: plugin 0.26 ms/frame with the map on (0.15 off; live map 0.12), 60 fps either way. In test mode the plugin
  logs `Live map cost:` every 30 s. Lesson: under Box64 every Unity/game call counts; `GameManager.IsGameplayScene()`
  alone was 0.21 ms.

## Open / next

- Retire the community package `gamenative-duo.hollow-knight-dual-screen` (superseded by the bundled mod): the agent
  could not run git in `../GameNativeDuo-community`; steps in `duo/notes/2026-10-28-agent-run.md`.
- PC editor: option dropdowns not looked at in a browser (API verified).
- The bottom-screen editor draws outlines of every inactive HK UI element over the map; tapping the map selects one
  of those (use List: the live map is first).
- The first two-finger long press right after a game loads is often missed (second works); seen with `sendevent`,
  check by hand.
- Slime Rancher / Subnautica smoke test with shared plugin 0.4.2.
- Area name label, Godhome/dream areas, first-map-less early game.
- adb key events are unreliable here (one stuck input walked the Knight into spikes before test mode existed); drive
  tests with the harness, not keys. The harness's first `pad` command after a launch can be lost: send one first.
