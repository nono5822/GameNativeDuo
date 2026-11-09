# Duo Companions: design

A **companion** is a small per-game app on the second screen: pages of cards (guides, checklists, maps, live
values, lists, timers, hotkeys) fed by data sources. Where an engine adapter moves the *game's own* UI between the
screens (`engine-adapter-contract.md`), a companion adds UI the game never had: Hollow Knight's live map and charm
list, Stardew's calendar and crop planner, Balatro's deck and run info, or just notes and a timer for any game.

Design reference: [Eden Duo companions](https://github.com/igawa6/eden-duo-companions) (GPL-3.0; we take its ideas,
not its code) and the PC entries in `existing-dual-screen-mods.md`.

## Goals

- **Declarative.** A JSON manifest and layout, no code, for the usual companion. The app draws native Android views;
  a modder writes data, never UI code.
- **Works with or without an in-game mod.** Static content and user input work for every game; live data needs a
  mod (bridge) or a readable game file.
- **Zero cost when idle.** Nothing is drawn when nothing changes; no data is computed for cards nobody sees.
- **Safe by default.** A data-only companion cannot run code, reach the network or read the user's files. Scripts
  are opt-in, sandboxed and consented to, like code mods.
- **Version gated.** Live sources read only on game builds the author tested; any other build shows a notice and
  reads nothing.
- **Shared like layouts.** A companion is a community package kind (`companion`), rated, per-device variants,
  live-reloadable while it is being written.

What Eden Duo got right and we adopt: version gating per game build, context-sensitive cards, staged actions applied
through the game's own UI, no game assets in packages, data generators per game, a porting guide. Where we differ:
Eden reads emulated memory; on PC we get data from mods that call the game's own APIs, and never read or patch
process memory (anti-cheat and DRM territory).

## Where a companion shows

| Session | Bottom screen | Companion |
|---|---|---|
| No screen split (most games) | Free | The companion **is** the bottom screen (replaces the devkit panel). |
| Region view | A crop of the frame | Opened from the bottom-screen menu as a full page; "Back to game" returns. |
| Screen split with a mod | The game's lower part and UI | Same as region view. Later: single cards pinned over the game as UI layout elements. |

While a companion page covers the bottom screen in a split session, the bottom-screen render output is detached, so
the bottom part of the game costs nothing. The top screen (overlay cards above the game, e.g. a timer) is planned
for v2; the manifest already says where a card wants to go (`screen`) so v1 packages keep working.

The companion window is a non-focusable `Presentation`, like every bottom-screen panel: the game keeps the controller
and keyboard. Touch drives the companion. Text input (notes, checklist items) is a dialog on the main screen: with
the game running on display 0, Android binds the keyboard to that display's focused window, never to a bottom-screen
window, even a focusable one (tested on the Thor). The Thor then opens the keyboard itself on the bottom screen, in
full-screen mode, which suits a handheld. A controller chord to drive the companion with the D-pad is task 05
(hotkeys).

## Package layout

```
<companion-id>/
  companion.json        manifest (required)
  pages/*.md            long text for text cards (optional)
  images/*.png|jpg|webp maps, icons, diagrams the author drew (optional)
  data/*.json           static datasets: crop lists, birthdays, checklists (optional)
  script.js             only for script companions (code: consent, see below)
```

Bundled companions live in `assets/duo/companions/<id>/`; community ones in `files/duo/companions/<id>/`; companions
being written in `Android/data/app.gamenative.duo/files/duo/companions/dev/<id>/` (adb can push there without
`run-as`, but cannot create folders with `adb push`: `adb shell mkdir -p` first; the app reloads them live, and
"Automatic" prefers a dev companion, so restart the game once after the first push). User state (ticks, notes, timers) lives apart from the package, in
`files/duo/companions/state/<containerId>/<id>.json`, so updating or reinstalling a companion never loses it.

## Manifest (`companion.json`)

