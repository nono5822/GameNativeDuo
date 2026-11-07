# Uniform editing (U1-U4): progress

Design: `duo/docs/uniform-editing.md`. Plan context: `duo/docs/devkit-platform.md`.
Branch `duo/uniform-editing`, worktree `~/dev/GameNativeDuo-uniform` (the main checkout holds someone's uncommitted
hotkeys work on `agent/2026-10-29-0124`: never commit or reset it from here).

**Resuming:** read this file, `git log --oneline devkit..HEAD`, `git status`; continue at the first unchecked box.
Commit after every box (code + this file together). Device: Thor `d615ac15` (USB) or `duo/agents/thor-wifi.sh`.

## U1 Declare and gate

- [ ] Parser: `features` (screens/move/scale range), `present`, `when` in `UiElement`; old top-level `screens` kept; unit tests
- [ ] `EditCapabilities` model (source, reasonOff, elements, background, widgets) + unit tests
- [ ] On-device editor gated by it (drag/pinch/+-/screen chips, reasons in the toolbar, absent elements in the list)
- [ ] Menus (`DuoMenuPanel`, `DuoQuickMenu`, `SplitScreenController`) read it instead of `canEdit`/`modSeen`
- [ ] PC editor: server sends capabilities, `index.html` gates the same way
- [ ] Balatro: declare features, list all four elements always (`present`), `features.json`
- [ ] Stardew: list absent menus with `present: false`, declare features (rebuild DLL, needs the game's folder)
- [ ] Unity: crosshair screens, plugin elements declare features (rebuild shared DLL)
- [ ] `engine-adapter-contract.md` updated
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
