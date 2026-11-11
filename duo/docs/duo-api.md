# The Duo API

One HTTP API that every developer tool speaks: the browser UI (`assets/duo/pc-editor/index.html`), `duo mcp`, the
CLI's `duo test` / `duo check`, and the VS Code extension. Two servers implement it:

| Server | Where | Started by | Address |
|---|---|---|---|
| **Device** | the app (`duo/pc/PcEditorServer.kt`) | "Edit on PC" (two-finger menu, in-game menu) | `http://<Thor's Wi-Fi IP>:8642`, pairing code |
| **Sim** | `duo sim` (`duo/tools/duo/`) | the developer | `http://127.0.0.1:8642`, token printed by `duo sim` |

A tool never needs to know which one it talks to beyond `GET /api/info`'s `server`. Specified here so the two cannot
drift apart; `duo/tools/duo/tests` runs the same contract tests against the sim.

## Rules

- JSON in and out (`Content-Type: application/json`), UTF-8. Errors: HTTP 4xx/5xx with `{"error": "..."}`.
- **Auth**: every `/api/` request carries `X-Duo-Code: <code>` (or `?k=<code>` for images and the page's first load).
  The device's code is the 6-character pairing code on the bottom screen (20 wrong codes stop the server); the sim's
  is a random token printed at start. A custom header means a web page on another site cannot drive either server
  (browsers preflight it, and neither server answers CORS).
- The device serves the **player's** endpoints (Layout) always, and the **developer** endpoints (everything marked
  *dev*) only while developer mode is on (setting in the GameNative Duo tab); otherwise they answer 403
  `{"error": "developer mode is off"}`.
- Nothing runs in the background: the device server stops with the session or when its panel closes.
- Events are **long polls**, not WebSockets: `GET /api/events?since=N&wait=S` returns at once when something newer
  than `N` exists, else after up to `S` seconds (max 25) with an empty list. One mechanism for curl, the MCP server
  and the page, no upgrade handshake to implement twice.

## Endpoints

### Session

| Method, path | Answer |
|---|---|
| `GET /api/info` | `{"api": 1, "server": "device"\|"sim", "app": "0.x", "sdk": "1.0", "dev": true, "game": "STEAM_2379780", "title": "Balatro", "mod": {"engine": "balatro-love", "sdk": "1.0", "caps": 47, "connected": true, "port": 40811}, "profile": "thor"}` |
| `GET /api/state` | Layout tab state: screens (`window`, `top`, `bottom`), `caps` (from `EditCapabilities`), the user's `layout`, `elements` with features and limits (today's PC editor). Device: `answered` is false when the game did not answer (paused, loading) and `elements` is then the mod's bundled list; `elementsVersion` counts the mod's ELEMENTS_CHANGED (also in `/api/info`) |
| `GET /api/snapshot/top.jpg`, `/bottom.jpg` | The screen as it looks now (device: PixelCopy; sim: window capture cropped) |

### Layout (player)

| Method, path | Body | Effect |
|---|---|---|
| `POST /api/rule` | `{"id": "Hand", "rule": {"screen": "top", "scale": 1.1, "x": 0, "y": 0}}` | Sets and saves one element's rule |
| `POST /api/background` | `{"mode": "color", "color": "#101322", "dim": 0.3}` | Bottom screen background |
| `POST /api/widgets` | `[{"type": "fps", ...}]` | App-drawn widgets |
| `POST /api/image` | JPEG/PNG bytes | Background image |
| `POST /api/snapshot-background` | none | The top screen's picture as the background |
| `POST /api/regions/add`, `/remove` | `{"id": ...}` for remove | Region view regions |
| `POST /api/reset` | none | Back to the preset |
| `POST /api/share` | `{"name", "description", "author"}` | The community share form's URL |

### Inspector (*dev*)

| Method, path | Answer |
|---|---|
| `GET /api/elements` | The mod's element list as it reported it (a fresh DUMP_ELEMENTS): `{"engine", "screen", "elements": [...]}` exactly as `elements.json`, plus `features.json` as `features` |
| `POST /api/elements/refresh` | Asks the mod again; same answer |

### Bridge (*dev*)

| Method, path | Answer |
|---|---|
| `GET /api/bridge` | `{"hello": {...}, "rates": {"in": 0.5, "out": 3.0}, "recent": [{"t": 1731070000123, "dir": "in", "type": "HELLO", "size": 27, "text": "caps 0x2f balatro-love/sdk1.0"}]}` (last 100 packets, decoded; touch moves folded) |
| `POST /api/bridge/send` | `{"type": "RULES_CHANGED"}` or `{"type": "DUMP_ELEMENTS"}`: send one app → mod packet by name |

### Console (*dev*)

| Method, path | Body | Answer |
|---|---|---|
| `POST /api/command` | `{"cmd": "hk.map.zoom", "args": {...}}` | `{"status": "ok"\|"refused"\|"unknown"\|"failed"\|"timeout", "message": "..."}` (waits up to 3 s for COMMAND_RESULT) |
| `GET /api/companion/state?prefix=hk.` | | `{"values": {"hk.geo": 120, ...}}`: what the companion store holds |
| `POST /api/companion/subscribe` | `{"keys": ["hk.geo"]}` | Adds the console's keys to the app's SUBSCRIBE (cleared when the page stops polling for 30 s) |
| `POST /api/touch` | `{"x": 620, "y": 540, "action": "tap"\|"down"\|"move"\|"up", "pointer": 0}` | A bottom-screen finger in **bottom-screen pixels**, delivered like a real one (native touch, or a click without a mod) |

### Logs (*dev*)

| Method, path | Answer |
|---|---|
| `GET /api/events?since=N&wait=S` | `{"next": M, "events": [{"seq": 41, "t": ..., "kind": "log"\|"bridge"\|"elements"\|"state"\|"check", ...}]}` |
| `GET /api/logs?since=N` | Same, logs only: `{"source": "mod"\|"app"\|"bepinex"\|"smapi", "level": "I", "text": "..."}` |

Kinds: `log` (a mod LOG line; device: also `GNDuo` logcat lines; sim: also the BepInEx/SMAPI log tail), `bridge`
(HELLO from a new mod, a mod gone), `elements` (ELEMENTS_CHANGED), `state` (keys changed, names only), `check`
(conformance progress and the report).

### Conformance (*dev*)

| Method, path | Answer |
|---|---|
| `POST /api/check` | Starts the check (`uniform-editing.md`); `{"started": true}` or 409 while one runs |
| `GET /api/check` | `{"running": false, "progress": "...", "report": {"game", "engine", "passed", "results": [...], "skipped": [...]}}` (the same JSON the device saves in `files/duo/conformance/<game>.json`) |

## Sim-only

| Method, path | Effect |
|---|---|
| `POST /api/sim/profile` | `{"profile": "thor"\|"pocket-ds"\|"rp-dual"}`: rewrites `layout.json` for that device |
| `POST /api/sim/window` | `{"title": "Balatro"}` or `{"pid": 1234}`: which window to capture |

## Versioning

`GET /api/info` carries `"api": 1`. New endpoints and fields are additive; a breaking change bumps `api`. Tools check
it once and say "update GameNative Duo" or "update duo" instead of failing on a 404.
