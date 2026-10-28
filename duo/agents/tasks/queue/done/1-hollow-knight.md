# Task 1 (highest priority): Hollow Knight live map, finished and polished

Read `duo/agents/tasks/queue/RULES.md`, then `duo/notes/2026-10-27-hollow-knight.md`. Code:
`duo/mods-src/unity/GameNativeDuo.HollowKnight/` (`LiveMap.cs` 751 lines, `Patches.cs`, `Settings.cs`, `TestHarness.cs`).
Build: `dotnet build -c Release -p:ManagedPath="$HOME/.local/share/Steam/steamapps/common/Hollow Knight/hollow_knight_Data/Managed" -p:BepInExPath=app/src/main/assets/duo/mods/shared/bepinex/BepInEx/core`,
copy the DLL to `app/src/main/assets/duo/mods/hollow-knight/plugin/`, and for quick iterations push it straight to the
game's `BepInEx/plugins/GameNativeDuo-hollowknight/` with `run-as` (then the app counts it as user-changed: push again after an APK install).

Save safety exactly as in `regression-pass.md` (backup + sha256, `hk-test.txt`, harness only, no adb key events).

## Bugs the user reported (fix first, reproduce before fixing)
1. **Pressing the map button shows a second map over the bottom one.** `QuickMapPatch` (ListenForQuickMap) plus the
   `quickMap.WasPressed` check in `LiveMap.cs:106` evidently do not catch every path: find which FSM/state/object
   opens the vanilla quick map (fsm dumps via the harness; look for every `ListenForQuickMap` and quick-map FSM, not
   only the first), and the moments where `OwnsMapButton` is false (`_shown` toggling, scene loads, benches).
   The harness `zoom` command bypasses real input, which is likely why the bug slipped through: add a harness command
   that presses the button **through the game's input path** (InControl `quickMap` action / the same thing a pad
   press does) and use it for every test of this. Test held, tapped, and spammed presses, during room transitions,
   while paused and right after closing the inventory.
2. **Areas the player has not discovered must not appear.** The live map must show exactly what the vanilla
   inventory map shows for this save, nothing more: area outlines, room shapes (Quill/bench rules: `scenesMapped`
   vs visited), names, pins, markers, the Knight icon (Wayward Compass only). Check what `SetupMap()` + `WorldMap()`
   reveal, and anything our code activates itself (layer moves, bounds, World zoom). Verify by screenshotting the
   vanilla inventory map and the live map at the same zoom for 3+ save states (use `scene` to travel; the World zoom
   especially) and comparing them. Bounds for World zoom must come from discovered areas only.

## Inventory and overlap pass
Open every inventory pane (Inventory, Charms incl. equipping with notches, Map incl. pins and markers, Hunter's
Journal, Dream Nail/Wanderer's Journal pages if present) and every overlay that can appear on the bottom screen:
pause, options, dialogue (real NPC), shops (Sly, Iselda, Salubra), bench rest, area-name title cards, item pickups /
ability tutorials, achievement-style popups, the soul/masks HUD while damaged and healing, geo counter while
collecting. For each: screenshot both screens, check nothing overlaps or is cut off, map dims or hides as designed, the
world stays on top. Fix what fails. Record a table of every pane/overlay with result and screenshot names.

## Customizable by the user
Move the live map's settings out of the BepInEx cfg into what users actually touch. They should be element(s) in the
UI layout (editor on the Thor and the PC editor): live map on/off, its rectangle (position/size on the bottom screen,
HUD reserve), default zoom, follow/drift-back behaviour, opacity/dim level when paused, Knight icon rule (vanilla
compass rule by default), what the map button does (zoom vs vanilla quick map). Defaults must be good without
touching anything. Ship a `ui-layout.json` preset for HK that places the HUD and map cleanly.

## Also
- Add LiveMap's time to `Split.OwnTicks`; measure steady-state frame time with the map on vs off (target: no
  measurable fps loss, plugin under 0.3 ms/frame); report numbers.
- Retire the superseded community package (remove from the index in `../GameNativeDuo-community`, keep a redirect
  note); commit there with `git -C ../GameNativeDuo-community`.
- Update the HK note (`duo/notes/2026-10-27-hollow-knight.md` "Open / next") and CLAUDE.md if facts changed.