```json
{
  "format": "gamenative-duo/companion", "version": 1,
  "id": "hk-journal", "name": "Hallownest journal", "author": "you", "packageVersion": "1.0.0",
  "games": ["steam-367520"],
  "requires": {
    "api": {"name": "hk", "min": 1},
    "builds": [{"source": "game.build", "in": ["1.5.78.11833"]}]
  },
  "data": {"charms": "data/charms.json"},
  "pages": [
    {"id": "map", "title": "Map", "cards": [{"type": "map", "image": "images/world.png", "marker": {"x": "hk.map.x", "y": "hk.map.y"}}]},
    {"id": "charms", "title": "Charms", "columns": 2, "cards": [
      {"type": "value", "label": "Notches", "value": "{hk.notches.used} / {hk.notches.max}"},
      {"type": "list", "items": "hk.charms.equipped", "text": "{name}", "when": {"key": "hk.charms.equipped", "exists": true}},
      {"type": "text", "text": "Sit on a bench to change charms.", "when": {"key": "hk.scene.bench", "eq": false}}
    ]}
  ]
}
```

- `games`: store keys as in the community index (`steam-413150`), or `["*"]` for a companion for every game.
- `requires.api`: the bridge API the live cards need (name and minimum version announced by the mod, below).
  Missing: live cards show "Needs <mod>", static cards still work.
- `requires.builds`: the game builds the author tested. Each entry names a key that identifies the build (a bridge key
  the mod sends, or `file:<path>` for a file's sha256 in the game folder, hashed once per session on a background
  thread) and the accepted values. **On any other build, live sources read nothing** and the companion shows
  "Made for game version X; this is Y". Static pages still show.
- `pages`: shown as tabs (chips) along the top. `columns` 1 (default) or 2; cards flow in order, `span: 2` takes a full
  row. Units are dp and fractions, never pixels, so one companion fits 3.9" and 5" screens.
- `variants` (task 06): `{"<device class>": {"pages": [...]}}` replaces pages on a device class (`small`, `tall`...).
- Unknown card types and unknown fields are ignored with a note on the card ("Needs a newer GameNative Duo"), never
  an error: old apps keep showing new companions.

### Cards (v1)

| Type | Shows | Main fields | Source |
|---|---|---|---|
| `text` | Paragraphs, `#` headings, `-` bullets, `**bold**` | `text` or `file` (`pages/x.md`) | static |
| `checklist` | Items with ticks, optional groups | `items` (strings or `{id, text, group}`), `file`, `userItems` | static + user |
| `notes` | The user's own text, per game | `hint` | user |
| `timer` | Stopwatch with laps, or countdown | `mode` (`stopwatch`, `countdown`), `seconds` | user |
| `image` | A picture, pinch to zoom and pan | `image` (in the package), `user: true` (picked by the user) | static / user |
| `value` | A label and a formatted value | `label`, `value` (template) | any |
| `progress` | A bar | `label`, `value`, `max` (templates or numbers) | any |
| `list` | One row per array element | `items` (key), `text` (template per element), `empty` | any |
| `map` | An image with live markers | `image`, `marker: {x, y}` (keys, image fractions) | static + live |
| `hotkeys` | Buttons that press keys or send commands | `buttons: [{label, keys} or {label, command}]` | user → game |

Planned: `region` (a live crop of the frame inside a card; needs a second output surface in the renderer), `table`,
`calendar`, `html` (script companions only).

**Templates**: `"{hk.geo}"`, `"{sdv.money:n0} g"`, `"{x:pct}"`; inside a `list`, `{name}` is the element's field.
Missing keys print `--`. Formats: `n0`, `n1`, `pct`, `time` (seconds as m:ss), `upper`.

**Conditions** (`when`, on cards and pages): JSON, no expression language to parse or exploit.
`{"key": k, "eq"|"ne"|"gt"|"lt"|"in"|"exists": v}`, `{"all": [...]}`, `{"any": [...]}`, `{"not": {...}}`. A card
whose condition is false is gone (takes no space). A page with `"focusWhen"` comes to the front when its condition
turns true (the shop page when a shop opens), unless the user touched the companion in the last 10 s.

## Data sources

Every source writes into one key-value store per session (`CompanionData`: dotted keys, JSON values). Cards read
keys; nothing reads a source directly. Prefixes say where a value came from:

