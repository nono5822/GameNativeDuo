# Engine adapter contract: UI layout, element list, native touch

What an in-game mod (an "engine adapter") implements so GameNative Duo can put its UI on the screens the user
chooses and send it bottom-screen touches. The Unity adapter (`duo/mods-src/unity`) is the reference
implementation; the Stardew adapter (`duo/mods-src/stardew`, engine `stardew-smapi`) is the second, the Balatro one
(`assets/duo/mods/balatro/GameNativeDuo-split`, Lua, engine `balatro-love`) the third. Games with no
adapter can still use **region view** (the bottom screen shows a magnified part of the frame), which needs nothing
from the game. Everything here is engine-neutral: the app side (editor, storage, presets) never changes per engine.

```
 Android app (GameNative Duo)                         game process (Wine)
 ───────────────────────────                          ──────────────────
 UiLayoutStore ── ui.json ──────────────────────────▶ adapter applies rules
 DuoBridge  ◀──── HELLO every 2 s (capabilities) ──── adapter
 DuoBridge  ───── TOUCH / RULES_CHANGED / DUMP ─────▶ adapter
 UiLayoutEditor ◀─ elements.json + ELEMENTS_WRITTEN ─ adapter
```

All files live in the prefix at `%AppData%\GameNativeDuo\` (`drive_c/users/xuser/AppData/Roaming/GameNativeDuo/`).

## 1. `layout.json` (app → adapter)

Where the two screens are inside the game window, in window pixels, origin top-left:

```json
{"screen":{"w":1920,"h":2160},"top":{"x":0,"y":0,"w":1920,"h":1080},"bottom":{"x":340,"y":1080,"w":1240,"h":1080},
 "gap":249,"bottomScale":1.142,"bottomShift":0,"bridge":{"port":38705}}
```

Act only while the window is exactly `screen` (±2 px). Re-read it when it changes (calibration rewrites it live);
`bridge.port` is new every session.

## 2. `ui.json` (app → adapter): the UI layout

```json
{"format":"gamenative-duo/ui-layout","version":1,"game":"STEAM_433340","engine":"unity-ugui",
 "elements":{"HUD Root/HudUI/UIContainer/crossHair":{"screen":"top","scale":1.2,"x":0.05,"y":0}}}
```

- **Element ids** are the adapter's own, but must be stable across sessions and machines. Unity: object names from
  the scene root joined with `/`, `#n` for the n-th of same-named siblings, `/` and `#` in names replaced by `_`.
- **`screen`**: `bottom`, `top`, `hidden`, or absent (adapter's default: bottom, crosshairs on top).
- **`scale`**: multiplies the element's own size (0.25–4). **`x`, `y`**: shift as fractions of the width and height
  of the screen the element ends up on (+ is right and down). Fractions, not pixels, so one layout fits every device
  with the same screen shapes.
- A rule with no `screen` keeps the adapter's default screen (resizing a crosshair must not drop it to the bottom).
- The game keeps animating its UI: apply rules on top of what the game writes every frame, never instead of it.
- **`background`** (optional): what the bottom screen shows behind the UI.
  `{"mode":"game|blur|color|image","color":"#101322","dim":0.3,"blur":0.5,"image":"background.jpg"}`. `game` (the
  default) is the world continuing below the top screen; `blur` the same, blurred (`blur` 0 to 1); `color` a solid
  color; `image` the picture `background.jpg` next to `ui.json` (cover the screen, centred). `dim` (0 to 0.9) darkens
  everything but `color`. Draw it on the GPU only, and only what you announce in HELLO (below): the app greys out the
  rest.
- **`widgets`** (optional): readouts the app draws itself (FPS, battery...). Adapters ignore them.
- Same file format for the user's layout (`files/duo/ui/<game>.json`), the preset bundled with a mod
  (`assets/duo/mods/<mod>/ui-layout.json`) and, later, community presets.

## 3. The bridge (UDP on 127.0.0.1)

The app listens on `bridge.port` (IPv4: Wine's sockets are IPv4, and Android's `getLoopbackAddress()` can be `::1`).
The adapter binds any free loopback port and sends a HELLO from it every 2 s; the app replies to that port. Accept
packets only from 127.0.0.1:`bridge.port`. Little-endian; every packet starts with `'G' 'D' 0x01 type`.

| Type | Direction | Payload after the 4-byte header |
|---|---|---|
| `0x01` TOUCH | app → adapter | `action u8` (0 down, 1 move, 2 up, 3 cancel), `pointer u8`, 2 bytes padding, `x f32`, `y f32` (window px, origin top-left) |
| `0x02` RULES_CHANGED | app → adapter | none: re-read `ui.json` now |
| `0x03` DUMP_ELEMENTS | app → adapter | `request u32`: write `elements.json`, then answer ELEMENTS_WRITTEN |
| `0x81` HELLO | adapter → app | `capabilities u32` (1 touch, 2 UI rules, 4 element list, 8 background color and image, 16 blur, 32 game), engine name (ASCII) |
| `0x83` ELEMENTS_WRITTEN | adapter → app | `request u32` |

The app treats the adapter as gone after 15 s without a HELLO (loading screens stall games) and then falls back to
mouse clicks. Moves are coalesced per finger; a receive thread must only queue, the game thread applies.

## 4. Native touch

Deliver TOUCH to the engine's UI as touches, **never** through the OS mouse. A mouse makes the game draw a cursor,
switch its button prompts to keyboard and mouse, and treat taps as gameplay clicks (in Slime Rancher a tap fired the
vacpack), and it breaks while the game locks the mouse. Unity: raycast through the `EventSystem` and run uGUI's
pointer events (down, up, click, drag, drop, enter, exit) per finger; this works whatever input module the game uses.

## 5. `elements.json` (adapter → app, on request only)

```json
{"engine":"unity-ugui","screen":{"w":1920,"h":2160},"elements":[
 {"id":"HUD Root","name":"HUD Root","parent":-1,"screen":"bottom","x":340,"y":1080,"w":1240,"h":1080,"graphic":false},
 {"id":"HUD Root/HudUI/UIContainer/crossHair","name":"crossHair","parent":0,"screen":"top","x":946,"y":526,"w":28,"h":28,"graphic":true}]}
```

Parents before children (`parent` is an index). Rectangles are where the element is **now** (rules applied), in window
pixels. `graphic`: it draws something (the editor outlines those). Write it after the frame's rules are applied. It is
only requested while the editor is open, so walking the whole UI is fine.

## Cost rules

- Idle cost ~0: no polling on the app side, a blocked receive thread on the adapter side, one HELLO per 2 s.
- Per frame the adapter touches only the elements that have rules, without allocations.
- Discovery (finding new canvases) at a low rate (Unity: 2 Hz); file watching every 2 s as a fallback for the bridge.
