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
- [ ] Thor test: Balatro title screen + run, Stardew, a Unity game

## U2 Known before launch + tags

- [ ] `features.json` bundled per mod in assets; catalog reads it; editing level computed (Full/Partial/Regions)
- [ ] Mod explorer tag on cards + details table; Dual screen control status text
- [ ] Editor uses the static list while the game is paused (GameNative menu)
- [ ] Thor test

## U3 Regions as elements

- [ ] Read current region view code (`split/RegionView.kt`, `RegionPanel.kt`, native output) and write the sub-plan here
- [ ] (sub-steps go here)

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
