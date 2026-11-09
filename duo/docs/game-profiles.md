# Game profiles: Unity games as data

Level 1 of the devkit ladder (`devkit-platform.md`): a game profile configures the official Unity adapter
(`duo/mods-src/unity`) for one game. No compiler, no code review: the adapter is ours and signed, the profile only
says what the adapter's heuristics would otherwise guess. Most new Unity games should need a profile and nothing else.

## Where it lives

- In a package: `profile.json` at the package root (`kind: "game-profile"`, data, one tap; or inside a `mod` package).
- Bundled: `app/src/main/assets/duo/mods/<id>/profile.json`.
- In the game's prefix: `%AppData%\GameNativeDuo\profile.json` (next to `layout.json`), copied there by the
  Dual screen switch. The adapter reads it at start and again when it changes.

## Format

```json
{
  "format": "gamenative-duo/game-profile", "version": 1,
  "game": "steam-433340", "engine": "unity-ugui",
  "builds": ["1.4.4"],
  "cameras": {
    "ui": [{"name": "UICamera"}, {"layer": "UI", "only": true}],
    "world": [{"name": "Main Camera"}]
  },
  "canvases": [
    {"path": "HUD Root/HudUI", "screen": "bottom"},
    {"path": "Pause Menu", "screen": "bottom"}
  ],
  "stayOnTop": ["*/crossHair", "*/Reticle*"],
  "elements": {
    "HUD Root/HudUI/UIContainer/crossHair": {"name": "Crosshair", "features": {"scale": {"min": 0.5, "max": 2}}}
  },
  "state": [
    {"key": "sr.money", "from": {"type": "SceneContext", "member": "Instance.PlayerState.model.currency"}}
  ]
}
```

| Key | Meaning | Absent |
|---|---|---|
| `cameras.ui` | Cameras that draw the game's UI: they go to the bottom screen. A matcher is `{"name": glob}`, or `{"layer": "UI", "only": true}` (draws that layer and nothing else) | The adapter's own rule: UI layer only, or named like `UICamera` / `HudCamera` |
| `cameras.world` | Cameras that are never UI, even if the heuristic says so | none |
| `canvases` | A canvas (by object path from the scene root, glob) and its default screen: `bottom`, `top`, `hidden` | bottom |
| `stayOnTop` | Element paths (glob) that stay on the top screen unless the user moves them | names containing crosshair, reticle, reticule |
| `elements` | Per element id: `name` shown in the editors, `features` (`screens`, `move`, `scale`, as in `engine-adapter-contract.md`) | the object's name, everything allowed |
| `state` | Companion state the adapter publishes: `key` (under the game's api, e.g. `sr.`), `from.type` (a type name in the game's own assemblies) and `from.member` (a dotted path of **public** fields and properties, the first one static) | none |
| `builds` | Game builds the author tested; on another build the adapter logs a warning and reads no `state` | no check |

Globs: `*` matches any run of characters except `/`, `**` any run including `/`, compared case-insensitively.

## Rules (why a profile stays data)

- `state.from` reads public fields and properties only. No method calls, no writes, no types from outside the game's
  own assemblies: that would turn data into code. A member that throws or is missing reads as absent.
- Unknown keys are ignored, so a newer profile works on an older adapter (it just does less).
- Everything a profile can say has a default: an empty profile changes nothing.
- What stays in `Quirks.cs` is real code (Harmony patches: Subnautica's canvas scaler and cursor, Team Cherry's aspect
  letterbox). A game that needs a new quirk is a sign the adapter is missing a feature, not a reason for a code mod.

## Making one

1. `duo new --engine unity me.mygame --game steam-<appid>` writes a stub `profile.json`.
2. Run the game under `duo sim` (or on a device in developer mode) and open the Inspector tab: it lists cameras,
   canvases and elements as the adapter sees them.
3. Fill in what is wrong, save: the adapter picks it up live. `duo check` verifies the features you declared.
4. `duo pack` and a PR to the community repo: `game-profile` packages need no code review.

Schema: `duo/docs/schema/game-profile.schema.json` (also in the community repo's `schema/`).
