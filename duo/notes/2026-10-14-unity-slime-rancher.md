# Unity screen split, first game: Slime Rancher (2026-10-14)

Branch: `duo/slime-rancher` (on top of `duo/stardew`).

- Slime Rancher: Steam 433340, Unity 2019.4.29f1, Mono backend, .NET 4 (MonoBleedingEdge).
- Loader: BepInEx 5.4.23.5 win_x64 (MIT), bundled under `assets/duo/mods/slime-rancher/` without the `.xml` docs and
  `.doorstop_version` (Android asset packaging drops dotfiles; not needed to run). Licence and notice installed into
  `BepInEx/`.
- Plugin: `duo/mods-src/unity/GameNativeDuo.UnitySplit` (generic for uGUI games). Every 0.5 s: cameras that render to
  the window get their viewport mapped into the top part (their own sub-rect kept); root canvases in
  screen-space-overlay or screen-space-camera mode move to a "GameNativeDuo UI camera" over the bottom part;
  `CanvasScaler.HandleScaleWithScreenSize` is post-patched to scale for the bottom screen's size.
- Result on the Thor: works after setting the in-game resolution to 1920x2160 once (before that the game rendered
  centred across both screens). HUD and menus on the bottom screen.
- Open: crosshair moves to the bottom screen with the HUD (issue #5; it is an `Image` + `CrosshairUI` inside the HUD
  canvas). Untested: taps during locked-mouse gameplay, aiming.
