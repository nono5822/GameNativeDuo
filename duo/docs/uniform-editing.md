# Uniform editing: one editor that behaves the same in every game

The UI layout editor (bottom screen, PC editor, quick menu) must never offer something that will not happen. Today it
does, because each dual-screen mod supports a different subset of the contract, and the editor assumes all of it.

## What goes wrong today (2026-11-07)

| Problem | Example | Why |
|---|---|---|
| Editor empty or missing elements | Balatro on the title screen: no elements at all. Stardew: Buffs/Chat missing until they show | Mods list only elements on screen now (`write_elements` only during a run; `HudLayout.WriteElements` skips absent menus) |
| A gesture does nothing | Pinch "Menus", "Consumables" or "Hand info" in Balatro: no change. Drag "Menus": no change | Balatro reads `scale` only for Hand and `x`/`y` only for Hand, Consumables, Hand info. The editor allows every gesture on every element |
| A screen choice does nothing or something else | Balatro "Hand info" → top; "Hand" → hidden (behaves like top) | Each element supports its own screens; only plugin elements can say so (`screens`) |
| Different games, different menus | "Edit UI layout" greyed in a game without a mod, active in another | No mod, no elements: the whole editor is off, with one line of text |
| Background modes differ | Blur in Unity games, Balatro | Already handled well: modes the mod does not announce are greyed (the pattern to extend) |

Bundled mods today:

| Mod | Elements | screens | move | scale | Background | Listed when |
|---|---|---|---|---|---|---|
| Unity (shared) | every UI object | top, bottom, hidden | yes | yes | color, image | always (scene objects) |
| Stardew | HUD, toolbar, bars, buffs, chat... | top, bottom, hidden | yes | yes | all | only menus on screen |
| Balatro: Hand | 1 | bottom, top | yes | yes | game, color, image | during a run |
| Balatro: Consumables | 1 | bottom, top | yes | no | | during a run |
| Balatro: Hand info | 1 | bottom, hidden | yes | no | | during a run |
| Balatro: Menus | 1 | bottom, top | no | no | | while a menu is open |
| Hollow Knight: Live map | plugin element | bottom, hidden | yes | yes | (Unity) | always |
| No mod (region view) | none | | | | none | |

## Two rules

1. **Declare, then gate.** Every element says what may be done to it, and every editor surface reads one model and
   shows only what is declared. Anything unavailable is greyed with the reason, never silently ignored.
2. **The same floor everywhere.** Every game, even without a mod, gets the same editor with the same three tabs
   (Elements, Background, Widgets). What fills the Elements tab comes from the mod or, without one, from regions
   the user draws (below). "Edit UI layout" is greyed only when there is no frame at all (Gamescope).

## 1. Element features (contract addition)

`elements.json` elements get a `features` object. It generalises the existing `screens` and `options`:

```json
{"id":"Menus","name":"Menus","parent":-1,"screen":"bottom","x":340,"y":1180,"w":1100,"h":800,"graphic":true,
 "present":true,
 "features":{"screens":["bottom","top"],"move":false,"scale":false},
 "when":"While a menu is open"}
```

| Key | Meaning | Absent means |
|---|---|---|
| `features.screens` | Screens the editor offers (`hidden` = can be turned off) | top, bottom, hidden |
| `features.move` | `x`/`y` are honoured | true |
| `features.scale` | `scale` is honoured; or `{"min":0.5,"max":2}` | true, 0.25–4 |
| `options` | Existing plugin-element options | none |
| `present` | Element is on screen now (rectangle valid) | true |
| `when` | Short text: when it appears ("During a run") | none |

- `screens` and `options` stay accepted at the top level of an element (current plugin elements); `features.screens`
  wins when both exist.
- **Absent means everything** so old mods behave as today. A mod that declares `features` on any element is
  "declaring" and is held to it by the conformance check (section 5).