| Prefix | Source | Cost |
|---|---|---|
| `<api>.` (`hk.`, `sdv.`) | **Bridge**: the game's mod sends state deltas, receives commands | mod-side, only subscribed keys |
| `static.` | **Static content**: `data/*.json` in the package (`"data": {"crops": "data/crops.json"}` → `static.crops`) | read once |
| `user.` | **User input**: ticks, notes, timers, picked images, persisted per container | on change |
| `session.` | **The app**: play time, battery, clock, FPS (the same metrics as the widgets) | 1 Hz while shown |
| `file.` | **Game files** (v2): a readable text/JSON file in the prefix or game folder, re-read on change (inotify). Log lines matched by regex. Read-only, gated by build. | on change |
| `region` cards | **Region views** of the frame (v2) | GPU only, while shown |

### Bridge additions (protocol version stays 1, new types)

Built on the app side (STATE into `CompanionData`, SUBSCRIBE from the shown page, `bridge.json`); full table and
limits in `engine-adapter-contract.md`.

| Type | Direction | Payload after the 4-byte header |
|---|---|---|
| `0x04` SUBSCRIBE | app → mod | UTF-8 JSON `{"keys": ["hk.map", "hk.charms"]}`: the keys visible cards read (a key also covers `key.*`); `[]` when the companion is hidden |
| `0x05` COMMAND | app → mod | `request u32`, UTF-8 JSON `{"cmd": "hk.equip", "args": {...}}` |
| `0x84` STATE | mod → app | `flags u8` (1 = full snapshot: drop the api's old keys), 3 bytes padding, UTF-8 JSON object of changed keys (`null` deletes) |
| `0x85` COMMAND_RESULT | mod → app | `request u32`, `status u8` (0 ok, 1 refused now, 2 unknown, 3 failed), UTF-8 JSON message |

- HELLO capabilities: `64` state, `128` commands. A mod with state also writes `companion-api.json` next to
  `elements.json`: `{"name": "hk", "version": 1, "keys": {...}, "commands": {...}}` (names, types, descriptions:
  the SDK reads it to autocomplete and validate manifests).
- **Zero cost**: the mod computes and sends only subscribed prefixes, at most 30 deltas/s, each under 8 KB (larger
  states are split). The app applies deltas on its receive thread and redraws visible cards at most 10 times a
  second, only those whose keys changed. Hidden companion: `SUBSCRIBE []`, the mod sends nothing.
- `bridge.json` (`{"port": N}`, next to `layout.json`) is written whenever a bridge is open, also in sessions without
  a screen split, so a companion-only mod knows where to send; `layout.json` keeps meaning "the window is split".

### Commands and staged actions

A command asks the mod to do something **through the game's own UI paths** (the functions the game's menus call, on
the game thread, only in states where the game itself would allow it), never by writing memory or save files. Two
kinds:

- **Immediate**: `hk.map.zoom`, `sdv.toolbar.select`. Fine for anything a button press could do.
- **Staged** (Eden Duo's idea): the user builds a plan in the companion (a charm loadout, a shopping list, a shop's
  items to buy) without touching the game; the plan is sent as one command when the user taps Apply, and the mod
  applies it step by step through the game's UI (only at a bench, only with the shop open...). It answers `refused
  now` with a reason when the game state does not allow it ("Sit on a bench first"). The plan stays staged until it
  applies.

Hotkey buttons without a mod press keys through the X server (the same injection as the on-screen controls), so any
game gets a hotkey card.

## Script companions (opt-in, v2)

For what JSON cannot express (a planner with real logic, a reused HTML UI). `script.js` runs in a WebView that has:
no network (every request except the package's own files is blocked), no file or content access, no JavaScript
interfaces beyond `duo` (`duo.get(key)`, `duo.on(prefix, fn)`, `duo.set("user.x", v)`, `duo.command(cmd, args)`,
`duo.publish("script.x", v)` so native cards can show computed values). A script companion is a code package: it
needs the user's consent once per version, is reviewed before merge, and the WebView exists only while its page
shows. Data-only companions never create a WebView.

## Runtime (app side)

`app/gamenative/duo/companion/`:

- `CompanionManifest`: parse and validate (`companion.json` → pages and cards; unknown things kept as "unsupported"
  cards). Pure, unit-tested.
- `CompanionData`: the session's key-value store, conditions and templates. Pure, unit-tested.
- `CompanionStore`: finds companions (bundled, community, dev folder), which one a container uses, user state.
- `CompanionView`: builds native views from the manifest (tabs, scrolling column, cards), re-evaluates conditions and
  templates only when keys it uses change. No animation, no polling: timers tick once a second only while running
  and on screen.
- Hosts: the bottom panel (no split) and the bottom-screen menu ("Companion", split and region view).
- The bottom screen asks for 60 Hz while a companion shows (a static UI needs no 120 Hz).
- The GameNative Duo settings tab chooses the companion per container: Automatic (the game's own companion, else
  the general one), Off, or any companion that lists the game.

Editor integration: v1 places the companion as a whole (it is the bottom screen). Pinned cards over the game (v2)
are UI layout elements with ids `companion/<id>/<card id>`, so the existing editors (bottom screen and PC) move,
resize and hide them with no new editor.

## Sharing

- Community package kind `companion`. Data-only (`.json`, `.md`, images) installs in one tap; with `script.js` it is
  code (consent, review). Index fields: `games`, `requires.api`, `requires.builds`, device variants.
- **No game assets in packages**: no ripped sprites, maps or text from the game. Maps are drawn by the author, or made
  on the device by a **generator** from the user's own copy: a step in the game's mod (or a PC tool) that exports
  data the game already has (Stardew's crop and birthday tables, Hollow Knight's room list) into
  `files/duo/companions/generated/<game>/`, which manifests read as `static.` data with `"generated": true`. The
  package ships the generator, never its output.
- Ratings, updates and per-device variants work like other packages (`community-packages.md`, `community-ratings.md`).

## Porting an existing dual-screen mod

| Source | What it has | Where its data comes from | As a companion |
|---|---|---|---|
| Android port with its own second screen (Silksong Android, Zelda 3 Android) | A second screen drawn inside the game | The game's own memory, in process | An engine adapter that sends the same values as bridge `STATE`; its screens become pages; its buttons become commands |
| Eden Duo companion (Switch, in an emulator) | Version-gated memory reads, context cards, staged actions | Emulated memory, per title version | Version gates → `requires.builds`; context → `when` / `focusWhen`; staged actions → staged commands. Data from a mod, not memory |
| PC web companion (Skyrim Web Monitor: SKSE plugin, local web server, browser UI) | A game plugin and a web page | The plugin, over HTTP | The plugin publishes `STATE` instead of serving HTTP; the page becomes native cards, or a script companion reuses the HTML (no network) |
| PC mod + phone app (Minecraft Second Screen: Java Edition mod + Android app) | Tabs: HUD, containers, map, chat, hotkeys | The mod, over the network | The mod sends over the UDP bridge (port from `bridge.json`); tabs become pages; hotkeys become `hotkeys` cards |
| Closed vendor app (Fallout 4 Pip-Boy) | A private protocol | The game, closed | Cannot be ported; write a mod and companion from scratch, reuse none of its art |
| No mod at all | Nothing | Nothing | Static guides, checklists, notes, timers, hotkeys; region cards (v2) |

Rules for every port:

- Namespace keys by api (`hk.`, `sdv.`, `balatro.`) and list them in `companion-api.json`.
- Send deltas, only for subscribed prefixes, at most 30 per second.
- List the builds you tested in `requires.builds`; bump the api version when a key changes meaning.
- Ship no game assets: generate maps and data on the device from the user's own copy.
- Respect the original's licence: take ideas, write your own code unless the licence allows reuse (GPL code cannot
  go into an MIT package).

## Build order

1. **04a** this design.
2. **04b** runtime: manifest, data store, native cards (text, checklist, notes, timer, image, value, progress, list),
   hosts on the bottom screen, settings row, live reload of the dev folder, 60 Hz while shown.
3. **04c** companions: the general one (guide, checklist, map image, notes, timer) for every game; then bridge
   `STATE`/`SUBSCRIBE`/`COMMAND` with Hollow Knight (live map, charms, progress), Stardew (calendar, birthdays, crops
   via a generator), Balatro (run info, deck).
4. **04d** SDK: `schema/companion.schema.json` and validator in the community repo, `duo-dev companion
   new|validate|push`, two templates (static, bridge), a 10-minute tutorial.
