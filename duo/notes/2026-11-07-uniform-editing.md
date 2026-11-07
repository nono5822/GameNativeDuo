# Uniform editing (U1-U4): progress

Design: `duo/docs/uniform-editing.md`. Plan context: `duo/docs/devkit-platform.md`.
Branch `duo/uniform-editing`, worktree `~/dev/GameNativeDuo-uniform` (the main checkout holds someone's uncommitted
hotkeys work on `agent/2026-10-29-0124`: never commit or reset it from here).

**Resuming:** read this file, `git log --oneline devkit..HEAD`, `git status`; continue at the first unchecked box.
Commit after every box (code + this file together). Device: Thor `d615ac15` (USB) or `duo/agents/thor-wifi.sh`.

## U1 Declare and gate

- [x] Parser: `features` (screens/move/scale range), `present`, `when` in `UiElement`; old top-level `screens` kept; unit tests
- [x] `EditCapabilities` model (source, reasonOff, elements, background, widgets) + unit tests
- [x] On-device editor gated by it (drag/pinch/+-/screen chips, reasons in the toolbar, absent elements in the list)
- [x] Menus (`DuoMenuPanel`, `DuoQuickMenu`, `SplitScreenController`) read it instead of `canEdit`/`modSeen`
- [x] PC editor: server sends capabilities, `index.html` gates the same way
- [x] Balatro: declare features, list all four elements always (`present`), `features.json`
- [x] Stardew: list absent menus with `present: false`, declare features (rebuild DLL, needs the game's folder)
- [x] Unity: elements under camera roots (sprite HUDs, tk2d) declare screens = their camera's + hidden (rebuild shared DLL). Crosshairs left free: they can move, the default only keeps them on top
- [x] `engine-adapter-contract.md` updated
- [x] Thor test: Balatro title + menus, Stardew title, Hollow Knight title (in-run/in-save checks listed in the log)

## U2 Known before launch + tags

- [x] `features.json` bundled per mod (`assets/duo/mods/<id>/features.json`, Unity fallback `shared/unity-features.json`), `ModFeatures` + `EditingLevel` (Full/Partial; Regions comes with U3), tests read the bundled files
- [x] Mod explorer tag on cards + "UI editing" section in details; Dual screen control shows "Official · Partial editing"
- [x] Editor uses the static list (bundled features.json, all absent) until the mod answers; capabilities editable from the installed mod's features before its hello
- [x] Thor test: tag on the Balatro card, "UI editing" details, control text (widened to 240 dp: it was cut at 160). Static fallback compiles; not provoked on the device (needs the editor open before the mod's first hello).

## U3 Regions as elements

- [x] Read current region view code and write the sub-plan (below)
- [x] U3a Native: `duoSetRegions` (per region crop + scissor on the bottom output, clear color) and `duoSetMasks`
  (black rects on the top frame via `vkCmdClearAttachments`, loaded with `GetDeviceProcAddr`); Java hooks in
  `VulkanRenderer`; .so rebuilt (worktree needed `lsfg-vk-android/thirdparty/*` copied from the main checkout: the
  dxbc submodule clone fails)
- [x] U3b Model: `UiRegion` (id, name, source fractions of the X screen, screen top/bottom/hidden, place on the bottom
  as centre fractions + width fraction, options filter/touch) in `UiLayout.regions`; JSON round trip; migrate the
  old single `region` string to one region filling the bottom screen; unit tests
- [ ] U3c Session: region mode gets a `UiLayoutController` (source REGIONS); `RegionOutput` turns regions into
  `duoSetRegions` / `duoSetMasks` data (pure function, unit-tested); background color drawn as the clear color;
  widgets on in region mode; touch: a tap inside a shown region clicks at its source point
- [ ] U3d Editor: REGIONS elements (bottom = place, top map = source); "Add region" (new region in the top map's
  middle); dragging/pinching the source in the map, the place on the bottom; Remove; list header "Your regions";
  background modes color only (image/blur greyed: "not in region view yet"); PC editor gets the same
- [ ] U3e `EditingLevel.REGIONS` tag for games without a mod (mod explorer and Dual screen control)
- [ ] U3 Thor test: a game without a mod (Isaac / Dead Cells), two regions, one hidden (masked on top)

## U4 Conformance

- [ ] Check runner in developer mode (rule tweak, dump, compare, restore), report in the editor
- [ ] Thor test on Balatro and a Unity game

## Log

- 2026-11-07: branch and worktree created, docs committed.
- Found: the editor's tap hit-test skipped every top-level element (`depth == 0`, meant for Unity's whole-screen
  canvases), so Balatro's four elements (all `parent: -1`) could only be picked from the List. Fix in the editor
  step: skip only top-level elements that have children (`UiElement.hasChildren`).
- Editor, menus, PC editor gated (compiles, lint clean; not yet run on the Thor). PC editor had the same root-element
  click bug (`e.depth === 0`); fixed the same way. `canEdit`/`modSeen` are gone: everything reads
  `UiLayoutController.capabilities`.
- Stardew references: pulled the game folder (no Content) from the Thor into the session scratchpad; build with `-p:GamePath=<that> -p:EnableModDeploy=false -p:EnableModZip=false`. Pull again with `adb exec-out run-as app.gamenative.duo sh -c 'cd ".../common/Stardew Valley" && tar cf - --exclude=Content --exclude=Mods .'`.
- Unity build refs: `-p:ManagedPath=~/dev/GameNativeDuo/duo/agents/runs/2026-10-28-0119/sr-managed` (Slime Rancher, oldest Unity).
- Thor, Balatro (title screen only, no run: achievements): editor lists all 4 elements as absent with their `when`;
  Menus selectable by tap once a popup is open; drag refused with the hint lit; Hide and size controls hidden;
  background tab greys blur with the reason. Found and fixed: Menus reported the overlay's whole backdrop
  (6279x3610 px in a 1440 window) instead of its panel (`D.content_of`). Hand/Consumables/Hand info during a run
  still to check (needs a seeded run with `G.F_NO_ACHIEVEMENTS`).
- `refreshIfUpdated` left Balatro's Lua alone ("0 updated": files hand-pushed in earlier sessions count as the
  user's). Pushed by hand with `run-as cp`.
- Thor, Hollow Knight (title): editor lists 43 elements from the new shared plugin, no regressions. tk2d HUD (fixed
  screens) only exists in-game: untested. Plugins were not refreshed by the app update either: they predate the
  install record (hand-pushed during HK work), so the installer counts them as the user's. Not a user-facing bug
  (fresh installs record them), but the dev device needs hand pushes after each change.
- Thor, Stardew (title): Chat absent with "In multiplayer". Found and fixed: on the title screen the HUD menus exist
  with stale positions (a box drawn at the screen edge); now everything is absent until `Context.IsWorldReady`.
  Editor status counts only visible elements.
