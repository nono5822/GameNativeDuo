# UI layout editor, native touch, bridge (2026-10-19)

Branch `duo/slime-rancher`. Tested on the Thor with Slime Rancher. Contract for other engines:
`duo/docs/engine-adapter-contract.md`.

## What changed

- **Bridge** (`duo/bridge/DuoBridge.kt`, plugin `Bridge.cs`): UDP on 127.0.0.1 between app and mod, port in
  `layout.json`. First piece of architecture step 4.
- **Native touch**: bottom-screen fingers go over the bridge to uGUI's EventSystem (`TouchInjector.cs`), no X mouse
  events. Fixes: cursor showing, Slime Rancher switching prompts to keyboard/mouse (InControl's `LastInputType`), taps
  firing the vacpack (left mouse = `attack`), and HUD taps being impossible in gameplay (the game's
  `SRStandaloneInputModule` only handles the mouse while `Cursor.visible` and has no touch path). Multi-touch and
  drags work (verified on the FOV slider). Setting "Native touch on the bottom screen" (default on); games without a
  bridge mod keep mouse clicks.
- **UI layout** (`duo/ui/UiLayout.kt`, plugin `UiRules.cs`/`UiRouter.cs`): per element bottom/top/hidden, size and
  offset, live. Elements moved to the other screen are reparented under a proxy on a canvas bound to that screen's
  UI camera; a same-named placeholder keeps their id, visibility and fading. Size/offset are applied in
  `Canvas.willRenderCanvases` on top of whatever the game writes (the crosshair animates its scale every frame).
  Canvas scaling moved to a `CanvasScaler.SetScaleFactor` prefix (the old postfix invoked a reflection method every
  frame per canvas, allocating).
- **Crosshair (#5) fixed generically**: names matching crosshair/reticle/reticule default to the top screen; Slime
  Rancher also ships `assets/duo/mods/slime-rancher/ui-layout.json`.
- **Editor** (`duo/ui/UiLayoutEditor.kt`): two-finger long press on the bottom screen -> menu -> "Edit UI layout".
  Tap to select (finger-sized targets, real hits beat padded ones), Parent, List (reaches hidden elements), drag,
  pinch, Bottom/Top/Hide, +/-, Reset, Reset all (back to the preset). Top-screen elements are edited in a map. The
  toolbar always moves away from what is being edited.

## Gotchas found

- `InetAddress.getLoopbackAddress()` is `::1` on the Thor; Wine sends to 127.0.0.1. Bind IPv4 explicitly.
- A user rule without `screen` replaced the implicit crosshair rule and dropped the crosshair to the bottom; implicit
  screens now survive resize-only rules, and the editor pins the current screen on every edit.
- `FrameLayout.addView` gives default MATCH_PARENT params: don't use "has layout params" as "already placed".
- Unity stalls `Update` for > 5 s while loading, so the bridge timeout is 15 s.
- A stray tiny `Currency` offset rule appeared once during testing and its source was not found; rule saves are now
  logged (`GNDuo: UI layout: <id> -> ...`) to catch it if it recurs.

## Testing without hands

- Taps/drags on the bottom screen: `adb shell input -d 4 tap X Y` / `swipe` (display 4, 1240x1080 landscape).
- Two-finger long press (opens the menu): raw multi-touch on `fts_ts_3` (`/dev/input/event5`, portrait 1080x1240)
  with `sendevent` (slots 0/1, tracking ids, BTN_TOUCH), hold 1.5 s.
- Push a rebuilt plugin without the toggle: `adb push` to `/data/local/tmp`, then `run-as ... sh -c 'cat > <game>/BepInEx/plugins/GameNativeDuo-split/...'`.

## Open

- Unity resolution must still be set to 1920x2160 once by hand.
- Element ids are scene-path based: two scenes with an identically named root canvas share ids.
- Editor elements go stale on scene change until the next edit (no auto refresh, by design: no polling).
- The same adapter contract for Stardew (SMAPI) and other engines; MCP server for agents on top of the bridge.
