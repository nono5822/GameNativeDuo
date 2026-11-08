# Devkit platform: making dual-screen mods easy to build

Goal: anyone with a game and an idea can make a dual-screen mod for it, test it without a Thor, and ship it to
users who only ever see one switch. Today every piece exists (bridge, `layout.json`/`ui.json`, element list, plugin
elements, companions, signed community packages, undo records, save backups), but a new developer has to
reverse-engineer `duo/mods-src/unity` to use them. This plan turns those pieces into a platform, in build order.

Related: `engine-adapter-contract.md` (the protocol), `companions.md` (state, commands, companion API),
`community-packages.md` (distribution), `uniform-editing.md` (one editor that behaves the same in every game: every
item below must respect it).

## Three levels of mod

Every design choice below follows from this ladder. Most games should stop at level 1.

| Level | Made by | What it is | Trust |
|---|---|---|---|
| 0. No code | Any player | Region views, UI layouts, companions (JSON), hotkey cards, settings packages | Data: one tap, may auto-apply |
| 1. Game profile | Modders without a compiler | Data that configures an **official** engine adapter: UI cameras, canvas routing, element defaults, state to publish | Data: our signed adapter runs, the profile only configures it |
| 2. Code mod | Developers | A BepInEx / SMAPI / Lovely / ASI plugin built on the Duo SDK | Code: built from source by CI, reviewed, signed, consent per version |

Code mods run inside Wine as the app's own Android user and can read everything the app stores (store logins
included). Android offers no in-process sandbox, so the best protection is a short list of code: every feature that
can move from level 2 to level 1 should.

---

## 1. Bridge v2 and the SDKs

### Bridge v2

The wire format and capability bits stay; v2 fills in what `companions.md` already specified and adds what the SDKs
need. Every addition is a new capability bit, so v1 mods keep working.

| Addition | Direction | Status |
|---|---|---|
| `STATE` deltas (cap 64) + `companion-api.json` | mod → app | Built (app side: companion cards) |
| `COMMAND` / `COMMAND_RESULT` (cap 128) | both | Built with the hotkeys work (branch not merged yet) |
| `SUBSCRIBE` `keys` | app → mod | Built: the mod sends only keys a visible card uses (idle cost 0) |
| `ELEMENTS_CHANGED` (0x86, cap 256) | mod → app | Built: the mod's element set changed (scene change), the editor refreshes without polling |
| `FEATURES` (`features.json` next to `elements.json`) | mod → app | New: what the mod supports, see `uniform-editing.md` |
| `LOG` (0x87) `level u8, text` | mod → app | Built: mod log lines reach logcat and the dev tools, no file pulling |
| `HAPTIC` (0x88) `ms u16, strength u8` | mod → app | Built: a tap on a bottom-screen card can buzz |
| Key/button injection by the app | app → X server | Built with the hotkeys work: hotkeys and companions drive games with **no mod** |

Rules carried over: IPv4 127.0.0.1 only, accept only from the session's port, receive threads only queue, the game
thread applies, state capped ~30/s, commands go through the game's own UI paths (never memory or save writes).

Version the protocol in the header byte only when the framing changes; features go in capability bits.

### SDKs

A developer should never pack `'G' 'D' 0x01` by hand. One API, same names in every language:

```
Duo.Layout                     // screen, top, bottom, gap, scale; Duo.OnLayoutChanged
Duo.IsSplit                    // window matches layout.screen
Duo.OnTouch(fn)                // bottom-screen fingers in window px, already queued to the game thread
Duo.Elements.Register(id, name, rectFn, features)   // a plugin element, with what the editor may do to it
Duo.Rule(id)                   // the user's rule (screen, scale, x, y, options), cached, versioned
Duo.Publish(key, value)        // companion state; dropped unless subscribed
Duo.OnCommand(name, fn)        // returns ok / not now + reason
Duo.Log(level, text)
```

| Package | Language | Built from | Licence |
|---|---|---|---|
| `GameNativeDuo.Sdk` (NuGet) | C#, netstandard2.0, no Unity references | `Bridge.cs`, `Layout.cs`, `MiniJson.cs`, `UiRules.cs` from the Unity and Stardew adapters | MIT |
| `GameNativeDuo.Sdk.Unity` | C# | `UiRouter`, `CameraPlacer`, `TouchInjector` as reusable components | MIT |
| `GameNativeDuo.Sdk.Smapi` | C# | Stardew's mouse postfix, `uiScreen` routing helpers | MIT |
| `duo.lua` | Lua (LÖVE, LuaJIT ffi) | `duo_bridge.lua`, `duo_util.lua` from Balatro | MIT |
| `duo.h` + ASI template | C/C++ (any DX game via Ultimate ASI Loader) | new | MIT |