- **List every element the mod knows, in every scene**, with `present: false` (and the last or default rectangle,
  or `w`/`h` 0) when it is not on screen. The editor shows absent elements in the list, greyed, with `when`; their
  screen and options can still be set from the list; move and scale wait until they are on screen.

### Mod-level features: `features.json`

Written next to `elements.json` at start (and shipped in the mod package so the app knows it before launch, and while
GameNative's menu pauses the game):

```json
{"format":"gamenative-duo/features","version":1,"engine":"balatro-love",
 "touch":true,"background":["game","color","image"],
 "elements":[
   {"id":"Hand","name":"Hand","when":"During a run","features":{"screens":["bottom","top"],"move":true,"scale":true}},
   {"id":"Consumables","name":"Consumables","when":"During a run","features":{"screens":["bottom","top"],"move":true,"scale":false}},
   {"id":"Hand info","name":"Hand info","when":"During a run","features":{"screens":["bottom","hidden"],"move":true,"scale":false}},
   {"id":"Menus","name":"Menus","when":"While a menu is open","features":{"screens":["bottom","top"],"move":false,"scale":false}}],
 "dynamic":false}
```

- `elements` lists the fixed elements (Balatro, Stardew, plugin elements). `dynamic: true` means more elements come
  from the running game (Unity scenes); those carry their own `features` in `elements.json` or use the defaults.
- Background modes move here from the HELLO bits (the bits stay for old mods).
- Bridge: mods send `ELEMENTS_CHANGED` when their element set changes (scene change) instead of the editor polling.

## 2. One model in the app: `EditCapabilities`

A new class in `duo/ui/` that every surface reads instead of checking bridge bits itself:

```
EditCapabilities(
  source: MOD | REGIONS | NONE,          // where elements come from
  reasonOff: String?,                    // whole editor off: "Gamescope has no X frame"
  elements: List<ElementCaps>,           // id, name, present, when, screens, move, scale range, options
  background: Set<UiBackgroundMode>, backgroundReason: String?,
  widgets: Boolean,                      // always true while a session runs
)
```

Built from: the mod's `features.json` (bundled copy before launch, live copy in session), HELLO capabilities (old
mods), the latest `elements.json`, the split mode, and the display server. It replaces `canEdit`/`modSeen` checks in
`UiLayoutController`, `DuoMenuPanel`, `DuoQuickMenu`, `SplitScreenController` and `PcEditorServer` (which sends it to
the web editor as JSON).

Editor behaviour from the model, the same on device and PC:

- Drag only when `move`; pinch and +/- only when `scale` (clamped to its range); screen chips only for `screens`.
- Touching a disabled control shows the reason in the toolbar line for 2 s: "Balatro's mod cannot resize Menus".
  Reasons are built from names, never raw ids.
- An element with no features at all (screens fixed, no move, no scale, no options) is listed but not selectable on
  the canvas: it is shown so the user knows it exists.
- Absent elements: in the list with their `when`, greyed.
- The element list header names the source: "Elements from the Balatro mod (4)", "Your regions (2)".

## 3. The floor: regions as elements for games without a mod

Without a mod, the Elements tab edits **regions**: rectangles of the game frame the user draws on a snapshot of the
top screen. A region is an element like any other, with the same verbs:

| Verb | Region meaning |
|---|---|
| screen `bottom` | The region is shown on the bottom screen (zero-copy crop, today's region view, now several) |
| screen `top` | Not copied (default for a new region) |
| screen `hidden` | Shown on the bottom screen **and** masked on the top screen (moves a HUD off the game view) |
| move, scale | Its place and size on the bottom screen |
| options | `filter`: sharp (nearest) / smooth; `touch`: taps click the source area / off |

- Stored in the same `files/duo/ui/<game>.json` under `regions` (new key; adapters ignore it), so layouts with
  regions share through community packages like any layout. A game that later gets a mod keeps its regions file
  separate from mod rules.
- Background (color, image, dim) and widgets are drawn by the app behind and around regions: the app owns the bottom
  surface in this mode, so these work in every game.
- Renderer work (in `duo/native` and `RegionView`): N crops in one pass of the bottom output (still zero-copy,
  redraw only on frame change); a top-screen mask is a black rectangle drawn by `VulkanRenderer` after the game frame.
  Bounded to a few regions (8) to keep the cost flat.
- This is architecture item 2 ("several regions, top-screen masks, touch layout editor") delivered through the UI
  layout editor instead of a separate tool.

With this, every game has: Elements (mod or regions), Background, Widgets, Line up screens, Companion. The only
difference between games is how fine the elements are, and the list header says which.

## 4. Telling users before they launch

From the bundled or packaged `features.json`, the mod explorer and the Dual screen control show one line per mod:

- **Full editing**: every element offers top, bottom and hidden, move and scale; background color and image.
- **Partial editing**: anything less; the details sheet shows the per-element table above.
- **Regions**: no mod; you choose parts of the screen.

The community index computes the level from the package's `features.json` (bundled mods: from their bundled copy).

### Tag in the mod browser

Each mod card in the mod explorer (`ModExplorer.kt`, `Badges(entry)`) shows the level as one more badge, next to the
existing Official / Community / Code / kind / Experimental badges:

| Tag | Colour | Tap on the card's details |
|---|---|---|
| Full editing | logo blue outline | "Every element: top, bottom or hidden, move, resize" |
| Partial editing | neutral | The per-element table (screens, move, resize), with each element's `when` |
| Regions | neutral | "No game mod: choose parts of the screen to show below" |
| Verified | small check after the level | The mod passed the conformance check (section 5) |

- Data-only packages (layouts, settings) inherit the tag of the mod they apply to and show no tag of their own.
- A mod without `features.json` (old or third-party) shows no editing tag until it declares one: no guessing.
- The Dual screen control next to Play shows the same text in its status line ("Balatro mod · Partial editing").

## 5. Conformance check: declarations must be true

A mod that declares features must honour them; otherwise uniformity is only on paper. The check runs in Duo Sim, in
`duo check` (devkit-platform.md), and in the app's developer mode:

1. Read `features.json` and `elements.json`.
2. For each present element and each declared feature: write a test rule (other screen; `scale` ×1.5; `x` +0.1),
   send `RULES_CHANGED`, wait two frames, `DUMP_ELEMENTS`, and compare the rectangle (screen changed; size ×1.5
   ±10%; moved by a tenth of the screen ±2%). Restore the user's rule.
3. Report pass/fail per element and feature. Undeclared features are not tested.

- Bundled mods and packages marked official must pass on every element present in the test script's scenes.
- Community packages show "Features verified" when the submitter attaches a passing report (the `duo test` script
  and its output); the index stores it.

## Plan

**Phase 1: declare and gate (app + bundled mods).** `EditCapabilities`; parse `features`, `present`, `when`; gate
gestures, chips and the PC editor; reasons in the toolbar; absent elements in the list. Balatro: declare features
as in the table, list all four elements always, `features.json`. Stardew: list absent menus with `present: false`.
Unity: declare `screens` for crosshairs (top, hidden) and plugin elements; everything else keeps the defaults.
Contract update in `engine-adapter-contract.md`. Unit tests for `EditCapabilities` and the parser.

**Phase 2: known before launch.** `features.json` in mod packages and the bundled catalog; editing level in the mod
explorer (tag on every mod card) and the Dual screen control; the editor uses the static list while GameNative's menu pauses the game.

**Phase 3: regions as elements.** Several regions, top masks, app-drawn background and widgets in region mode; the
region editor becomes the Elements tab for games without a mod.

**Phase 4: conformance.** The check in developer mode first (no tooling needed), then in `duo check` and Duo Sim;
required for official packages.

Close the gaps on the mod side as they are found, where the game allows it (Balatro: scale for Consumables and Menus
is likely possible through the UIBox scale; Hand on `hidden` makes little sense and stays undeclared).