MIT on purpose: SDKs are linked into mods, and GPL in an SDK would scare off developers of closed-source mods. The
app stays GPL-3.0; our own adapters stay GPL-3.0.

The bundled adapters (Unity, Stardew, Balatro) are rebuilt on the SDKs, so the SDK is always the tested path. The SDK
version goes in HELLO's engine string (`unity-ugui/sdk1.2`) so the status screen can say "mod built for an older
GameNative Duo".

**Done when:** the three bundled adapters build on the SDKs with no behaviour change, and a new LÖVE or Unity mod
needs about 50 lines to put one element on the bottom screen.

---

## 2. Dev tools: the `duo` binary, its front-ends, developer mode

### Shape: one core, thin front-ends

No single form works alone. A web app cannot bind the UDP bridge, write into the game's `%AppData%`, capture a
window or run adb. A VS Code extension ties everyone to one editor, while Unity and Stardew modders mostly use
Visual Studio or Rider, Lua modders use anything, and profile makers use no editor at all. A desktop app would be a
second UI next to the PC editor, and nothing could script it. A Claude skill needs something to drive. So
everything lives in one local program, and every front-end is a thin skin over its API:

```
              ┌──────────────── duo (one binary: CLI + local server) ─────────────────┐
              │ bridge (UDP)   layout/ui/features/profile files   window capture      │
              │ build / pack / sign   adb (device)   test runner   conformance check  │
              │             local HTTP + WebSocket API: the "Duo API"                 │
              └───────────────────────────────────────────────────────────────────────┘
        ▲ browser UI              ▲ duo mcp               ▲ VS Code extension      ▲ the Thor
  pc-editor/index.html      Claude Code / any agent   webview of the same UI   PcEditorServer speaks the
  (sim, inspector, logs)    (build, tap, screenshot,  + tasks, schemas,        same API in developer mode:
                            check, elements, logs)    problem matcher          the same UI drives a device
```

- **The binary is the product.** One download for Windows and Linux, no installer. `duo sim` starts the simulator
  and opens the UI in the browser; every other command also runs headless (terminal, CI).
- **One UI, three places.** The UI is `assets/duo/pc-editor/index.html`, grown into a dev tool. It runs against the
  simulator (served by `duo`), against a real Thor (served by the app's `PcEditorServer`), and inside VS Code. A
  developer switches between the sim and a device without learning a new tool, and players already know the editor.
- **One API.** The Duo API is the contract between the UI, the MCP server, the extension and the device. Specify it
  next to the bridge (`engine-adapter-contract.md` style) so the app and `duo` cannot drift apart.

### Tech

- .NET (10, the current LTS; built: `duo/tools/duo`), native AOT, one self-contained binary per OS (win-x64, linux-x64, linux-arm64). Same language as the SDK
  and the C# mods, so `GameNativeDuo.Sdk`'s bridge and file code is shared, and developers install no runtime. Also
  published as a .NET global tool (`dotnet tool install -g gamenative-duo`) for those who have the SDK.
- Window capture: Windows Graphics Capture on Windows; XComposite on Linux (Proton games run under Xwayland or X11).
  Fallback when capture fails: a transparent always-on-top overlay that draws the hinge gap and the bottom screen's
  outline over the real game window (no true-scale preview, still usable).
- The UI stays one HTML file with no dependencies (as now): embedded in the binary, bundled in the APK.
- macOS: not a target for now (PC games need CrossOver there).

### Duo Sim (`duo sim`)

Mods run inside Wine, so they behave the same on a PC: Windows natively, Linux under Proton. The sim plays the app's
part on the desktop so a developer iterates in seconds without a Thor.

- Writes `layout.json` into the game's `%AppData%\GameNativeDuo\` (device profile chosen in the UI: Thor, AYANEO
  Pocket DS, Retroid Dual Screen) and binds the bridge on UDP 127.0.0.1.
- Asks the developer to run the game windowed at `layout.screen` (or sets it for Unity through
  `-screen-width/-height`, as `UnityScreenArgs` does).
- Shows the captured window as the two physical screens: the top part, the hinge gap, the bottom part scaled by
  `bottomScale`. Clicks on the simulated bottom screen are sent as `TOUCH` (second finger with a modifier key).
- Writes `ui.json` and companion state exactly as the app does; companions render from the same manifest (a web
  port of `CompanionView`'s cards; the device view can share it later).

### UI tabs (sim and device alike)

| Tab | Shows |
|---|---|
| Layout | Today's PC editor: both screens, drag elements, background, widgets; gated by `EditCapabilities` (`uniform-editing.md`) |
| Inspector | Live element tree from `elements.json`: features, `present`/`when`, the rule, the rectangle; write a game profile from picks (section 3) |
| Bridge | Decoded traffic, HELLO, capabilities, `features.json`, packets per second |
| Logs | Bridge `LOG` lines, `GNDuo` logcat (device), BepInEx/SMAPI log tail (sim: from the game folder) |
| Console | Send commands, read and subscribe to companion state keys, send `RULES_CHANGED` / `DUMP_ELEMENTS` |
| Conformance | Run the check, per-element pass/fail, export the report for a package |

### CLI

```
duo new --engine unity|smapi|love|native <id>   # scaffold from templates (section 5), with a game profile stub
duo build                                        # dotnet/lua packaging per engine, references from the game folder
duo sim [--profile thor]                         # simulator + UI in the browser
duo dev [--sim | --device]                       # build, install into the sim's game or the device's dev folder, tail logs
duo test <script>                                # scripted run: generalises hk-test.txt / sdv-test.txt
duo check                                        # conformance check against the running game (uniform-editing.md)
duo pack                                         # validate against the community schemas, sign, write the package folder
duo submit                                       # open the community PR (gh), or print the share-form link
duo keygen                                       # author key (section 4)
duo mcp                                          # MCP server over the Duo API (stdio)
```

- `duo dev --device` pushes over Wi-Fi adb (`duo/agents/thor-wifi.sh`) into
  `Android/data/app.gamenative.duo/files/duo/mods/dev/<id>/`. The app reloads a dev mod's files at the next session
  start, like dev companions, and prefers a dev copy over the bundled one while developer mode is on.
- `duo test` scripts: `wait`, `tap x y` (bottom-screen px), `swipe`, `key`, `command`, `expect element <id> on
  bottom`, `expect state <key> = <v>`, `screenshot top|bottom`. The same script runs in the sim and on the device.

### `duo mcp` and the skill

The MCP server exposes the Duo API as tools, so any agent can run the loop a developer runs: `build`, `install`,
`launch`, `tap`, `swipe`, `screenshot` (top/bottom), `elements`, `set_rule`, `state`, `command`, `check`, `logs`
(tail and filter). It works against the sim or a device, like the UI.

Shipped with it:

- A Claude skill, `gamenative-duo-dev`: the contract, the SDK, the editing levels, and the traps we hit (Harmony
  `ref` arguments, IPv4 loopback, Unity resolution memory, never forcing `ShouldDrawOnBuffer` on Stardew's title...).
- `llms.txt` on the docs site, generated from the same reference pages.

Cheap once the API exists, and the community already builds dual-screen projects with AI help.

### VS Code extension (last, thin)

A webview of the same UI, tasks for `duo build` / `duo dev`, `duo check` failures as problems, schema registration
for our JSON files. Optional: JSON validation already works anywhere through `$schema` URLs to the docs site.

### Developer mode in the app

A setting in the GameNative Duo tab (global, off by default). It keeps the device side small; the heavy work runs in
`duo` on the PC:

- `PcEditorServer` serves the Duo API (behind the pairing code, never in the background), so the UI, `duo dev
  --device`, `duo test` and `duo mcp` all drive the Thor the same way they drive the sim.
- Overlay on the bottom screen (toggle in the two-finger menu): last HELLO, capabilities, engine and SDK version,
  bridge packets per second, `LOG` lines, frame times.
- Dev folder mods load; a "DEV" badge stays on the bottom screen and in the Dual screen control.

### Order inside this item

1. `duo` core + browser UI (sim, Layout, Inspector, Bridge, Logs, Console): covers every developer.
2. `duo mcp` + the skill + `llms.txt`.
3. Developer mode: the Duo API on the device.
4. VS Code extension.

**Done when:** a developer without a Thor makes a Balatro-style mod for another LÖVE game in an afternoon, then
`duo dev --device` runs it unchanged on a Thor; and an agent with `duo mcp` can build, test and screenshot a mod
without help.

---

## 3. Game profiles: per-game quirks as data

Most per-game work in the Unity adapter is configuration: which cameras are UI, which canvases go where, which
objects are crosshairs, which element sits on which screen by default. `Quirks.cs` holds the cases that are code
today (Subnautica, Team Cherry). A game profile moves everything that is configuration into a data file the official
adapter reads, so a new Unity game is a JSON file, not a DLL.

`profile.json` (next to `layout.json` in the prefix, installed from the mod package):

```json
{
  "format": "gamenative-duo/game-profile", "version": 1,
  "game": "STEAM_433340", "engine": "unity-ugui",
  "builds": ["1.4.4"],
  "cameras": {
    "ui": [{"name": "UICamera"}, {"layer": "UI", "only": true}],
    "world": [{"name": "Main Camera"}],
    "tk2d": [{"name": "HudCamera", "screen": "bottom"}]
  },
  "canvases": [
    {"path": "HUD Root/HudUI", "screen": "bottom"},
    {"path": "Pause Menu", "screen": "bottom", "fit": "contain"}
  ],
  "stayOnTop": ["*/crossHair", "*/Reticle*"],
  "elements": {
    "HUD Root/HudUI/UIContainer/crossHair": {"name": "Crosshair", "features": ["scale"]}
  },
  "state": [
    {"key": "player.hp", "type": "int", "from": {"type": "PlayerState", "member": "model.currHealth"}}
  ]
}
```

- Each engine adapter defines what its profile may say. Unity: cameras, canvases, sprite HUDs, stay-on-top names,
  element names and features, state read from public members by type and member path (reflection, read only, no
  method calls). SMAPI and LÖVE profiles come later, with the same top-level shape.
- `state.from` reads public fields and properties only. Calling methods, writing values or loading types the profile
  names from outside the game's own assemblies is not allowed: that would make data into code.
- Unknown keys are ignored (a newer profile on an older adapter); `builds` warns on a mismatch like packages do.
- Profiles are a community package kind (`game-profile`, data, one tap). The validator checks them against
  `schema/game-profile.schema.json`.
- Developer path: the PC editor's Inspector tab can write a profile: pick a camera or canvas, choose its screen,
  save. A modder makes a level 1 mod without writing JSON by hand.
- Migration: Slime Rancher and Subnautica's camera and canvas quirks become profiles; Team Cherry's tk2d handling
  becomes a `tk2d` camera kind; whatever stays in `Quirks.cs` is real code (and a sign the adapter is missing a
  feature).

**Done when:** Slime Rancher and Subnautica ship as profiles on the shared adapter with no per-game C#, and the
community repo accepts `game-profile` packages without code review.

---

## 4. Code trust: build from source, author keys, revocation

Level 2 mods are code with the app's full access. What protects users is where that code comes from.

### Build from source

- A code mod package in the community repo holds **source**, a build file and a lock of its dependencies, never
  binaries. CI builds it in a clean container (pinned .NET SDK, game reference assemblies supplied as stubs: we
  never host game files) and adds the output hashes to the package. The signed index lists only CI-built hashes.
- Exceptions: well-known loaders (BepInEx, SMAPI, Lovely, Ultimate ASI Loader) pinned to their official release
  hashes in `community/loaders.json`. A new loader version is a maintainer PR.
- Reproducible where the toolchain allows (`Deterministic`, `PathMap`, fixed timestamps): anyone can rebuild and
  compare.

### Review

- CI flags in the PR: network APIs (`System.Net`, sockets other than the SDK's bridge), file access outside the game
  folder and `%AppData%\GameNativeDuo`, process start, P/Invoke, reflection on types outside the game's assemblies,
  obfuscation. Flags are hints for the reviewer, not a verdict.
- A maintainer approves every code PR. An AI-assisted review of the diff is posted as a comment first.
- Updates show the diff since the last approved version; small updates are quick to review.

### Author keys

- An author signs their package manifest with an Ed25519 key (`duo keygen`, `duo pack` signs). The index records
  the key with the package. The app pins it at first install: an update signed by a different key shows "This mod
  changed owner" and needs consent again. Key rotation: the old key signs the new one.
- The index key stays the root of trust; author keys only add continuity between versions.

### Permissions and consent

- The manifest declares what the mod writes (`install` map: game folder paths, prefix paths, `WINEDLLOVERRIDES`) and
  which bridge features it uses (capabilities, commands). The installer writes only declared paths; the bridge drops
  commands and capabilities the manifest did not declare.
- Consent screen, once per version: who made it, what it writes, the honest sentence ("This mod runs code with the
  same access as your games"), the review status.

### Revocation

- `v1/revoked.json` in the signed index: package id, versions, reason. The app checks it with the catalog (ETag, no
  extra request when unchanged). A revoked mod is turned off through `DuoModInstaller`'s undo record at the next
  launch, with the reason shown.

### Hardening (upstream candidate)

Encrypt stored store credentials with the Android Keystore, so a mod that reads the app's files gets nothing usable.
It protects every GameNative user, not just modders; propose it upstream.

**Done when:** the Hollow Knight plugin goes through the CI build and is installed from the index with author key,
consent screen and a working revocation test.

---

## 5. Docs site and templates

### Docs site

GitHub Pages on the community repo (`nono5822.github.io/GameNativeDuo-community/docs/`), next to the signed index.

- **Start here**: what the bottom screen can be (region view, companion, split with a mod), with pictures.
- **Your first dual-screen mod in 15 minutes**: Duo Sim + `duo new --engine love` + one element on the bottom screen.
- **Guides per level**: make a UI layout; make a companion; make a game profile; write a code mod (Unity, SMAPI,
  LÖVE, native).
- **Reference**: the engine adapter contract, the companion manifest, the game profile schema, the SDK API, the
  feature list from `uniform-editing.md`. Generated from the schemas where possible, so it cannot drift.
- **Cookbook**: move a HUD, hide an element, add a live map element with options, publish state to a companion,
  handle touch in a game with no UI system, deal with a game that remembers its resolution.
- **Showcase**: every package with screenshots, author and ratings (from the index, no hand-kept list).
- **Rules**: licences, no game files, nothing touching DRM, single-player only for state and commands, the review
  process.

### Templates

`GameNativeDuo-templates` repo (or a folder in the community repo), each a working mod `duo new` copies:

| Template | Shows |
|---|---|
| `love-basic` | `duo.lua`, one element, touch |
| `unity-profile` | a game profile only (level 1) |
| `unity-plugin` | BepInEx plugin on `GameNativeDuo.Sdk.Unity`, a plugin element with options |
| `smapi-plugin` | SMAPI mod, a menu on the bottom screen, native touch |
| `native-asi` | `duo.h`, region-only plus state |
| `companion` | a companion with state cards and command buttons wired to a mod |

Each template has CI that builds it against the current SDK, so a broken template is caught before a developer
finds it.

### Community

- A Discussions category (or Discord) for mod developers; links from the docs and the app's mod explorer.
- Authors credited by name in the explorer and the consent screen; "Made with the Duo SDK" is enough of a badge.

**Done when:** someone outside the project follows the 15-minute guide on a clean PC and gets a mod on the simulated
bottom screen, and the docs link from the app's mod explorer.

---

## Uniform editing (part of this plan)

Full design in `uniform-editing.md`. It runs through every item above: the SDK exposes element features, the sim and
`duo check` run the conformance check, game profiles declare features, the docs explain the three editing levels.

| Step | What |
|---|---|
| U1 Declare and gate | `features` per element, `present`/`when`, `EditCapabilities` read by every editor surface, greyed controls with reasons; Balatro, Stardew, Unity declare |
| U2 Known before launch | `features.json` shipped with mods; editing-level tag on mod cards in the explorer and in the Dual screen control |
| U3 Regions as elements | Games without a mod: several regions, top masks, app-drawn background and widgets, all in the same editor |
| U4 Conformance | Check in developer mode, then `duo check` / Duo Sim; "Verified" tag; required for official mods |

## Order and dependencies

```
U1 Declare and gate ──▶ U2 Tags + known before launch
        │
        ▼
1 Bridge v2 + SDKs ──▶ 2 Dev tools  ──▶ 5 Docs + templates
        │                    │   └──▶ U4 Conformance in duo check / sim
        └──▶ 3 Profiles ─────┘
U3 Regions as elements (independent: renderer work)
4 Code trust (parallel; needed before the first third-party code mod)
```

U1 comes before 1 so the SDK exposes element features from day one.
